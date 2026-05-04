package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.LyricsCacheRepository
import com.visorcraft.radioshed.domain.LyricLine
import com.visorcraft.radioshed.domain.Lyrics

/**
 * Cache-aware lyrics resolver. The flow:
 *  1. Look up [LyricsCacheRepository] by trackId — return immediately if hit.
 *  2. Resolve track + artist + album metadata from LibraryRepository.
 *  3. Hit [LyricsFetcher] (LRClib on Android, null stub on iOS).
 *  4. Persist whatever came back to the cache (including "instrumental"
 *     and "not found" sentinels — see the empty-source convention below)
 *     so we don't re-hit the network for the same track.
 *  5. Parse `syncedLyrics` into a [List]<[LyricLine]> at decode time, so
 *     the UI gets a ready-to-render structure.
 *
 * "Not found" is encoded in the cache as `(synced=null, plain=null,
 * instrumental=false, source="lrclib:miss")`. This avoids unbounded
 * re-fetching of tracks that simply aren't on LRClib. Users can force
 * a re-fetch via the manual button in lyrics view (deletes cache → calls
 * [resolve] again).
 */
class LyricsService(
    private val libraryRepo: LibraryRepository,
    private val cache: LyricsCacheRepository,
    private val fetcher: LyricsFetcher,
    private val localLoader: LocalLyricsLoader,
    private val networkGate: NetworkPolicyGate,
    private val settings: com.visorcraft.radioshed.data.SettingsStore,
) {

    suspend fun resolve(trackId: Long, forceRefresh: Boolean = false): Lyrics? {
        if (!forceRefresh) {
            cache.get(trackId)?.let { entry ->
                // Negative-cache TTL: a "not found" hit on LRClib that's
                // older than MISS_TTL_MS gets a re-fetch — LRClib adds
                // tracks over time and we'd never surface those without
                // the user manually force-refreshing. Hit cache for
                // positive entries (lyrics, instrumental flag, local
                // sidecar) is permanent because the file content
                // doesn't shift over time the way the LRClib database
                // does.
                val isMiss = entry.source == MISS_SENTINEL
                val ageMs = com.visorcraft.radioshed.data.nowMillis() - entry.fetchedAt
                if (isMiss && ageMs > MISS_TTL_MS) {
                    cache.delete(trackId)
                } else {
                    return entry.toLyrics(trackId)
                }
            }
        }

        val track = libraryRepo.getTrackById(trackId) ?: return null

        // Local sidecar (.lrc / .txt) wins over the network fetcher.
        // User-curated files are typically more accurate than what's
        // on LRClib — and they work offline. Cached with source="local"
        // so subsequent loads short-circuit through the cache.
        val local = localLoader.load(track.path)
        if (local != null) {
            cache.put(
                trackId = trackId,
                syncedLrc = local.synced,
                plainText = local.plain,
                source = LOCAL_SOURCE,
            )
            return Lyrics(
                trackId = trackId,
                synced = local.synced?.let { parseLrc(it) },
                plain = local.plain,
                instrumental = false,
                source = LOCAL_SOURCE,
            )
        }

        val taggedArtist = libraryRepo.artistsForTrack(trackId)
            .firstOrNull { it.name.isNotBlank() }?.name
        val taggedTitle = track.title.takeIf { it.isNotBlank() }

        val albumTitle = track.albumId?.let { libraryRepo.getAlbumById(it)?.title }
        val durationSec = (track.durationMs / 1000L).takeIf { it > 0 }

        // User-supplied override wins over everything else. Set when
        // the auto-fallback chain misses and the user manually
        // corrects the search query from the Lyrics not-found
        // screen. Stored per-track and persisted across app launches.
        val override = cache.getOverride(trackId)

        // Try multiple (artist, title) candidate pairs. LRClib is strict
        // about exact matches, so a single attempt with messy tags
        // misses popular tracks whose stored title is "Song (Remastered
        // 2008)" or whose artist is "Roberta Flack feat. Donny Hathaway".
        // The candidate generator strips parentheticals, drops
        // featured-artist suffixes, and falls back to filename parsing
        // when the metadata is missing entirely. We stop on the first
        // hit to keep network use bounded.
        val baseCandidates = lyricsQueryCandidates(
            taggedArtist = taggedArtist,
            taggedTitle = taggedTitle,
            fileName = track.fileName,
        )
        val candidates = if (override != null) {
            listOf(LyricsQuery(override.first, override.second)) + baseCandidates
        } else {
            baseCandidates
        }

        // Honour the user's connectivity policy. forceRefresh is the
        // manual retry path (user pressed "search again" in Lyrics
        // view) so it counts as user-initiated; the silent-on-play
        // path is automatic. Manual policy permits user-initiated
        // requests but blocks automatic ones; Never blocks both;
        // WiFiOnly blocks both unless on an unmetered transport.
        // Auto-fetch toggle: when off, only manual force-refresh
        // proceeds to the network. The local + embedded passes above
        // already ran, so the user still gets sidecar lyrics offline;
        // we just don't silently dial out for tracks the local files
        // don't cover.
        val autoFetchAllowed = forceRefresh || settings.getAutoFetchLyrics()
        val networkAllowed = autoFetchAllowed && if (forceRefresh) {
            networkGate.allowUserInitiatedFetch()
        } else {
            networkGate.allowAutomaticFetch()
        }
        if (!networkAllowed) {
            // Don't poison the cache with a miss — the user might be
            // offline temporarily, or might flip the policy back to
            // Always later. Returning null lets the UI show the
            // not-found state without committing a 30-day TTL miss.
            return null
        }

        var raw: RawLyricsResponse? = null
        // Track whether at least one candidate hit a transient error
        // (rate-limit, 5xx, timeout). If so, we DON'T cache the miss
        // — the user would otherwise be stuck with "not found" for
        // 30 days because of a network blip.
        var sawTransientError = false
        for (candidate in candidates) {
            raw = try {
                fetcher.fetch(
                    artist = candidate.artist,
                    title = candidate.title,
                    album = albumTitle,
                    durationSeconds = durationSec,
                )
            } catch (e: TransientLyricsFetchException) {
                sawTransientError = true
                null
            }
            if (raw != null) break
        }

        if (raw == null) {
            if (sawTransientError) {
                // Don't poison the cache with a miss when the network
                // was the actual problem. Next resolve attempts the
                // fetch fresh.
                return null
            }
            // Cache the miss so we don't keep hitting the network.
            cache.put(
                trackId = trackId,
                syncedLrc = null,
                plainText = null,
                source = MISS_SENTINEL,
            )
            return null
        }

        cache.put(
            trackId = trackId,
            syncedLrc = raw.syncedLrc,
            plainText = raw.plainLyrics,
            source = if (raw.instrumental) INSTRUMENTAL_SENTINEL else raw.source,
        )

        return Lyrics(
            trackId = trackId,
            synced = raw.syncedLrc?.let { parseLrc(it) },
            plain = raw.plainLyrics,
            instrumental = raw.instrumental,
            source = raw.source,
        )
    }

    suspend fun forget(trackId: Long) {
        cache.delete(trackId)
    }

    private fun com.visorcraft.radioshed.data.LyricsCacheEntry.toLyrics(trackId: Long): Lyrics? {
        // Miss sentinel: we tried and the provider didn't have it.
        if (source == MISS_SENTINEL) return null
        val instrumental = source == INSTRUMENTAL_SENTINEL
        return Lyrics(
            trackId = trackId,
            synced = syncedLrc?.let { parseLrc(it) },
            plain = plainText,
            instrumental = instrumental,
            source = source,
        )
    }

    /**
     * Parse standard LRC `[mm:ss.xx] line` AND extended `[h:mm:ss.xx]`
     * (hour-prefixed) syntax used for tracks longer than 100 minutes.
     * Multi-timestamp lines (`[00:01][00:30] line`) are expanded into
     * one [LyricLine] per timestamp. Bracketed metadata tags
     * (`[ti:...]`, `[ar:...]`, `[length:03:24]`) with non-numeric
     * content are ignored — the prefix is non-digit, so the regex's
     * `\d{1,2}` anchor doesn't match. Empty lines are kept (some
     * songs use them as visual breath markers).
     */
    private fun parseLrc(lrc: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        // Three-part-or-two-part timestamp. The optional inner group
        // catches an hour prefix when present (`[01:23:45]` = 1h23m45s).
        // Without the alternation, hour-format tracks (long DJ sets,
        // mixes, podcasts) would silently parse as mm:ss losing the
        // hour entirely.
        val timestampRegex = Regex("""\[(?:(\d{1,2}):)?(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?]""")
        for (rawLine in lrc.split('\n')) {
            val line = rawLine.trimEnd('\r')
            val matches = timestampRegex.findAll(line).toList()
            if (matches.isEmpty()) continue
            val text = line.substring(matches.last().range.last + 1).trim()
            for (m in matches) {
                val hours = m.groupValues[1].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L
                val mins = m.groupValues[2].toLongOrNull() ?: continue
                val secs = m.groupValues[3].toLongOrNull() ?: continue
                val frac = m.groupValues[4]
                val fracMs = when (frac.length) {
                    1 -> frac.toLong() * 100L
                    2 -> frac.toLong() * 10L
                    3 -> frac.toLong()
                    else -> 0L
                }
                val timeMs = hours * 3_600_000L + mins * 60_000L + secs * 1_000L + fracMs
                result += LyricLine(timeMs = timeMs, text = text)
            }
        }
        return result.sortedBy { it.timeMs }
    }

    private companion object {
        const val MISS_SENTINEL = "lrclib:miss"
        const val INSTRUMENTAL_SENTINEL = "lrclib:instrumental"
        const val LOCAL_SOURCE = "local"

        /**
         * How long a "not found" cache entry stays valid before we'll
         * re-hit LRClib. 30 days strikes a balance: long enough that
         * the network isn't pinged on every play of an obscure track
         * we already established isn't there; short enough that a
         * track LRClib added in the meantime surfaces eventually
         * without user intervention.
         */
        const val MISS_TTL_MS: Long = 30L * 24L * 60L * 60L * 1_000L
    }
}
