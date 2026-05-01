package com.visorcraft.radioshed.data

import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.domain.Rating
import com.visorcraft.radioshed.domain.Track
import kotlinx.coroutines.withContext
import com.visorcraft.radioshed.db.Track as TrackRow

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
        db.ratingsQueries.selectAllRatings().executeAsList().map { row ->
            Rating(trackId = row.track_id, stars = row.stars, ratedAt = row.rated_at)
        }
    }

    suspend fun topRatedTracks(minStars: Double = 4.0, limit: Long = 100): List<Track> =
        withContext(IODispatcher) {
            db.ratingsQueries.selectTopRatedTracks(minStars, limit).executeAsList().map(::toTrack)
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
        db.ratingsQueries.selectMostPlayedTracks(limit).executeAsList().map(::toTrack)
    }

    suspend fun leastPlayedTracks(limit: Long = 100): List<Track> = withContext(IODispatcher) {
        db.ratingsQueries.selectLeastPlayedTracks(limit).executeAsList().map(::toTrack)
    }

    suspend fun neverPlayedTracks(limit: Long = 100): List<Track> = withContext(IODispatcher) {
        db.ratingsQueries.selectNeverPlayedTracks(limit).executeAsList().map(::toTrack)
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

    private fun toTrack(row: TrackRow): Track = Track(
        id = row.id,
        folderId = row.folder_id,
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
    )
}

data class PlayCountStat(
    val trackId: Long,
    val count: Long,
    val lastPlayedAt: Long?,
    val totalListenMs: Long,
)
