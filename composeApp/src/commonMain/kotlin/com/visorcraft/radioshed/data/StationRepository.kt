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

    /**
     * Clone an existing station's source / playback / cosmetic fields
     * into a new row with a `(copy)` suffix on the name. Reset stats
     * (the duplicate hasn't been played yet), reset built-in flag
     * (the copy is a user station even if seeded from a preset), and
     * append at the bottom of sort order so it doesn't shove existing
     * stations around. Returns the new station id.
     *
     * The MAX(sort_position) lookup and INSERT run in a single
     * SQLDelight transaction so two concurrent Duplicate taps can't
     * resolve the same sort_position and corrupt the visible order.
     *
     * Name collision: if the source already ends in `(copy)` or
     * `(copy N)`, increment to `(copy N+1)` so a second duplicate of
     * "Foo (copy)" yields "Foo (copy 2)" rather than the awkward
     * "Foo (copy) (copy)".
     */
    suspend fun duplicate(stationId: Long): Long? = withContext(IODispatcher) {
        val src = getById(stationId) ?: return@withContext null
        val now = nowMillis()
        db.stationsQueries.transactionWithResult {
            val maxSort = db.stationsQueries.selectMaxStationSort().executeAsOne()
            db.stationsQueries.insertStation(
                name = bumpCopySuffix(src.name),
                color = src.color.toLong(),
                icon = src.icon,
                artwork_uri = src.artworkUri,
                sort_position = maxSort + 1L,
                custom_subtitle = src.customSubtitle,
                source_json = encodeSource(src.source),
                playback_json = encodePlayback(src.playback),
                is_built_in = 0L,
                created_at = now,
                updated_at = now,
            ).executeAsOne()
        }
    }

    /**
     * Atomic-pair reorder: swap two stations' sort positions inside
     * one transaction so a process kill (or a Flow observer firing
     * mid-pair) can't leave the list with two stations sharing the
     * same sort_position. Used by the up/down buttons in the long-
     * press sheet.
     */
    suspend fun swapSortPositions(idA: Long, posA: Int, idB: Long, posB: Int) =
        withContext(IODispatcher) {
            val now = nowMillis()
            db.stationsQueries.transaction {
                db.stationsQueries.reorderStation(posB.toLong(), now, idA)
                db.stationsQueries.reorderStation(posA.toLong(), now, idB)
            }
        }

    /**
     * Move [stationId] to the very top or bottom of the station order.
     * Renumbers every row in a single transaction so the resulting
     * sort_position values are dense (0..N-1) — keeps swap-pair
     * reorders from later inheriting wide gaps when the user has been
     * shuffling things around. The station list passed in must be the
     * already-sorted view the user is looking at; we trust its order
     * rather than re-querying so the move matches what they see.
     */
    suspend fun moveStationToEnd(
        stationId: Long,
        currentOrder: List<Long>,
        toTop: Boolean,
    ) = withContext(IODispatcher) {
        if (currentOrder.size < 2) return@withContext
        if (stationId !in currentOrder) return@withContext
        val without = currentOrder.filter { it != stationId }
        val newOrder = if (toTop) listOf(stationId) + without else without + stationId
        val now = nowMillis()
        db.stationsQueries.transaction {
            newOrder.forEachIndexed { index, id ->
                db.stationsQueries.reorderStation(index.toLong(), now, id)
            }
        }
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
     * Wipe one station's history rows + reset its cached counters.
     * Two writes in one transaction so a "Reset stats" UI can't
     * leave the station in a half-cleared state where the history
     * is gone but `play_count` still reads 47. Stats screens that
     * read station.play_count and the History screen that reads
     * station_history rows both go to zero atomically.
     */
    suspend fun resetStationStats(stationId: Long) = withContext(IODispatcher) {
        db.stationsQueries.transaction {
            db.stationsQueries.deleteHistoryByStation(stationId)
            db.stationsQueries.resetStationStatsById(nowMillis(), stationId)
        }
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

    /**
     * Set or clear the user-uploaded station artwork URI. Pass null
     * to revert to the auto-collage / preset gradient defaults.
     */
    suspend fun setArtwork(id: Long, artworkUri: String?) = withContext(IODispatcher) {
        db.stationsQueries.updateStationArtwork(artworkUri, nowMillis(), id)
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

    /**
     * Wipe the rows from the *recent* end of the history timeline.
     * Used by the "Clear last hour / 24 hours" entries in the History
     * screen. Threshold is the cutoff played_at — anything ≥ it gets
     * deleted. Counters on `station` are intentionally left alone:
     * the play happened, just not in the visible log.
     */
    suspend fun trimHistoryNewerThan(threshold: Long) = withContext(IODispatcher) {
        db.stationsQueries.trimHistoryNewerThan(threshold)
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

    /**
     * Day-bucketed play activity for the last [windowDays]. Returns a
     * dense list of [windowDays] entries — index 0 is the oldest day,
     * the last index is "today" — so the caller can render a uniform
     * bar chart without having to fill in zero-play gaps. Bucketing
     * is by 24-hour rolling windows from now (not local calendar
     * days), which is close enough for the visualization and avoids
     * timezone math in SQL.
     */
    suspend fun dailyActivity(windowDays: Int = 30): List<DailyActivity> =
        withContext(IODispatcher) {
            val now = nowMillis()
            // +1 ms epsilon so a row exactly at the boundary doesn't
            // get bucketed as days_ago == windowDays (which the
            // (windowDays-1 downTo 0) renderer below skips). Without
            // this, the oldest day's plays silently disappear when
            // they happen to land on the boundary millisecond.
            val sinceMs = now - windowDays.toLong() * DAY_MS + 1L
            val rows = db.stationsQueries.selectPlaysPerDayWindow(
                nowMs = now,
                sinceMs = sinceMs,
            ).executeAsList()
            val byDaysAgo = rows.associate {
                it.days_ago.toInt() to (it.plays to it.listened_ms)
            }
            // Render oldest → newest so the bar chart reads left-to-
            // right with "today" on the right.
            (windowDays - 1 downTo 0).map { offset ->
                val (plays, listenedMs) = byDaysAgo[offset] ?: (0L to 0L)
                DailyActivity(
                    daysAgo = offset,
                    plays = plays,
                    listenedMs = listenedMs,
                )
            }
        }

    /**
     * Plays bucketed by local hour-of-day (0..23) over the last
     * [windowDays]. Returns a dense array of 24 longs — one per
     * hour, hour 0 first. Useful for "what time of day do I listen?"
     * histogram on the Stats screen.
     */
    suspend fun playsByHourOfDay(windowDays: Int = 30): LongArray =
        withContext(IODispatcher) {
            val sinceMs = nowMillis() - windowDays.toLong() * DAY_MS
            val rows = db.stationsQueries.selectPlaysPerHourWindow(sinceMs).executeAsList()
            val out = LongArray(24)
            rows.forEach { row ->
                val h = row.hour?.toInt()?.coerceIn(0, 23) ?: return@forEach
                out[h] = row.plays
            }
            out
        }

    /**
     * Plays bucketed by local weekday over the last [windowDays].
     * Returns a dense LongArray of size 7 — Monday is index 0,
     * Sunday is index 6 (rotated from SQLite's Sunday-first
     * convention so the chart reads with the work week as the
     * "front" of the histogram).
     */
    suspend fun playsByWeekday(windowDays: Int = 30): LongArray =
        withContext(IODispatcher) {
            val sinceMs = nowMillis() - windowDays.toLong() * DAY_MS
            val rows = db.stationsQueries.selectPlaysPerWeekdayWindow(sinceMs)
                .executeAsList()
            val out = LongArray(7)
            rows.forEach { row ->
                val sqliteWd = row.weekday?.toInt() ?: return@forEach
                if (sqliteWd !in 0..6) return@forEach
                // SQLite: 0 = Sunday … 6 = Saturday.
                // Our index: 0 = Monday … 6 = Sunday.
                val mondayFirst = (sqliteWd + 6) % 7
                out[mondayFirst] = row.plays
            }
            out
        }

    /**
     * Listening streak over the rolling [windowDays] window. Returns a
     * pair of (currentStreakDays, longestStreakDays). Current streak
     * is the consecutive run of days with ≥1 play counting back from
     * today; if the user didn't listen today but did listen
     * yesterday, current streak = 0 (a missed day breaks the chain).
     * Longest streak is the maximum consecutive run anywhere in the
     * window. Both counts are zero on a cold-start with no plays.
     */
    suspend fun listeningStreaks(windowDays: Int = 90): Pair<Int, Int> =
        withContext(IODispatcher) {
            val sinceMs = nowMillis() - windowDays.toLong() * DAY_MS
            val daysList: List<Long> = db.stationsQueries.selectActivePlayDays(sinceMs)
                .executeAsList()
                .mapNotNull { it.day_epoch }
            if (daysList.isEmpty()) {
                return@withContext Pair(0, 0)
            }
            // Current streak: rows are newest-first day-epoch. Walk
            // forward and count while each row equals the previous
            // minus one. The first row must equal "today's day epoch"
            // for the chain to be live; if the most recent play was
            // yesterday, current = 0.
            val todayEpoch: Long = nowMillis() / DAY_MS
            var current = 0
            if (daysList[0] == todayEpoch) {
                current = 1
                var prev: Long = daysList[0]
                for (i in 1 until daysList.size) {
                    val d = daysList[i]
                    if (d == prev - 1L) {
                        current += 1
                        prev = d
                    } else {
                        break
                    }
                }
            }
            // Longest streak across the whole window.
            var longest = 1
            var run = 1
            for (i in 1 until daysList.size) {
                if (daysList[i] == daysList[i - 1] - 1L) {
                    run += 1
                    if (run > longest) longest = run
                } else {
                    run = 1
                }
            }
            Pair(current, longest)
        }

    /**
     * Completion-rate over the entire station_history table.
     * Returned as (completedPlays, totalPlays) so the caller can
     * decide how to format (percentage, ratio, etc.). The cold-start
     * case returns 0 / 0; UI must guard against divide-by-zero.
     * Both columns are non-null thanks to IFNULL/COUNT in the SQL.
     */
    suspend fun completionStats(): Pair<Long, Long> = withContext(IODispatcher) {
        val row = db.stationsQueries.selectCompletionStats().executeAsOne()
        row.completed to row.total
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

    /**
     * Top genres by play count. Joins history → track → track_tag
     * filtered to GENRE values. The Stats screen renders this as a
     * compact tile alongside top tracks/artists/albums.
     */
    suspend fun topPlayedGenres(limit: Long): List<Pair<String, Long>> =
        withContext(IODispatcher) {
            db.stationsQueries.selectTopPlayedGenres(limit).executeAsList().map { row ->
                row.genre to row.plays
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

/**
 * Bumps a `(copy)` / `(copy N)` suffix on a duplicate-target name so a
 * second duplicate of "Foo (copy)" reads "Foo (copy 2)" rather than
 * "Foo (copy) (copy)". Plain names just get " (copy)" appended.
 */
internal fun bumpCopySuffix(name: String): String {
    val numberedRegex = Regex("""^(.*) \(copy (\d+)\)$""")
    val plainSuffix = " (copy)"
    val numberedMatch = numberedRegex.matchEntire(name)
    if (numberedMatch != null) {
        val base = numberedMatch.groupValues[1]
        val n = numberedMatch.groupValues[2].toIntOrNull() ?: 1
        return "$base (copy ${n + 1})"
    }
    if (name.endsWith(plainSuffix)) {
        val base = name.removeSuffix(plainSuffix)
        return "$base (copy 2)"
    }
    return name + plainSuffix
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

/** One bar in the listening-activity bar chart on the Stats screen. */
data class DailyActivity(
    /** 0 = today's bucket, increasing into the past. */
    val daysAgo: Int,
    val plays: Long,
    val listenedMs: Long,
)

private const val DAY_MS: Long = 24L * 60L * 60L * 1000L

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
