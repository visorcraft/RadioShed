package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Sweeps every album that has a MusicBrainz Release ID but no artwork,
 * fetches the front cover from Cover Art Archive, persists the bytes
 * via [ArtworkPersister] (content-addressed, dedup'd across albums),
 * and updates `album.artwork_uri`.
 *
 * Emits [Progress] events as a cold flow; the UI in AppSettings collects
 * and renders these. Cancelling the collecting coroutine cancels the
 * sweep cleanly — each album is independent so we never leave the DB in
 * a half-updated state.
 *
 * What this does NOT do (deferred to a v2 pass):
 *  - MusicBrainz fuzzy search for albums without an embedded MBID
 *    (would require Ktor + JSON parsing of MB's API + rate-limiting).
 *  - Cache the "no art available" state to avoid re-asking on every run
 *    — for v1, an album with no CAA art will get re-tried on next sync;
 *    the cost is one HTTP HEAD-equivalent per sweep.
 */
class MissingArtworkSync(
    private val libraryRepo: LibraryRepository,
    private val coverArtFetcher: CoverArtFetcher,
    private val artworkPersister: ArtworkPersister,
    private val networkGate: NetworkPolicyGate,
) {

    sealed interface Progress {
        data class Started(val total: Int) : Progress
        data class Fetching(val albumId: Long, val index: Int, val total: Int) : Progress
        data class Found(val albumId: Long) : Progress
        data class Missing(val albumId: Long) : Progress
        data class Failed(val albumId: Long, val reason: String) : Progress
        data class Completed(val found: Int, val missing: Int, val failed: Int) : Progress
        /**
         * The connectivity policy doesn't permit a sweep right now
         * (Never, Manual, or WiFi-only on a metered network). The
         * UI surfaces this distinct from a normal Completed so the
         * "0 found, 0 missing" outcome isn't confused with success.
         */
        data object PolicyBlocked : Progress
    }

    private companion object {
        /** Per-request delay during a sweep — be polite to CAA. */
        const val REQUEST_DELAY_MS = 200L
    }

    fun sweep(): Flow<Progress> = flow {
        // Honour the user's connectivity policy before kicking off a
        // sweep. The user explicitly tapped "Sync missing artwork",
        // so this counts as user-initiated — Manual policy permits
        // it; Never and WiFi-only-on-metered still block.
        if (!networkGate.allowUserInitiatedFetch()) {
            emit(Progress.PolicyBlocked)
            return@flow
        }
        val targets = libraryRepo.albumsMissingArtwork()
        emit(Progress.Started(total = targets.size))
        if (targets.isEmpty()) {
            emit(Progress.Completed(found = 0, missing = 0, failed = 0))
            return@flow
        }

        var found = 0
        var missing = 0
        var failed = 0
        targets.forEachIndexed { index, (albumId, mbid) ->
            // Light rate-limit between requests. Cover Art Archive
            // doesn't publish a hard rate limit, but bursting hundreds
            // of GETs in a few seconds is impolite and risks 429s
            // (which `runCatching` would surface as `failed` —
            // misleading the user when the real cause is throttle).
            // 200ms gap = max ~5 req/s, comfortable headroom.
            if (index > 0) kotlinx.coroutines.delay(REQUEST_DELAY_MS)
            emit(Progress.Fetching(albumId = albumId, index = index, total = targets.size))
            val outcome = runCatching { coverArtFetcher.fetchByReleaseMbid(mbid) }
            val bytes = outcome.getOrNull()
            when {
                outcome.isFailure -> {
                    failed += 1
                    emit(Progress.Failed(albumId = albumId, reason = outcome.exceptionOrNull()?.message ?: "error"))
                }
                bytes == null -> {
                    missing += 1
                    emit(Progress.Missing(albumId = albumId))
                }
                else -> {
                    val uri = artworkPersister.persist(bytes)
                    if (uri == null) {
                        failed += 1
                        emit(Progress.Failed(albumId = albumId, reason = "persist failed"))
                    } else {
                        libraryRepo.setAlbumArtwork(albumId, uri)
                        found += 1
                        emit(Progress.Found(albumId = albumId))
                    }
                }
            }
        }

        emit(Progress.Completed(found = found, missing = missing, failed = failed))
    }
}
