package com.visorcraft.radioshed.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.domain.Playlist
import com.visorcraft.radioshed.domain.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.visorcraft.radioshed.db.Playlist as PlaylistRow
import com.visorcraft.radioshed.db.Track as TrackRow

class PlaylistRepository(private val db: RadioShedDatabase) {

    fun observeAll(): Flow<List<Playlist>> =
        db.playlistsQueries.selectAllPlaylists()
            .asFlow()
            .mapToList(IODispatcher)
            .map { rows -> rows.map(::toPlaylist) }

    suspend fun list(): List<Playlist> = withContext(IODispatcher) {
        db.playlistsQueries.selectAllPlaylists().executeAsList().map(::toPlaylist)
    }

    suspend fun getById(id: Long): Playlist? = withContext(IODispatcher) {
        db.playlistsQueries.selectPlaylistById(id).executeAsOneOrNull()?.let(::toPlaylist)
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
        db.playlistsQueries.selectTracksInPlaylist(playlistId).executeAsList().map(::toTrack)
    }

    suspend fun trackCount(playlistId: Long): Long = withContext(IODispatcher) {
        db.playlistsQueries.countTracksInPlaylist(playlistId).executeAsOne()
    }

    // ---- Mappers ----

    private fun toPlaylist(row: PlaylistRow): Playlist = Playlist(
        id = row.id,
        name = row.name,
        description = row.description,
        artworkUri = row.artwork_uri,
        createdAt = row.created_at,
        updatedAt = row.updated_at,
    )

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
