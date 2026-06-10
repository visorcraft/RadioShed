package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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
 * `NetworkPolicyGate.allowAutomaticFetch()` in `sharedModule`.
 *
 * Server credentials are read live from [SettingsStore] on every
 * [listTracks] collection so an instance bound once at startup always
 * reflects the user's current configuration (no stale snapshot).
 *
 * Error contract: enumeration is best-effort. A failure fetching one
 * artist's albums, or one album's songs, is swallowed (`runCatching`)
 * so the rest of the library still surfaces — a single unreachable
 * album never aborts the whole walk. The flow therefore COMPLETES
 * normally with partial results rather than throwing. A failure listing
 * the top-level artists yields an empty flow (nothing to walk).
 */
class SubsonicSource(
    private val client: SubsonicClient,
    private val settings: SettingsStore,
    private val allowNetwork: () -> Boolean,
) : MediaSource {

    /**
     * Fixed id so the registry key is stable across credential edits.
     * The [displayName] still reflects the current host for UI labels.
     */
    override val id: String = "subsonic"

    override val displayName: String get() = "Subsonic (${hostOf(settings.getSubsonicUrl())})"

    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsStreaming = true,
        supportsArtwork = true,
    )

    /**
     * Cold flow. Collection drives the walk; nothing is held beyond the
     * consumer's window. The gate is checked first — a denied policy
     * finishes the flow immediately with zero client calls. A blank
     * server URL likewise yields nothing (no server to query).
     *
     * Filtering: [SourceFilter.formats] is matched against the song's
     * lowercased suffix; the duration window
     * ([SourceFilter.minDurationMs] / [maxDurationMs]) is evaluated from
     * [SubsonicSong.durationSec] (× 1000) DURING enumeration, because
     * [DiscoveredTrack] carries no duration field — once a track is
     * surfaced its duration is gone, so the filter must run here.
     */
    override fun listTracks(filter: SourceFilter): Flow<DiscoveredTrack> = flow {
        if (!allowNetwork()) return@flow

        val server = serverOrNull() ?: return@flow

        // A failure listing artists leaves nothing to walk — empty flow.
        val artists = catching { client.getArtists(server) } ?: return@flow

        for (artist in artists) {
            // Per-artist catching: one unreachable artist's albums
            // can't abort the whole walk (partial-results contract).
            val albums = catching { client.getAlbumsForArtist(server, artist.id) } ?: continue
            for (album in albums) {
                val songs = catching { client.getSongs(server, album.id) } ?: continue
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
        }
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
        /** Strip scheme + trailing slash to a stable host token for [displayName]. */
        fun hostOf(url: String): String =
            url.trim()
                .substringAfter("://")
                .trimEnd('/')
    }
}

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
