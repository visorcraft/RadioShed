package com.visorcraft.radioshed.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.domain.Album
import com.visorcraft.radioshed.domain.Artist
import com.visorcraft.radioshed.domain.Personnel
import com.visorcraft.radioshed.domain.Tag
import com.visorcraft.radioshed.domain.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.visorcraft.radioshed.db.Album as AlbumRow
import com.visorcraft.radioshed.db.Artist as ArtistRow
import com.visorcraft.radioshed.db.Track as TrackRow
import com.visorcraft.radioshed.db.Track_personnel as TrackPersonnelRow
import com.visorcraft.radioshed.db.Track_tag as TrackTagRow

/**
 * Repository for the core music-library entities: Artist, Album, Track, plus
 * track ↔ artist links, custom tags, and personnel (composer/conductor/etc.).
 *
 * Bigger surface than other repositories because the scanner needs to atomically
 * register a track's artists, album, tags, and personnel in one logical operation.
 */
class LibraryRepository(private val db: RadioShedDatabase) {

    // ---- Artist ----

    suspend fun upsertArtist(name: String, sortName: String, musicbrainzId: String? = null): Long =
        withContext(IODispatcher) {
            val existing = db.libraryQueries.selectArtistBySortName(sortName).executeAsOneOrNull()
            if (existing != null) return@withContext existing.id
            val now = nowMillis()
            db.libraryQueries.insertArtist(
                name = name,
                sort_name = sortName,
                musicbrainz_id = musicbrainzId,
                image_uri = null,
                bio = null,
                created_at = now,
                updated_at = now,
            ).executeAsOne()
        }

    suspend fun getArtistById(id: Long): Artist? = withContext(IODispatcher) {
        db.libraryQueries.selectArtistById(id).executeAsOneOrNull()?.let(::toArtist)
    }

    fun observeArtists(): Flow<List<Artist>> =
        db.libraryQueries.selectAllArtists()
            .asFlow()
            .mapToList(IODispatcher)
            .map { rows -> rows.map(::toArtist) }

    suspend fun setArtistImage(id: Long, imageUri: String?) = withContext(IODispatcher) {
        db.libraryQueries.updateArtistImage(imageUri, nowMillis(), id)
    }

    suspend fun setArtistBio(id: Long, bio: String?) = withContext(IODispatcher) {
        db.libraryQueries.updateArtistBio(bio, nowMillis(), id)
    }

    suspend fun countArtists(): Long = withContext(IODispatcher) {
        db.libraryQueries.countArtists().executeAsOne()
    }

    // ---- Album ----

    suspend fun upsertAlbum(
        title: String,
        sortTitle: String,
        artistId: Long? = null,
        year: Int? = null,
        musicbrainzId: String? = null,
    ): Long = withContext(IODispatcher) {
        val existing = db.libraryQueries.selectAlbumByTitleAndArtist(sortTitle, artistId).executeAsOneOrNull()
        if (existing != null) return@withContext existing.id
        val now = nowMillis()
        db.libraryQueries.insertAlbum(
            title = title,
            artist_id = artistId,
            sort_title = sortTitle,
            year = year?.toLong(),
            musicbrainz_id = musicbrainzId,
            artwork_uri = null,
            track_count = 0,
            created_at = now,
            updated_at = now,
        ).executeAsOne()
    }

    suspend fun getAlbumById(id: Long): Album? = withContext(IODispatcher) {
        db.libraryQueries.selectAlbumById(id).executeAsOneOrNull()?.let(::toAlbum)
    }

    fun observeAlbums(): Flow<List<Album>> =
        db.libraryQueries.selectAllAlbums()
            .asFlow()
            .mapToList(IODispatcher)
            .map { rows -> rows.map(::toAlbum) }

    suspend fun setAlbumArtwork(id: Long, artworkUri: String?) = withContext(IODispatcher) {
        db.libraryQueries.updateAlbumArtwork(artworkUri, nowMillis(), id)
    }

    suspend fun setAlbumTrackCount(id: Long, trackCount: Long) = withContext(IODispatcher) {
        db.libraryQueries.updateAlbumTrackCount(trackCount, nowMillis(), id)
    }

    suspend fun countAlbums(): Long = withContext(IODispatcher) {
        db.libraryQueries.countAlbums().executeAsOne()
    }

    // ---- Track ----

    suspend fun insertTrack(
        folderId: Long,
        path: String,
        fileName: String,
        fileSize: Long,
        fileCtime: Long,
        fileMtime: Long,
        title: String,
        albumId: Long? = null,
        trackNumber: Int? = null,
        discNumber: Int? = null,
        durationMs: Long,
        bitrateKbps: Int? = null,
        sampleRateHz: Int? = null,
        channels: Int? = null,
        format: String,
        replayGainTrack: Double? = null,
        replayGainAlbum: Double? = null,
        replayGainTrackPeak: Double? = null,
        bpm: Double? = null,
        musicbrainzId: String? = null,
        acoustidFingerprint: String? = null,
        cueOffsetStartMs: Long? = null,
        cueOffsetEndMs: Long? = null,
    ): Long = withContext(IODispatcher) {
        val now = nowMillis()
        db.libraryQueries.insertTrack(
            folder_id = folderId,
            path = path,
            file_name = fileName,
            file_size = fileSize,
            file_ctime = fileCtime,
            file_mtime = fileMtime,
            title = title,
            album_id = albumId,
            track_number = trackNumber?.toLong(),
            disc_number = discNumber?.toLong(),
            duration_ms = durationMs,
            bitrate_kbps = bitrateKbps?.toLong(),
            sample_rate_hz = sampleRateHz?.toLong(),
            channels = channels?.toLong(),
            format = format,
            replay_gain_track = replayGainTrack,
            replay_gain_album = replayGainAlbum,
            replay_gain_track_peak = replayGainTrackPeak,
            bpm = bpm,
            musicbrainz_id = musicbrainzId,
            acoustid_fingerprint = acoustidFingerprint,
            cue_offset_start_ms = cueOffsetStartMs,
            cue_offset_end_ms = cueOffsetEndMs,
            added_at = now,
            updated_at = now,
        ).executeAsOne()
    }

    suspend fun getTrackById(id: Long): Track? = withContext(IODispatcher) {
        db.libraryQueries.selectTrackById(id).executeAsOneOrNull()?.let(::toTrack)
    }

    suspend fun getTrackByPath(path: String): Track? = withContext(IODispatcher) {
        db.libraryQueries.selectTrackByPath(path).executeAsOneOrNull()?.let(::toTrack)
    }

    suspend fun listTracksByFolder(folderId: Long): List<Track> = withContext(IODispatcher) {
        db.libraryQueries.selectTracksByFolder(folderId).executeAsList().map(::toTrack)
    }

    suspend fun listAvailableTracks(limit: Long, offset: Long): List<Track> =
        withContext(IODispatcher) {
            db.libraryQueries.selectAvailableTracks(limit, offset).executeAsList().map(::toTrack)
        }

    suspend fun newestTracks(limit: Long): List<Track> = withContext(IODispatcher) {
        db.libraryQueries.selectNewestTracks(limit).executeAsList().map(::toTrack)
    }

    suspend fun setTrackAvailable(id: Long, available: Boolean) = withContext(IODispatcher) {
        db.libraryQueries.setTrackAvailable(if (available) 1L else 0L, nowMillis(), id)
    }

    suspend fun setTracksAvailableByFolder(folderId: Long, available: Boolean) =
        withContext(IODispatcher) {
            db.libraryQueries.setTracksAvailableByFolder(if (available) 1L else 0L, nowMillis(), folderId)
        }

    suspend fun deleteTrack(id: Long) = withContext(IODispatcher) {
        db.libraryQueries.deleteTrackById(id)
    }

    suspend fun deleteTracksByFolder(folderId: Long) = withContext(IODispatcher) {
        db.libraryQueries.deleteTracksByFolder(folderId)
    }

    suspend fun countTracks(): Long = withContext(IODispatcher) {
        db.libraryQueries.countTracks().executeAsOne()
    }

    // ---- Track ↔ Artist ----

    suspend fun linkTrackToArtist(
        trackId: Long,
        artistId: Long,
        role: String = "primary",
        position: Int = 0,
    ) = withContext(IODispatcher) {
        db.libraryQueries.linkTrackArtist(trackId, artistId, role, position.toLong())
    }

    suspend fun artistsForTrack(trackId: Long): List<Artist> = withContext(IODispatcher) {
        db.libraryQueries.selectArtistsByTrack(trackId).executeAsList().map(::toArtist)
    }

    suspend fun tracksForArtist(artistId: Long): List<Track> = withContext(IODispatcher) {
        db.libraryQueries.selectTracksByArtist(artistId).executeAsList().map(::toTrack)
    }

    suspend fun unlinkAllArtistsFromTrack(trackId: Long) = withContext(IODispatcher) {
        db.libraryQueries.unlinkAllArtistsFromTrack(trackId)
    }

    // ---- Tags ----

    suspend fun setTagsForTrack(trackId: Long, tags: List<Tag>) = withContext(IODispatcher) {
        db.libraryQueries.deleteAllTagsForTrack(trackId)
        tags.forEach { tag ->
            db.libraryQueries.insertTrackTag(trackId, tag.key, tag.value, tag.position.toLong())
        }
    }

    suspend fun tagsForTrack(trackId: Long): List<Tag> = withContext(IODispatcher) {
        db.libraryQueries.selectTagsByTrack(trackId).executeAsList().map(::toTag)
    }

    suspend fun tracksByTag(key: String, value: String): List<Track> = withContext(IODispatcher) {
        db.libraryQueries.selectTracksByTag(key, value).executeAsList().map(::toTrack)
    }

    suspend fun distinctTagValues(key: String): List<String> = withContext(IODispatcher) {
        db.libraryQueries.distinctTagValues(key).executeAsList()
    }

    // ---- Personnel ----

    suspend fun setPersonnelForTrack(trackId: Long, personnel: List<Personnel>) =
        withContext(IODispatcher) {
            db.libraryQueries.deleteAllPersonnelForTrack(trackId)
            personnel.forEach { p ->
                db.libraryQueries.insertTrackPersonnel(trackId, p.role, p.name, p.position.toLong())
            }
        }

    suspend fun personnelForTrack(trackId: Long): List<Personnel> = withContext(IODispatcher) {
        db.libraryQueries.selectPersonnelByTrack(trackId).executeAsList().map(::toPersonnel)
    }

    // ---- Mappers ----

    private fun toArtist(row: ArtistRow): Artist = Artist(
        id = row.id,
        name = row.name,
        sortName = row.sort_name,
        musicbrainzId = row.musicbrainz_id,
        imageUri = row.image_uri,
        bio = row.bio,
        createdAt = row.created_at,
        updatedAt = row.updated_at,
    )

    private fun toAlbum(row: AlbumRow): Album = Album(
        id = row.id,
        title = row.title,
        artistId = row.artist_id,
        sortTitle = row.sort_title,
        year = row.year?.toInt(),
        musicbrainzId = row.musicbrainz_id,
        artworkUri = row.artwork_uri,
        trackCount = row.track_count,
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

    private fun toTag(row: TrackTagRow): Tag =
        Tag(key = row.key, value = row.value_, position = row.position.toInt())

    private fun toPersonnel(row: TrackPersonnelRow): Personnel =
        Personnel(role = row.role, name = row.name, position = row.position.toInt())
}
