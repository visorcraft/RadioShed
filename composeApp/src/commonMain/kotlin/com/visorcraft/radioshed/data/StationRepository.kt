package com.visorcraft.radioshed.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.domain.PlaybackRule
import com.visorcraft.radioshed.domain.SourceRule
import com.visorcraft.radioshed.domain.Station
import com.visorcraft.radioshed.domain.StationStats
import com.visorcraft.radioshed.domain.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.visorcraft.radioshed.db.Station as StationRow
import com.visorcraft.radioshed.db.Station_history as StationHistoryRow

class StationRepository(private val db: RadioShedDatabase) {

    fun observeAll(): Flow<List<Station>> =
        db.stationsQueries.selectAllStations()
            .asFlow()
            .mapToList(IODispatcher)
            .map { rows -> rows.map(::toStation) }

    suspend fun list(): List<Station> = withContext(IODispatcher) {
        db.stationsQueries.selectAllStations().executeAsList().map(::toStation)
    }

    suspend fun getById(id: Long): Station? = withContext(IODispatcher) {
        db.stationsQueries.selectStationById(id).executeAsOneOrNull()?.let(::toStation)
    }

    suspend fun listPinned(): List<Station> = withContext(IODispatcher) {
        db.stationsQueries.selectPinnedStations().executeAsList().map(::toStation)
    }

    suspend fun listRecentlyPlayed(limit: Long): List<Station> = withContext(IODispatcher) {
        db.stationsQueries.selectRecentlyPlayedStations(limit).executeAsList().map { row ->
            // SelectRecentlyPlayedStations is a generated projection; rebuild manually.
            Station(
                id = row.id,
                name = row.name,
                color = row.color.toInt(),
                icon = row.icon,
                artworkUri = row.artwork_uri,
                sortPosition = row.sort_position.toInt(),
                customSubtitle = row.custom_subtitle,
                source = decodeSource(row.source_json),
                playback = decodePlayback(row.playback_json),
                stats = StationStats(
                    playCount = row.play_count,
                    lastPlayedAt = row.last_played_at,
                    totalListenMs = row.total_listen_ms,
                    skipRate = row.skip_rate,
                ),
                isPinned = row.is_pinned == 1L,
                isBuiltIn = row.is_built_in == 1L,
                createdAt = row.created_at,
                updatedAt = row.updated_at,
            )
        }
    }

    suspend fun count(): Long = withContext(IODispatcher) {
        db.stationsQueries.countStations().executeAsOne()
    }

    suspend fun insert(
        name: String,
        source: SourceRule,
        playback: PlaybackRule = PlaybackRule(),
        color: Int = 0,
        icon: String? = null,
        artworkUri: String? = null,
        sortPosition: Int = 0,
        customSubtitle: String? = null,
        isBuiltIn: Boolean = false,
    ): Long = withContext(IODispatcher) {
        val now = nowMillis()
        db.stationsQueries.insertStation(
            name = name,
            color = color.toLong(),
            icon = icon,
            artwork_uri = artworkUri,
            sort_position = sortPosition.toLong(),
            custom_subtitle = customSubtitle,
            source_json = encodeSource(source),
            playback_json = encodePlayback(playback),
            is_built_in = if (isBuiltIn) 1L else 0L,
            created_at = now,
            updated_at = now,
        ).executeAsOne()
    }

    suspend fun update(
        id: Long,
        name: String,
        source: SourceRule,
        playback: PlaybackRule,
        color: Int,
        icon: String?,
        artworkUri: String?,
        customSubtitle: String?,
    ) = withContext(IODispatcher) {
        db.stationsQueries.updateStation(
            name = name,
            color = color.toLong(),
            icon = icon,
            artwork_uri = artworkUri,
            custom_subtitle = customSubtitle,
            source_json = encodeSource(source),
            playback_json = encodePlayback(playback),
            updated_at = nowMillis(),
            id = id,
        )
    }

    suspend fun updateStats(id: Long, stats: StationStats) = withContext(IODispatcher) {
        db.stationsQueries.updateStationStats(
            play_count = stats.playCount,
            last_played_at = stats.lastPlayedAt,
            total_listen_ms = stats.totalListenMs,
            skip_rate = stats.skipRate,
            updated_at = nowMillis(),
            id = id,
        )
    }

    /** Atomic +1 on play_count + last_played_at. Race-safe vs concurrent starts. */
    suspend fun bumpStationPlayCount(id: Long, playedAt: Long = nowMillis()) =
        withContext(IODispatcher) {
            db.stationsQueries.bumpStationPlayCount(playedAt, nowMillis(), id)
        }

    /** Add [deltaMs] to total_listen_ms atomically. */
    suspend fun bumpStationListenedMs(id: Long, deltaMs: Long) = withContext(IODispatcher) {
        if (deltaMs <= 0L) return@withContext
        db.stationsQueries.bumpStationListenedMs(deltaMs, nowMillis(), id)
    }

    /**
     * Wipe denormalized per-station play counters. Paired with
     * [clearAllHistory] so a "Clear history" action resets both the
     * full history table and the cached counters that the Stats
     * screen reads from `station.play_count`.
     */
    suspend fun resetAllStationStats() = withContext(IODispatcher) {
        db.stationsQueries.resetAllStationStats(nowMillis())
    }

    /**
     * Batched (trackId → playCount). Returns 0 for tracks that have
     * never appeared in `station_history` (i.e. ids absent from the
     * map should be treated as 0 by callers).
     */
    suspend fun playCountForTracks(trackIds: Collection<Long>): Map<Long, Long> =
        withContext(IODispatcher) {
            if (trackIds.isEmpty()) return@withContext emptyMap()
            db.stationsQueries.selectPlayCountForTracks(trackIds).executeAsList()
                .associate { it.track_id to it.plays }
        }

    /**
     * Map of (stationId → (plays, skips)) computed from
     * `station_history`. Used by Stations Home tiles to render
     * "X plays · Y% skip". Stations with no history are absent —
     * callers treat absence as 0 plays.
     */
    suspend fun stationCompletionRates(): Map<Long, Pair<Long, Long>> =
        withContext(IODispatcher) {
            db.stationsQueries.selectStationCompletionRates().executeAsList()
                .associate { row ->
                    // SUM over zero-row groups can't fire because GROUP
                    // BY only emits stations with ≥1 history row, but
                    // SQLDelight types skips as Long? regardless.
                    row.station_id to (row.plays to (row.skips ?: 0L))
                }
        }

    /**
     * Aggregate play stats for a single track. Returns zero counts
     * when the track has no history rows yet (every field except
     * trackId is non-null thanks to the SUM/IFNULL coalesces).
     */
    suspend fun historyStatsForTrack(trackId: Long): TrackHistoryStats =
        withContext(IODispatcher) {
            val row = db.stationsQueries.selectTrackHistoryStats(trackId).executeAsOne()
            // SUM(...) over zero rows returns NULL in SQLite; the
            // CASE WHEN inside our SUM means the inner expression is
            // never NULL but the outer sum still is. SQLDelight types
            // these as Long? so we coerce below. (The lints flag
            // `row.completes ?: 0L` as always-non-null when the
            // generator produces a non-null Long; suppressing.)
            @Suppress("USELESS_ELVIS")
            TrackHistoryStats(
                plays = row.plays,
                completes = (row.completes ?: 0L),
                skips = (row.skips ?: 0L),
                listenedMs = (row.listened_ms ?: 0L),
            )
        }

    suspend fun reorder(id: Long, sortPosition: Int) = withContext(IODispatcher) {
        db.stationsQueries.reorderStation(sortPosition.toLong(), nowMillis(), id)
    }

    suspend fun setPinned(id: Long, pinned: Boolean) = withContext(IODispatcher) {
        db.stationsQueries.setStationPinned(if (pinned) 1L else 0L, nowMillis(), id)
    }

    suspend fun delete(id: Long) = withContext(IODispatcher) {
        db.stationsQueries.deleteStationById(id)
    }

    /**
     * Up to [limit] album artwork URIs for the station's most recent
     * history, distinct by album. Powers the auto-collage thumbnail
     * shown on station tiles that have no custom artwork uploaded.
     * Returns empty when the station has no playable history yet.
     */
    suspend fun recentAlbumArtwork(stationId: Long, limit: Long = 4L): List<String> =
        withContext(IODispatcher) {
            // SQLDelight infers artwork_uri as non-null from the WHERE
            // `album.artwork_uri IS NOT NULL`. The blank-trim is the
            // last line of defence against stale empty strings that
            // pre-date the matching guard in `updateAlbumArtworkIfMissing`.
            db.stationsQueries.selectRecentAlbumArtworkForStation(stationId, limit)
                .executeAsList()
                .map { it.artwork_uri }
                .filter { it.isNotBlank() }
        }

    // ---- Pinned tracks (per-station) ----

    suspend fun pinTrackToStation(stationId: Long, trackId: Long) = withContext(IODispatcher) {
        db.stationsQueries.pinTrackToStation(stationId, trackId, nowMillis())
    }

    suspend fun unpinTrackFromStation(stationId: Long, trackId: Long) = withContext(IODispatcher) {
        db.stationsQueries.unpinTrackFromStation(stationId, trackId)
    }

    suspend fun isTrackPinned(stationId: Long, trackId: Long): Boolean =
        withContext(IODispatcher) {
            db.stationsQueries.isTrackPinnedToStation(stationId, trackId).executeAsOne() > 0L
        }

    suspend fun pinnedTracks(stationId: Long): List<Track> = withContext(IODispatcher) {
        db.stationsQueries.selectPinnedTracksForStation(stationId).executeAsList()
            .map { it.toDomain() }
    }

    suspend fun pinnedTrackIds(stationId: Long): List<Long> = withContext(IODispatcher) {
        db.stationsQueries.selectPinnedTrackIdsForStation(stationId).executeAsList()
    }

    suspend fun pinnedTrackCount(stationId: Long): Long = withContext(IODispatcher) {
        db.stationsQueries.countPinnedTracksForStation(stationId).executeAsOne()
    }

    // ---- Station history ----

    suspend fun recordPlay(
        stationId: Long,
        trackId: Long,
        playedAt: Long = nowMillis(),
        completed: Boolean = false,
        listenedMs: Long = 0,
    ): Long = withContext(IODispatcher) {
        db.stationsQueries.recordPlay(
            station_id = stationId,
            track_id = trackId,
            played_at = playedAt,
            completed = if (completed) 1L else 0L,
            listened_ms = listenedMs,
        ).executeAsOne()
    }

    suspend fun historyForStation(stationId: Long, limit: Long = 100): List<HistoryEntry> =
        withContext(IODispatcher) {
            db.stationsQueries.selectHistoryByStation(stationId, limit).executeAsList().map { row ->
                HistoryEntry(
                    id = row.id,
                    stationId = row.station_id,
                    trackId = row.track_id,
                    playedAt = row.played_at,
                    completed = row.completed == 1L,
                    listenedMs = row.listened_ms,
                )
            }
        }

    /**
     * Global play history across every station, newest first. Includes
     * the station's name pre-joined so the History screen doesn't need
     * a second per-row lookup.
     */
    suspend fun allHistory(limit: Long = 200): List<HistoryEntryWithStation> =
        withContext(IODispatcher) {
            db.stationsQueries.selectAllHistory(limit).executeAsList().map { row ->
                HistoryEntryWithStation(
                    id = row.history_id,
                    trackId = row.track_id,
                    stationId = row.station_id,
                    stationName = row.station_name,
                    playedAt = row.played_at,
                    completed = row.completed == 1L,
                    listenedMs = row.listened_ms,
                )
            }
        }

    suspend fun recentTrackIdsForStation(stationId: Long, limit: Long): List<Long> =
        withContext(IODispatcher) {
            db.stationsQueries.selectRecentTrackIdsForStation(stationId, limit).executeAsList()
        }

    suspend fun trimHistoryOlderThan(threshold: Long) = withContext(IODispatcher) {
        db.stationsQueries.trimHistoryOlderThan(threshold)
    }

    suspend fun clearAllHistory() = withContext(IODispatcher) {
        // Wipe both the granular history rows AND the denormalized
        // station-level counters so the Stats screen's Top Stations
        // section actually resets (it reads station.play_count, not
        // station_history aggregates).
        db.stationsQueries.clearAllHistory()
        db.stationsQueries.resetAllStationStats(nowMillis())
    }

    /** Top played tracks across all stations. Returned as (trackId, playCount). */
    suspend fun topPlayedTracks(limit: Long): List<Pair<Long, Long>> =
        withContext(IODispatcher) {
            db.stationsQueries.selectTopPlayedTrackIds(limit).executeAsList().map { row ->
                row.track_id to row.play_count
            }
        }

    /**
     * Rediscover candidates: track ids the user has played at least
     * once but whose most-recent play is older than [olderThanMs]
     * milliseconds (compare against [nowMillis]). Returned ordered
     * oldest-first.
     */
    suspend fun rediscoverTrackIds(olderThanMs: Long, limit: Long): List<Long> =
        withContext(IODispatcher) {
            db.stationsQueries.selectRediscoverTrackIds(olderThanMs, limit).executeAsList()
                .map { it.track_id }
        }

    suspend fun totalListenedMs(): Long = withContext(IODispatcher) {
        // The IFNULL wrapping in the SQL ensures a non-null Long even
        // when the table is empty (cold start), so executeAsOne is safe.
        db.stationsQueries.totalListenedMs().executeAsOne()
    }

    suspend fun countAllPlays(): Long = withContext(IODispatcher) {
        db.stationsQueries.countAllPlays().executeAsOne()
    }

    /** Top artists by play count. Returned as (artistId, artistName, plays). */
    suspend fun topPlayedArtists(limit: Long): List<TopArtistEntry> =
        withContext(IODispatcher) {
            db.stationsQueries.selectTopPlayedArtists(limit).executeAsList().map { row ->
                TopArtistEntry(
                    id = row.artist_id,
                    name = row.artist_name,
                    plays = row.plays,
                )
            }
        }

    /** Top albums by play count, including pre-resolved artwork URI. */
    suspend fun topPlayedAlbums(limit: Long): List<TopAlbumEntry> =
        withContext(IODispatcher) {
            db.stationsQueries.selectTopPlayedAlbums(limit).executeAsList().map { row ->
                TopAlbumEntry(
                    id = row.album_id,
                    title = row.album_title,
                    artworkUri = row.artwork_uri,
                    plays = row.plays,
                )
            }
        }

    // ---- Mappers ----

    private fun toStation(row: StationRow): Station = Station(
        id = row.id,
        name = row.name,
        color = row.color.toInt(),
        icon = row.icon,
        artworkUri = row.artwork_uri,
        sortPosition = row.sort_position.toInt(),
        customSubtitle = row.custom_subtitle,
        source = decodeSource(row.source_json),
        playback = decodePlayback(row.playback_json),
        stats = StationStats(
            playCount = row.play_count,
            lastPlayedAt = row.last_played_at,
            totalListenMs = row.total_listen_ms,
            skipRate = row.skip_rate,
        ),
        isPinned = row.is_pinned == 1L,
        isBuiltIn = row.is_built_in == 1L,
        createdAt = row.created_at,
        updatedAt = row.updated_at,
    )

    private fun encodeSource(source: SourceRule): String =
        RadioShedJson.encodeToString(SourceRule.serializer(), source)

    private fun decodeSource(json: String): SourceRule =
        RadioShedJson.decodeFromString(SourceRule.serializer(), json)

    private fun encodePlayback(playback: PlaybackRule): String =
        RadioShedJson.encodeToString(PlaybackRule.serializer(), playback)

    private fun decodePlayback(json: String): PlaybackRule =
        RadioShedJson.decodeFromString(PlaybackRule.serializer(), json)
}

data class HistoryEntry(
    val id: Long,
    val stationId: Long,
    val trackId: Long,
    val playedAt: Long,
    val completed: Boolean,
    val listenedMs: Long,
)

data class TopArtistEntry(val id: Long, val name: String, val plays: Long)

data class TopAlbumEntry(
    val id: Long,
    val title: String,
    val artworkUri: String?,
    val plays: Long,
)

data class HistoryEntryWithStation(
    val id: Long,
    val trackId: Long,
    val stationId: Long,
    val stationName: String,
    val playedAt: Long,
    val completed: Boolean,
    val listenedMs: Long,
)

/** Per-track aggregate from `station_history`, used by Track Info. */
data class TrackHistoryStats(
    val plays: Long,
    val completes: Long,
    val skips: Long,
    val listenedMs: Long,
)
