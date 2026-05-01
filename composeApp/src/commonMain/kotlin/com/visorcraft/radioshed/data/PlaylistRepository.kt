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

    suspend fun tracksIn(playlistId: Long): List<Track> = withContext(IODispatcher) {
        db.playlistsQueries.selectTracksInPlaylist(playlistId).executeAsList().map { it.toDomain() }
    }

    suspend fun trackCount(playlistId: Long): Long = withContext(IODispatcher) {
        db.playlistsQueries.countTracksInPlaylist(playlistId).executeAsOne()
    }
}
