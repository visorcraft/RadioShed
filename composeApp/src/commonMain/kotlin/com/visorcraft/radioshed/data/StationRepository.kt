package com.visorcraft.radioshed.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.domain.PlaybackRule
import com.visorcraft.radioshed.domain.SourceRule
import com.visorcraft.radioshed.domain.Station
import com.visorcraft.radioshed.domain.StationStats
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

    suspend fun reorder(id: Long, sortPosition: Int) = withContext(IODispatcher) {
        db.stationsQueries.reorderStation(sortPosition.toLong(), nowMillis(), id)
    }

    suspend fun setPinned(id: Long, pinned: Boolean) = withContext(IODispatcher) {
        db.stationsQueries.setStationPinned(if (pinned) 1L else 0L, nowMillis(), id)
    }

    suspend fun delete(id: Long) = withContext(IODispatcher) {
        db.stationsQueries.deleteStationById(id)
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

    suspend fun recentTrackIdsForStation(stationId: Long, limit: Long): List<Long> =
        withContext(IODispatcher) {
            db.stationsQueries.selectRecentTrackIdsForStation(stationId, limit).executeAsList()
        }

    suspend fun trimHistoryOlderThan(threshold: Long) = withContext(IODispatcher) {
        db.stationsQueries.trimHistoryOlderThan(threshold)
    }

    /** Top played tracks across all stations. Returned as (trackId, playCount). */
    suspend fun topPlayedTracks(limit: Long): List<Pair<Long, Long>> =
        withContext(IODispatcher) {
            db.stationsQueries.selectTopPlayedTrackIds(limit).executeAsList().map { row ->
                row.track_id to row.play_count
            }
        }

    suspend fun totalListenedMs(): Long = withContext(IODispatcher) {
        // The IFNULL wrapping in the SQL ensures a non-null Long even
        // when the table is empty (cold start), so executeAsOne is safe.
        db.stationsQueries.totalListenedMs().executeAsOne()
    }

    suspend fun countAllPlays(): Long = withContext(IODispatcher) {
        db.stationsQueries.countAllPlays().executeAsOne()
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
