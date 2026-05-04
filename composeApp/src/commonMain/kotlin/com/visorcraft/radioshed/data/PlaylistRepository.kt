package com.visorcraft.radioshed.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.domain.Playlist
import com.visorcraft.radioshed.domain.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class PlaylistRepository(private val db: RadioShedDatabase) {

    fun observeAll(): Flow<List<Playlist>> =
        db.playlistsQueries.selectAllPlaylists()
            .asFlow()
            .mapToList(IODispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    suspend fun list(): List<Playlist> = withContext(IODispatcher) {
        db.playlistsQueries.selectAllPlaylists().executeAsList().map { it.toDomain() }
    }

    suspend fun getById(id: Long): Playlist? = withContext(IODispatcher) {
        db.playlistsQueries.selectPlaylistById(id).executeAsOneOrNull()?.toDomain()
    }

    suspend fun count(): Long = withContext(IODispatcher) {
        db.playlistsQueries.countPlaylists().executeAsOne()
    }

    suspend fun insert(name: String, description: String? = null, artworkUri: String? = null): Long =
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

    suspend fun rename(id: Long, name: String) = withContext(IODispatcher) {
        db.playlistsQueries.renamePlaylist(name, nowMillis(), id)
    }

    suspend fun setDescription(id: Long, description: String?) = withContext(IODispatcher) {
        db.playlistsQueries.updatePlaylistDescription(description, nowMillis(), id)
    }

    suspend fun delete(id: Long) = withContext(IODispatcher) {
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
    suspend fun duplicate(sourceId: Long, newName: String): Long = withContext(IODispatcher) {
        val sourceTracks = db.playlistsQueries
            .selectTracksInPlaylist(sourceId)
            .executeAsList()
            .map { it.toDomain() }
        val now = nowMillis()
        var newId = 0L
        db.transaction {
            newId = db.playlistsQueries.insertPlaylist(
                name = newName,
                description = null,
                artwork_uri = null,
                created_at = now,
                updated_at = now,
            ).executeAsOne()
            sourceTracks.forEachIndexed { idx, track ->
                db.playlistsQueries.addTrackToPlaylist(
                    playlist_id = newId,
                    track_id = track.id,
                    position = idx.toLong(),
                    added_at = now,
                )
            }
        }
        newId
    }

    // ---- Playlist tracks ----

    suspend fun addTrack(playlistId: Long, trackId: Long, position: Int) =
        withContext(IODispatcher) {
            db.playlistsQueries.addTrackToPlaylist(playlistId, trackId, position.toLong(), nowMillis())
        }

    suspend fun removeTrack(playlistId: Long, trackId: Long) = withContext(IODispatcher) {
        db.playlistsQueries.removeTrackFromPlaylist(playlistId, trackId)
    }

    suspend fun clear(playlistId: Long) = withContext(IODispatcher) {
        db.playlistsQueries.clearPlaylist(playlistId)
    }

    suspend fun reorderTrack(playlistId: Long, trackId: Long, position: Int) =
        withContext(IODispatcher) {
            db.playlistsQueries.reorderPlaylistTrack(position.toLong(), playlistId, trackId)
        }

    /**
     * Replace the entire track ordering for a playlist in one
     * transaction. [orderedTrackIds] must be the desired order from
     * top to bottom; positions are renumbered 0..N-1. Tracks not in
     * the list are left at their current position (callers should
     * pass the full track list to avoid partial-update surprises).
     */
    suspend fun setTrackOrder(playlistId: Long, orderedTrackIds: List<Long>) =
        withContext(IODispatcher) {
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

    suspend fun tracksIn(playlistId: Long): List<Track> = withContext(IODispatcher) {
        db.playlistsQueries.selectTracksInPlaylist(playlistId).executeAsList().map { it.toDomain() }
    }

    suspend fun trackCount(playlistId: Long): Long = withContext(IODispatcher) {
        db.playlistsQueries.countTracksInPlaylist(playlistId).executeAsOne()
    }

    /**
     * Cover-art URI for a playlist's first track. Used to render a
     * preview thumbnail on the playlist list. Returns null when the
     * playlist is empty, the first track lacks an album, or that
     * album has no artwork persisted.
     */
    suspend fun firstTrackArtwork(playlistId: Long): String? = withContext(IODispatcher) {
        // SQLDelight wraps single-column nullable result in NullableString
        // wrapper class; flatten via executeAsOneOrNull + the column.
        db.playlistsQueries.firstPlaylistTrackArtwork(playlistId)
            .executeAsOneOrNull()?.artwork_uri
    }
}
