package com.visorcraft.radioshed.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.domain.Playlist
import com.visorcraft.radioshed.domain.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// SQLite allows at most 999 host parameters per statement.
private const val IN_CLAUSE_CHUNK = 900

class PlaylistRepository(private val db: RadioShedDatabase) {
    fun observeAll(): Flow<List<Playlist>> =
        db.playlistsQueries.selectAllPlaylists()
            .asFlow()
            .mapToList(IODispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    suspend fun list(): List<Playlist> =
        withContext(IODispatcher) {
            db.playlistsQueries.selectAllPlaylists().executeAsList().map { it.toDomain() }
        }

    suspend fun getById(id: Long): Playlist? =
        withContext(IODispatcher) {
            db.playlistsQueries.selectPlaylistById(id).executeAsOneOrNull()?.toDomain()
        }

    suspend fun count(): Long =
        withContext(IODispatcher) {
            db.playlistsQueries.countPlaylists().executeAsOne()
        }

    suspend fun insert(
        name: String,
        description: String? = null,
        artworkUri: String? = null,
    ): Long =
        withContext(IODispatcher) {
            val now = nowMillis()
            db.playlistsQueries.insertPlaylist(
                name = name,
                description = description,
                artwork_uri = artworkUri,
                created_at = now,
                updated_at = now,
            ).executeAsOne()
        }

    suspend fun rename(
        id: Long,
        name: String,
    ) = withContext(IODispatcher) {
        db.playlistsQueries.renamePlaylist(name, nowMillis(), id)
    }

    suspend fun setDescription(
        id: Long,
        description: String?,
    ) = withContext(IODispatcher) {
        db.playlistsQueries.updatePlaylistDescription(description, nowMillis(), id)
    }

    suspend fun delete(id: Long) =
        withContext(IODispatcher) {
            db.playlistsQueries.deletePlaylistById(id)
        }

    /**
     * Create a new playlist with [newName] and copy every track from
     * [sourceId] in its current order. Single transaction so a
     * mid-copy crash leaves no half-populated row in the playlists
     * table. Returns the new playlist's id.
     *
     * The source's description and artwork URI are intentionally NOT
     * copied — duplication is for "branch this and start tweaking"
     * use cases where the user is going to want to rename and
     * re-curate. Auto-naming defaults to "{source name} (copy)" at
     * the call site if no explicit name is provided.
     */
    suspend fun duplicate(
        sourceId: Long,
        newName: String,
    ): Long =
        withContext(IODispatcher) {
            val sourceTrackIds =
                db.playlistsQueries
                    .selectTracksInPlaylist(sourceId)
                    .executeAsList()
                    .map { it.id }
            val now = nowMillis()
            var newId = 0L
            db.transaction {
                newId =
                    db.playlistsQueries.insertPlaylist(
                        name = newName,
                        description = null,
                        artwork_uri = null,
                        created_at = now,
                        updated_at = now,
                    ).executeAsOne()
                var pos = 0L
                for (trackId in sourceTrackIds) {
                    db.playlistsQueries.addTrackToPlaylist(
                        playlist_id = newId,
                        track_id = trackId,
                        position = pos,
                        added_at = now,
                    )
                    pos += 1
                }
            }
            newId
        }

    // ---- Playlist tracks ----

    suspend fun addTrack(
        playlistId: Long,
        trackId: Long,
        position: Int,
    ) = withContext(IODispatcher) {
        db.playlistsQueries.addTrackToPlaylist(playlistId, trackId, position.toLong(), nowMillis())
    }

    /**
     * Atomic "append to end" — replaces the unsafe pattern
     *   `addTrack(id, tid, position = trackCount(id).toInt())`
     * which had a TOCTOU between the count and the insert: two
     * concurrent appends both saw the same count and both wrote
     * rows at the same `position`, leaving the playlist with
     * duplicate position values and an arbitrary visible order.
     *
     * Wrapping count + insert in `db.transaction` serializes
     * concurrent appends on the SQLite write lock, so the second
     * append sees the first one's row when computing its position.
     */
    suspend fun appendTrack(
        playlistId: Long,
        trackId: Long,
    ) = withContext(IODispatcher) {
        db.transaction {
            val nextPos = db.playlistsQueries.countTracksInPlaylist(playlistId).executeAsOne()
            db.playlistsQueries.addTrackToPlaylist(playlistId, trackId, nextPos, nowMillis())
        }
    }

    /**
     * Bulk append: every track lands in the same transaction so a
     * mid-batch crash either commits the whole album/artist or
     * none of it. Avoids the per-track count+insert race even when
     * the caller is single-threaded — the count is read once at
     * the top of the transaction and each subsequent insert
     * increments the local position counter from there.
     */
    suspend fun appendTracks(
        playlistId: Long,
        trackIds: List<Long>,
    ) = withContext(IODispatcher) {
        if (trackIds.isEmpty()) return@withContext
        val now = nowMillis()
        db.transaction {
            var pos = db.playlistsQueries.countTracksInPlaylist(playlistId).executeAsOne()
            for (trackId in trackIds) {
                db.playlistsQueries.addTrackToPlaylist(playlistId, trackId, pos, now)
                pos += 1
            }
        }
    }

    suspend fun removeTrack(
        playlistId: Long,
        trackId: Long,
    ) = withContext(IODispatcher) {
        db.playlistsQueries.removeTrackFromPlaylist(playlistId, trackId)
    }

    suspend fun clear(playlistId: Long) =
        withContext(IODispatcher) {
            db.playlistsQueries.clearPlaylist(playlistId)
        }

    suspend fun reorderTrack(
        playlistId: Long,
        trackId: Long,
        position: Int,
    ) = withContext(IODispatcher) {
        db.playlistsQueries.reorderPlaylistTrack(position.toLong(), playlistId, trackId)
    }

    /**
     * Replace the entire track ordering for a playlist in one
     * transaction. [orderedTrackIds] must be the desired order from
     * top to bottom; positions are renumbered 0..N-1. Tracks not in
     * the list are left at their current position (callers should
     * pass the full track list to avoid partial-update surprises).
     */
    suspend fun setTrackOrder(
        playlistId: Long,
        orderedTrackIds: List<Long>,
    ) = withContext(IODispatcher) {
        db.transaction {
            orderedTrackIds.forEachIndexed { idx, trackId ->
                db.playlistsQueries.reorderPlaylistTrack(
                    position = idx.toLong(),
                    playlist_id = playlistId,
                    track_id = trackId,
                )
            }
        }
    }

    suspend fun tracksIn(playlistId: Long): List<Track> =
        withContext(IODispatcher) {
            db.playlistsQueries.selectTracksInPlaylist(playlistId).executeAsList().map { it.toDomain() }
        }

    suspend fun trackCount(playlistId: Long): Long =
        withContext(IODispatcher) {
            db.playlistsQueries.countTracksInPlaylist(playlistId).executeAsOne()
        }

    /**
     * Cover-art URI for a playlist's first track. Used to render a
     * preview thumbnail on the playlist list. Returns null when the
     * playlist is empty, the first track lacks an album, or that
     * album has no artwork persisted.
     */
    suspend fun firstTrackArtwork(playlistId: Long): String? =
        withContext(IODispatcher) {
            // SQLDelight wraps single-column nullable result in NullableString
            // wrapper class; flatten via executeAsOneOrNull + the column.
            db.playlistsQueries.firstPlaylistTrackArtwork(playlistId)
                .executeAsOneOrNull()?.artwork_uri
        }

    suspend fun firstTrackArtworkForPlaylists(playlistIds: Collection<Long>): Map<Long, String?> =
        withContext(IODispatcher) {
            if (playlistIds.isEmpty()) return@withContext emptyMap()
            playlistIds.toSet().chunked(IN_CLAUSE_CHUNK).flatMap {
                db.playlistsQueries.firstTrackArtworkForPlaylists(it).executeAsList()
            }.associate { it.playlist_id to it.artwork_uri }
        }

    suspend fun trackCountsForPlaylists(playlistIds: Collection<Long>): Map<Long, Long> =
        withContext(IODispatcher) {
            if (playlistIds.isEmpty()) return@withContext emptyMap()
            playlistIds.toSet().chunked(IN_CLAUSE_CHUNK).flatMap {
                db.playlistsQueries.trackCountsForPlaylists(it).executeAsList()
            }.associate { it.playlist_id to it.track_count }
        }
}
