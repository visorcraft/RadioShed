package com.visorcraft.radioshed.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.domain.PlayCountStats
import com.visorcraft.radioshed.domain.Rating
import com.visorcraft.radioshed.domain.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Combines: ratings (1-5 stars), skip log, play counts, and per-station dislikes.
 * Conceptually all "judgements about a track."
 */
class RatingRepository(private val db: RadioShedDatabase) {
    // ---- Stars ----

    suspend fun setRating(
        trackId: Long,
        stars: Double,
        ratedAt: Long = nowMillis(),
    ) = withContext(IODispatcher) {
        db.ratingsQueries.setRating(trackId, stars, ratedAt)
    }

    suspend fun getRating(trackId: Long): Double? =
        withContext(IODispatcher) {
            db.ratingsQueries.selectRating(trackId).executeAsOneOrNull()
        }

    suspend fun clearRating(trackId: Long) =
        withContext(IODispatcher) {
            db.ratingsQueries.clearRating(trackId)
        }

    suspend fun listAll(): List<Rating> =
        withContext(IODispatcher) {
            db.ratingsQueries.selectAllRatings().executeAsList().map { it.toDomain() }
        }

    /**
     * Batched (trackId → stars). Returns absent ids as missing-from-
     * map; callers default to 0.0 for "no rating" weighting.
     */
    suspend fun ratingsForTracks(trackIds: Collection<Long>): Map<Long, Double> =
        withContext(IODispatcher) {
            if (trackIds.isEmpty()) return@withContext emptyMap()
            db.ratingsQueries.selectRatingsForTracks(trackIds).executeAsList()
                .associate { it.track_id to it.stars }
        }

    suspend fun topRatedTracks(
        minStars: Double = 4.0,
        limit: Long = 100,
    ): List<Track> =
        withContext(IODispatcher) {
            db.ratingsQueries.selectTopRatedTracks(minStars, limit).executeAsList()
                .map { it.toDomain() }
        }

    /**
     * "Forgotten Gems": tracks rated ≥ [minStars] whose most-recent
     * play in `station_history` is older than [olderThanMs]. A track
     * never played at all matches via the LEFT JOIN coalesce → 0 < t.
     *
     * [byRating] flips the sort: false (default) = longest-unheard
     * first; true = highest-rated first within the gem set.
     */
    suspend fun forgottenGemTracks(
        minStars: Double = 4.0,
        olderThanMs: Long,
        limit: Long = 100,
        byRating: Boolean = false,
    ): List<Track> =
        withContext(IODispatcher) {
            val rows =
                if (byRating) {
                    db.ratingsQueries.selectForgottenGemTracksByRating(minStars, olderThanMs, limit)
                        .executeAsList()
                } else {
                    db.ratingsQueries.selectForgottenGemTracks(minStars, olderThanMs, limit)
                        .executeAsList()
                }
            rows.map { it.toDomain() }
        }

    // ---- Skip log ----

    suspend fun recordSkip(
        trackId: Long,
        stationId: Long?,
        positionMs: Long,
        skippedAt: Long = nowMillis(),
    ) = withContext(IODispatcher) {
        db.ratingsQueries.insertSkip(trackId, stationId, skippedAt, positionMs)
    }

    suspend fun skipCountFor(trackId: Long): Long =
        withContext(IODispatcher) {
            db.ratingsQueries.countSkipsForTrack(trackId).executeAsOne()
        }

    suspend fun trimSkipsOlderThan(threshold: Long) =
        withContext(IODispatcher) {
            db.ratingsQueries.trimSkipsOlderThan(threshold)
        }

    /**
     * Top-N tracks by total skip count across all stations. Pairs the
     * track row with its skip count so the UI can render "skipped 12
     * times" alongside the title without a second query per row.
     */
    suspend fun mostSkippedTracks(limit: Long = 25): List<Pair<Track, Long>> =
        withContext(IODispatcher) {
            db.ratingsQueries.selectMostSkippedTracks(limit).executeAsList().map { row ->
                Track(
                    id = row.id,
                    path = row.path,
                    fileName = row.file_name,
                    fileSize = row.file_size,
                    fileCtime = row.file_ctime,
                    fileMtime = row.file_mtime,
                    title = row.title,
                    albumId = row.album_id,
                    trackNumber = row.track_number?.toInt(),
                    discNumber = row.disc_number?.toInt(),
                    durationMs = row.duration_ms,
                    bitrateKbps = row.bitrate_kbps?.toInt(),
                    sampleRateHz = row.sample_rate_hz?.toInt(),
                    channels = row.channels?.toInt(),
                    format = row.format,
                    replayGainTrack = row.replay_gain_track,
                    replayGainAlbum = row.replay_gain_album,
                    replayGainTrackPeak = row.replay_gain_track_peak,
                    bpm = row.bpm,
                    musicbrainzId = row.musicbrainz_id,
                    acoustidFingerprint = row.acoustid_fingerprint,
                    cueOffsetStartMs = row.cue_offset_start_ms,
                    cueOffsetEndMs = row.cue_offset_end_ms,
                    isAvailable = row.is_available == 1L,
                    addedAt = row.added_at,
                    updatedAt = row.updated_at,
                ) to row.skip_count
            }
        }

    // ---- Play counts ----

    suspend fun recordPlay(
        trackId: Long,
        listenedMs: Long,
        playedAt: Long = nowMillis(),
    ) = withContext(IODispatcher) {
        db.ratingsQueries.incrementPlayCount(trackId, playedAt, listenedMs)
    }

    suspend fun playCountFor(trackId: Long): PlayCountStat? =
        withContext(IODispatcher) {
            db.ratingsQueries.selectPlayCount(trackId).executeAsOneOrNull()?.let { row ->
                PlayCountStat(
                    trackId = row.track_id,
                    count = row.count,
                    lastPlayedAt = row.last_played_at,
                    totalListenMs = row.total_listen_ms,
                )
            }
        }

    suspend fun mostPlayedTracks(limit: Long = 100): List<Track> =
        withContext(IODispatcher) {
            db.ratingsQueries.selectMostPlayedTracks(limit).executeAsList().map { it.toDomain() }
        }

    suspend fun leastPlayedTracks(limit: Long = 100): List<Track> =
        withContext(IODispatcher) {
            db.ratingsQueries.selectLeastPlayedTracks(limit).executeAsList().map { it.toDomain() }
        }

    /**
     * Batch fetch `(track_id → (count, last_played_at))` for a
     * candidate set. Tracks without a `play_count` row are absent
     * from the map (caller treats absence as zero / never).
     * Used by the AdaptiveMix smart-query scorer.
     */
    suspend fun playCountsForTracks(trackIds: Collection<Long>): Map<Long, PlayCountStats> =
        withContext(IODispatcher) {
            if (trackIds.isEmpty()) return@withContext emptyMap()
            db.ratingsQueries.selectPlayCountsForTracks(trackIds).executeAsList()
                .associate { it.track_id to PlayCountStats(it.count, it.last_played_at) }
        }

    suspend fun neverPlayedTracks(limit: Long = 100): List<Track> =
        withContext(IODispatcher) {
            db.ratingsQueries.selectNeverPlayedTracks(limit).executeAsList().map { it.toDomain() }
        }

    // ---- Liked songs ----

    suspend fun like(
        trackId: Long,
        likedAt: Long = nowMillis(),
    ) = withContext(IODispatcher) {
        db.ratingsQueries.likeTrack(trackId, likedAt)
    }

    suspend fun unlike(trackId: Long) =
        withContext(IODispatcher) {
            db.ratingsQueries.unlikeTrack(trackId)
        }

    suspend fun isLiked(trackId: Long): Boolean =
        withContext(IODispatcher) {
            db.ratingsQueries.isTrackLiked(trackId).executeAsOne() > 0L
        }

    suspend fun likedTracks(): List<Track> =
        withContext(IODispatcher) {
            db.ratingsQueries.selectLikedTracks().executeAsList().map { it.toDomain() }
        }

    suspend fun likedTrackIds(): List<Long> =
        withContext(IODispatcher) {
            db.ratingsQueries.selectLikedTrackIds().executeAsList()
        }

    /** (trackId → liked_at) so backups round-trip the original
     *  timestamp instead of overwriting with the export time. */
    suspend fun likedTracksWithTimestamps(): List<Pair<Long, Long>> =
        withContext(IODispatcher) {
            db.ratingsQueries.selectLikedTracksWithTimestamps().executeAsList()
                .map { it.track_id to it.liked_at }
        }

    suspend fun likedCount(): Long =
        withContext(IODispatcher) {
            db.ratingsQueries.countLikedTracks().executeAsOne()
        }

    /** Live count for the Liked Songs entry on the Playlists screen. */
    fun observeLikedCount(): Flow<Long> = db.ratingsQueries.observeLikedCount().asFlow().mapToOne(IODispatcher)

    // ---- Per-station dislikes ----

    suspend fun dislikeForStation(
        trackId: Long,
        stationId: Long,
        dislikedAt: Long = nowMillis(),
    ) = withContext(IODispatcher) {
        db.ratingsQueries.dislikeForStation(trackId, stationId, dislikedAt)
    }

    suspend fun undislikeForStation(
        trackId: Long,
        stationId: Long,
    ) = withContext(IODispatcher) {
        db.ratingsQueries.undislikeForStation(trackId, stationId)
    }

    suspend fun isDislikedForStation(
        trackId: Long,
        stationId: Long,
    ): Boolean =
        withContext(IODispatcher) {
            db.ratingsQueries.isDislikedForStation(trackId, stationId).executeAsOne() > 0L
        }

    suspend fun dislikedTrackIdsForStation(stationId: Long): List<Long> =
        withContext(IODispatcher) {
            db.ratingsQueries.selectDislikedTrackIdsForStation(stationId).executeAsList()
        }

    // ---- Global dislike ----

    /**
     * Mark this track as never-play-anywhere. Filtered out of every
     * station's candidate pool by SourceRuleEvaluator before per-
     * station dislikes are applied. Idempotent — re-banning just
     * refreshes the timestamp.
     */
    suspend fun globalDislike(
        trackId: Long,
        dislikedAt: Long = nowMillis(),
    ) = withContext(IODispatcher) {
        db.ratingsQueries.globalDislikeTrack(trackId, dislikedAt)
    }

    suspend fun undoGlobalDislike(trackId: Long) =
        withContext(IODispatcher) {
            db.ratingsQueries.undoGlobalDislike(trackId)
        }

    suspend fun isGloballyDisliked(trackId: Long): Boolean =
        withContext(IODispatcher) {
            db.ratingsQueries.isGloballyDisliked(trackId).executeAsOne() > 0L
        }

    /**
     * Full set of globally-disliked track ids. Loaded once per
     * station-load (hot path on the SourceRule evaluator) so we
     * filter the candidate pool at construction time rather than
     * checking each candidate individually.
     */
    suspend fun globallyDislikedIds(): Set<Long> =
        withContext(IODispatcher) {
            db.ratingsQueries.selectGloballyDislikedIds().executeAsList().toSet()
        }
}

data class PlayCountStat(
    val trackId: Long,
    val count: Long,
    val lastPlayedAt: Long?,
    val totalListenMs: Long,
)
