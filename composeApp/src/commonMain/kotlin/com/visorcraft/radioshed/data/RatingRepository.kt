package com.visorcraft.radioshed.data

import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.domain.Rating
import com.visorcraft.radioshed.domain.Track
import kotlinx.coroutines.withContext

/**
 * Combines: ratings (1-5 stars), skip log, play counts, and per-station dislikes.
 * Conceptually all "judgements about a track."
 */
class RatingRepository(private val db: RadioShedDatabase) {

    // ---- Stars ----

    suspend fun setRating(trackId: Long, stars: Double, ratedAt: Long = nowMillis()) =
        withContext(IODispatcher) {
            db.ratingsQueries.setRating(trackId, stars, ratedAt)
        }

    suspend fun getRating(trackId: Long): Double? = withContext(IODispatcher) {
        db.ratingsQueries.selectRating(trackId).executeAsOneOrNull()
    }

    suspend fun clearRating(trackId: Long) = withContext(IODispatcher) {
        db.ratingsQueries.clearRating(trackId)
    }

    suspend fun listAll(): List<Rating> = withContext(IODispatcher) {
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

    suspend fun topRatedTracks(minStars: Double = 4.0, limit: Long = 100): List<Track> =
        withContext(IODispatcher) {
            db.ratingsQueries.selectTopRatedTracks(minStars, limit).executeAsList()
                .map { it.toDomain() }
        }

    /**
     * "Forgotten Gems": tracks rated ≥ [minStars] whose most-recent
     * play in `station_history` is older than [olderThanMs]. A track
     * never played at all matches via the LEFT JOIN coalesce → 0 < t.
     */
    suspend fun forgottenGemTracks(
        minStars: Double = 4.0,
        olderThanMs: Long,
        limit: Long = 100,
    ): List<Track> = withContext(IODispatcher) {
        db.ratingsQueries.selectForgottenGemTracks(minStars, olderThanMs, limit)
            .executeAsList().map { it.toDomain() }
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

    suspend fun skipCountFor(trackId: Long): Long = withContext(IODispatcher) {
        db.ratingsQueries.countSkipsForTrack(trackId).executeAsOne()
    }

    suspend fun trimSkipsOlderThan(threshold: Long) = withContext(IODispatcher) {
        db.ratingsQueries.trimSkipsOlderThan(threshold)
    }

    // ---- Play counts ----

    suspend fun recordPlay(trackId: Long, listenedMs: Long, playedAt: Long = nowMillis()) =
        withContext(IODispatcher) {
            db.ratingsQueries.incrementPlayCount(trackId, playedAt, listenedMs)
        }

    suspend fun playCountFor(trackId: Long): PlayCountStat? = withContext(IODispatcher) {
        db.ratingsQueries.selectPlayCount(trackId).executeAsOneOrNull()?.let { row ->
            PlayCountStat(
                trackId = row.track_id,
                count = row.count,
                lastPlayedAt = row.last_played_at,
                totalListenMs = row.total_listen_ms,
            )
        }
    }

    suspend fun mostPlayedTracks(limit: Long = 100): List<Track> = withContext(IODispatcher) {
        db.ratingsQueries.selectMostPlayedTracks(limit).executeAsList().map { it.toDomain() }
    }

    suspend fun leastPlayedTracks(limit: Long = 100): List<Track> = withContext(IODispatcher) {
        db.ratingsQueries.selectLeastPlayedTracks(limit).executeAsList().map { it.toDomain() }
    }

    suspend fun neverPlayedTracks(limit: Long = 100): List<Track> = withContext(IODispatcher) {
        db.ratingsQueries.selectNeverPlayedTracks(limit).executeAsList().map { it.toDomain() }
    }

    // ---- Per-station dislikes ----

    suspend fun dislikeForStation(trackId: Long, stationId: Long, dislikedAt: Long = nowMillis()) =
        withContext(IODispatcher) {
            db.ratingsQueries.dislikeForStation(trackId, stationId, dislikedAt)
        }

    suspend fun undislikeForStation(trackId: Long, stationId: Long) = withContext(IODispatcher) {
        db.ratingsQueries.undislikeForStation(trackId, stationId)
    }

    suspend fun isDislikedForStation(trackId: Long, stationId: Long): Boolean =
        withContext(IODispatcher) {
            db.ratingsQueries.isDislikedForStation(trackId, stationId).executeAsOne() > 0L
        }

    suspend fun dislikedTrackIdsForStation(stationId: Long): List<Long> =
        withContext(IODispatcher) {
            db.ratingsQueries.selectDislikedTrackIdsForStation(stationId).executeAsList()
        }
}

data class PlayCountStat(
    val trackId: Long,
    val count: Long,
    val lastPlayedAt: Long?,
    val totalListenMs: Long,
)
