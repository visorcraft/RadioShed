package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.LyricsCacheRepository
import com.visorcraft.radioshed.data.nowMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Mirror of [MissingArtworkSync] for lyrics. Sweeps every available
 * track that doesn't have a positive lyrics cache entry yet, runs it
 * through [LyricsService.resolve], and lets the service handle local
 * sidecar files / LRClib network fetches / cache writes internally.
 *
 * "Missing" means EITHER no cache row at all, OR a cached
 * `lrclib:miss` sentinel that's older than the negative-cache TTL
 * (30 days). Tracks recently confirmed not-on-LRClib are skipped so
 * the sweep doesn't re-poll for things we already know aren't there.
 *
 * Emits [Progress] events as a cold flow; the AppSettings UI
 * collects and renders them. Cancelling the collecting coroutine
 * cancels the sweep cleanly — each track is independent.
 */
class MissingLyricsSync(
    private val lyricsCache: LyricsCacheRepository,
    private val lyricsService: LyricsService,
    private val networkGate: NetworkPolicyGate,
) {
    sealed interface Progress {
        data class Started(val total: Int) : Progress

        data class Fetching(val trackId: Long, val index: Int, val total: Int) : Progress

        data class Found(val trackId: Long) : Progress

        data class Missing(val trackId: Long) : Progress

        data class Failed(val trackId: Long, val reason: String) : Progress

        data class Completed(val found: Int, val missing: Int, val failed: Int) : Progress

        /**
         * The connectivity policy doesn't permit a sweep right now
         * (Never, or WiFi-only on a metered network). Mirrors the
         * artwork-sweep behaviour so the UI's "0 found, 0 missing"
         * outcome isn't confused with successful completion.
         */
        data object PolicyBlocked : Progress
    }

    private companion object {
        /** Per-request delay during a sweep — be polite to LRClib. */
        const val REQUEST_DELAY_MS = 200L

        /**
         * Negative-cache TTL — must match [LyricsService.MISS_TTL_MS]
         * so the sweep's "missing" definition lines up with what the
         * service treats as a stale miss.
         */
        const val MISS_TTL_MS: Long = 30L * 24L * 60L * 60L * 1_000L
    }

    fun sweep(): Flow<Progress> =
        flow {
            // Honour connectivity policy. The user explicitly tapped
            // "Start sweep", so this counts as user-initiated — Manual
            // policy permits it; Never and WiFi-only-on-metered still
            // block. Same gate as the artwork sweep, with a per-service
            // Lyrics override consulted before the global policy.
            if (!networkGate.allowUserInitiatedFetch(NetworkService.Lyrics)) {
                emit(Progress.PolicyBlocked)
                return@flow
            }
            val threshold = nowMillis() - MISS_TTL_MS
            val targets = lyricsCache.trackIdsMissingLyrics(threshold)
            emit(Progress.Started(total = targets.size))
            if (targets.isEmpty()) {
                emit(Progress.Completed(found = 0, missing = 0, failed = 0))
                return@flow
            }

            var found = 0
            var missing = 0
            var failed = 0
            targets.forEachIndexed { index, trackId ->
                // Same 200ms gap as the artwork sweep — LRClib doesn't
                // publish a hard rate limit but bursting hundreds of
                // GETs in a few seconds invites 429s.
                if (index > 0) kotlinx.coroutines.delay(REQUEST_DELAY_MS)
                emit(Progress.Fetching(trackId = trackId, index = index, total = targets.size))
                val outcome = runCatching { lyricsService.resolve(trackId) }
                when {
                    outcome.isFailure -> {
                        failed += 1
                        emit(
                            Progress.Failed(
                                trackId = trackId,
                                reason = outcome.exceptionOrNull()?.message ?: "error",
                            ),
                        )
                    }
                    outcome.getOrNull() == null -> {
                        // Either no metadata to query with, or LRClib
                        // returned a clean miss. The service has already
                        // stamped the cache appropriately.
                        missing += 1
                        emit(Progress.Missing(trackId = trackId))
                    }
                    else -> {
                        found += 1
                        emit(Progress.Found(trackId = trackId))
                    }
                }
            }

            emit(Progress.Completed(found = found, missing = missing, failed = failed))
        }
}
