package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.data.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * [MediaSource] adapter over a [SubsonicClient]. Walks the canonical
 * Subsonic drill-down — artists → albums → songs — and surfaces every
 * song as a [DiscoveredTrack] streaming straight off the server, so a
 * Subsonic/Navidrome/Airsonic library can seed stations through the
 * same plug-in path as the local file source.
 *
 * Construction mirrors [ScrobbleSync]'s gate seam: the network policy is
 * supplied as an `allowNetwork: () -> Boolean` lambda rather than the
 * concrete [NetworkPolicyGate], because the gate's decision depends on
 * several runtime knobs (ConnectivityPolicy + online + unmetered) and a
 * lambda is the simplest honest test seam. Production binds it to
 * `NetworkPolicyGate.allowAutomaticFetch()` in `sharedModule`. [nowMs]
 * is the usual injectable clock (production = [nowMillis]) so tests can
 * pin the cache TTL deterministically.
 *
 * Server credentials are read live from [SettingsStore] on every
 * [listTracks] collection so an instance bound once at startup always
 * reflects the user's current configuration (no stale snapshot).
 *
 * Walk cost: a full drill-down is one `getArtists` call plus one call
 * per artist and one per album — thousands of round trips on a large
 * server. Two mitigations keep that affordable:
 *
 *  1. The album/song fan-out runs with at most [MAX_CONCURRENT_REQUESTS]
 *     requests in flight (a [Semaphore] — deliberately conservative so a
 *     500-artist walk never opens 500 sockets against a home server).
 *     Results are awaited in artist/album order, so emission order stays
 *     deterministic regardless of which request finishes first.
 *  2. A clean walk is cached in memory for [CACHE_TTL_MS], keyed on a
 *     server-URL + username fingerprint, so back-to-back station-rule
 *     evaluations share one walk instead of re-enumerating the library.
 *     The cache is single-flight: concurrent collections park on a
 *     [Mutex] and the late ones are served from the entry the first one
 *     filled. Editing the server URL or username invalidates the entry;
 *     stream URLs embed auth lazily at emission time, so a password-only
 *     edit still yields URLs signed with the current credentials.
 *
 * Error contract: enumeration is best-effort. A failure fetching one
 * artist's albums, or one album's songs, is swallowed (`runCatching`)
 * so the rest of the library still surfaces — a single unreachable
 * album never aborts the whole walk. The flow therefore COMPLETES
 * normally with partial results rather than throwing. A failure listing
 * the top-level artists yields an empty flow (nothing to walk). Partial
 * results are NOT cached — pinning a half-walk for the full TTL would
 * hide the missing subtree behind a transient hiccup — so the next
 * collection retries the full walk.
 */
class SubsonicSource(
    private val client: SubsonicClient,
    private val settings: SettingsStore,
    private val allowNetwork: () -> Boolean,
    private val nowMs: () -> Long = { nowMillis() },
) : MediaSource {
    /**
     * Fixed id so the registry key is stable across credential edits.
     * The [displayName] still reflects the current host for UI labels.
     */
    override val id: String = "subsonic"

    override val displayName: String get() = "Subsonic (${hostOf(settings.getSubsonicUrl())})"

    override val capabilities: SourceCapabilities =
        SourceCapabilities(
            supportsStreaming = true,
            supportsArtwork = true,
        )

    /** One memoized walk. Guarded by [cacheMutex]; see [cachedOrWalk]. */
    private var cachedWalk: CachedWalk? = null
    private val cacheMutex = Mutex()

    /**
     * Cold flow over the (possibly cached) enumeration. The gate is
     * checked FIRST, before the cache is even consulted — a denied
     * policy yields an empty flow with zero client calls AND zero
     * cached results, because the stream URLs the tracks carry would
     * be played against the very connectivity policy the gate
     * enforces. A blank server URL likewise yields nothing (no server
     * to query).
     *
     * Filtering: [SourceFilter.formats] is matched against the song's
     * lowercased suffix; the duration window
     * ([SourceFilter.minDurationMs] / [maxDurationMs]) is evaluated from
     * [SubsonicSong.durationSec] (× 1000) DURING emission, because
     * [DiscoveredTrack] carries no duration field — once a track is
     * surfaced its duration is gone, so the filter must run here. The
     * cache holds RAW songs (pre-filter), so collections with different
     * filters share the same walk.
     */
    override fun listTracks(filter: SourceFilter): Flow<DiscoveredTrack> =
        flow {
            if (!allowNetwork()) return@flow

            val server = serverOrNull() ?: return@flow

            // A failure listing artists leaves nothing to walk — empty flow.
            val songs = cachedOrWalk(server) ?: return@flow

            for (song in songs) {
                val suffix = song.suffix.orEmpty().lowercase()
                if (filter.formats.isNotEmpty() && suffix !in filter.formats) continue

                val durationMs = song.durationSec * 1000L
                if (filter.minDurationMs != null && durationMs < filter.minDurationMs) continue
                if (filter.maxDurationMs != null && durationMs > filter.maxDurationMs) continue

                emit(
                    DiscoveredTrack(
                        uri = client.streamUrl(server, song.id),
                        // Suffix may be blank if the server omits it;
                        // keep the bare title rather than a dangling dot.
                        fileName = if (suffix.isEmpty()) song.title else "${song.title}.$suffix",
                        // File size is unknown — Subsonic's browse
                        // responses don't carry it and we don't probe.
                        fileSize = 0L,
                        // Remote tracks have no meaningful local ctime/mtime.
                        fileCtime = 0L,
                        fileMtime = 0L,
                        format = suffix,
                    ),
                )
            }
        }

    /**
     * Serve the song list from the in-memory cache when fresh (same
     * config fingerprint, younger than [CACHE_TTL_MS]); otherwise run
     * one full walk. Holding [cacheMutex] across the walk is what makes
     * it single-flight: a second concurrent collection parks here and
     * reads the entry the first one just wrote instead of racing a
     * duplicate walk. Returns null when the top-level artists listing
     * failed (nothing to walk; not cached, so the next collection
     * retries immediately).
     */
    private suspend fun cachedOrWalk(server: SubsonicServer): List<SubsonicSong>? {
        // URL + username identify the library; the password is excluded
        // on purpose — a password edit doesn't change the catalog, and
        // streamUrl() re-signs against current credentials at emission.
        val fingerprint = "${server.baseUrl}\n${server.username}"
        return cacheMutex.withLock {
            cachedWalk?.let { cached ->
                if (cached.fingerprint == fingerprint && nowMs() - cached.cachedAtMs < CACHE_TTL_MS) {
                    return@withLock cached.songs
                }
            }
            val walk = walkLibrary(server) ?: return@withLock null
            if (!walk.partial) {
                cachedWalk =
                    CachedWalk(
                        fingerprint = fingerprint,
                        songs = walk.songs,
                        cachedAtMs = nowMs(),
                    )
            }
            walk.songs
        }
    }

    /**
     * Fan out the artists → albums → songs drill-down with at most
     * [MAX_CONCURRENT_REQUESTS] calls in flight. The per-leg [catching]
     * preserves the partial-results contract: a failed leg contributes
     * nothing (null) instead of cancelling its siblings, and the nulls
     * are folded into [WalkResult.partial] afterwards — no shared
     * mutable flag for concurrent legs to race on.
     */
    private suspend fun walkLibrary(server: SubsonicServer): WalkResult? =
        coroutineScope {
            val artists = catching { client.getArtists(server) } ?: return@coroutineScope null

            val requests = Semaphore(MAX_CONCURRENT_REQUESTS)

            // Per-artist catching: one unreachable artist's albums
            // can't abort the whole walk (partial-results contract).
            val albumLists =
                artists.map { artist ->
                    async { requests.withPermit { catching { client.getAlbumsForArtist(server, artist.id) } } }
                }.awaitAll()

            val songLists =
                albumLists.flatMap { it.orEmpty() }.map { album ->
                    async { requests.withPermit { catching { client.getSongs(server, album.id) } } }
                }.awaitAll()

            WalkResult(
                songs = songLists.flatMap { it.orEmpty() },
                partial = albumLists.any { it == null } || songLists.any { it == null },
            )
        }

    /**
     * Build a [SubsonicServer] from the persisted credentials, or null
     * when no URL is configured (nothing to query). The URL is stored
     * already-trimmed by the settings UI; we strip a trailing slash
     * defensively to match [SubsonicServer]'s "no trailing slash" contract.
     */
    private fun serverOrNull(): SubsonicServer? {
        val url = settings.getSubsonicUrl().trim().trimEnd('/')
        if (url.isBlank()) return null
        return SubsonicServer(
            baseUrl = url,
            username = settings.getSubsonicUsername(),
            password = settings.getSubsonicPassword(),
        )
    }

    private companion object {
        /**
         * Upper bound on in-flight client calls during a walk. Four is
         * deliberately modest: the typical target is a self-hosted
         * Navidrome on home hardware, not a CDN.
         */
        const val MAX_CONCURRENT_REQUESTS = 4

        /** How long one clean walk is served from memory. */
        const val CACHE_TTL_MS = 10 * 60 * 1000L

        /** Strip scheme + trailing slash to a stable host token for [displayName]. */
        fun hostOf(url: String): String =
            url.trim()
                .substringAfter("://")
                .trimEnd('/')
    }
}

/** One memoized enumeration: which config produced it, what, and when. */
private data class CachedWalk(
    val fingerprint: String,
    val songs: List<SubsonicSong>,
    val cachedAtMs: Long,
)

/** Outcome of one full walk; [partial] = some leg failed and was skipped. */
private data class WalkResult(
    val songs: List<SubsonicSong>,
    val partial: Boolean,
)

/**
 * Like [runCatching] but rethrows [CancellationException] so structured
 * concurrency is not broken inside a [flow] collector.
 */
private inline fun <T> catching(block: () -> T): T? {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }
}
