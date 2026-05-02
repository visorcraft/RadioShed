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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Repository for the core music-library entities: Artist, Album, Track, plus
 * track ↔ artist links, custom tags, and personnel (composer/conductor/etc.).
 *
 * Multi-step operations (upserts, "replace all tags for a track") are wrapped
 * in [db.transactionWithResult] / [db.transaction] so concurrent scanner runs
 * can't observe partially applied state or race on UNIQUE-constraint inserts.
 */
class LibraryRepository(private val db: RadioShedDatabase) {

    // ---- Artist ----

    suspend fun upsertArtist(name: String, sortName: String, musicbrainzId: String? = null): Long =
        withContext(IODispatcher) {
            db.transactionWithResult {
                val now = nowMillis()
                // INSERT OR IGNORE handles concurrent inserts colliding on
                // sort_name UNIQUE; the follow-up SELECT returns the id either
                // way (the row that existed already, or the row we just wrote).
                db.libraryQueries.insertArtistOrIgnore(
                    name = name,
                    sort_name = sortName,
                    musicbrainz_id = musicbrainzId,
                    image_uri = null,
                    bio = null,
                    created_at = now,
                    updated_at = now,
                )
                db.libraryQueries.selectArtistIdBySortName(sortName).executeAsOne()
            }
        }

    suspend fun getArtistById(id: Long): Artist? = withContext(IODispatcher) {
        db.libraryQueries.selectArtistById(id).executeAsOneOrNull()?.toDomain()
    }

    fun observeArtists(): Flow<List<Artist>> =
        db.libraryQueries.selectAllArtists()
            .asFlow()
            .mapToList(IODispatcher)
            .map { rows -> rows.map { it.toDomain() } }

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
        db.transactionWithResult {
            val now = nowMillis()
            // INSERT OR IGNORE handles concurrent upserts colliding on the
            // (sort_title, artist_id) UNIQUE constraint added in the schema.
            db.libraryQueries.insertAlbumOrIgnore(
                title = title,
                artist_id = artistId,
                sort_title = sortTitle,
                year = year?.toLong(),
                musicbrainz_id = musicbrainzId,
                artwork_uri = null,
                track_count = 0,
                created_at = now,
                updated_at = now,
            )
            db.libraryQueries.selectAlbumIdByTitleAndArtist(sortTitle, artistId).executeAsOne()
        }
    }

    suspend fun getAlbumById(id: Long): Album? = withContext(IODispatcher) {
        db.libraryQueries.selectAlbumById(id).executeAsOneOrNull()?.toDomain()
    }

    fun observeAlbums(): Flow<List<Album>> =
        db.libraryQueries.selectAllAlbums()
            .asFlow()
            .mapToList(IODispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    suspend fun setAlbumArtwork(id: Long, artworkUri: String?) = withContext(IODispatcher) {
        db.libraryQueries.updateAlbumArtwork(artworkUri, nowMillis(), id)
    }

    /**
     * Albums that have a MusicBrainz Release ID embedded in their tags
     * but no artwork on disk yet. Used by Cover Art Archive sync.
     * Returns (albumId, mbid) pairs.
     */
    suspend fun albumsMissingArtwork(): List<Pair<Long, String>> = withContext(IODispatcher) {
        // The SQL query already filters out NULL/blank mbids so the column
        // comes back non-null, but we still drop pure-whitespace defensively.
        db.libraryQueries.selectAlbumsMissingArtwork().executeAsList()
            .mapNotNull { row ->
                val mbid = row.musicbrainz_id.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                row.id to mbid
            }
    }

    suspend fun countAlbumsMissingArtwork(): Long = withContext(IODispatcher) {
        db.libraryQueries.countAlbumsMissingArtwork().executeAsOne()
    }

    suspend fun setAlbumTrackCount(id: Long, trackCount: Long) = withContext(IODispatcher) {
        db.libraryQueries.updateAlbumTrackCount(trackCount, nowMillis(), id)
    }

    suspend fun countAlbums(): Long = withContext(IODispatcher) {
        db.libraryQueries.countAlbums().executeAsOne()
    }

    // ---- Track ----

    /**
     * Upserts a track by `path` (which is UNIQUE). On first insert, all metadata
     * fields are persisted. On a subsequent call with the same path (race or rescan),
     * this is a no-op for metadata and returns the existing track id — the scanner
     * is expected to call [setArtistsForTrack], [setTagsForTrack], etc. afterwards
     * to refresh metadata that may have changed in the underlying file.
     */
    /**
     * Upsert a track and (optionally) link it to [folderId] in one
     * transaction. Pass folderId=null to upsert the track without
     * touching its folder membership — useful when something other than
     * the scanner writes a track row.
     *
     * Schema v2: a track can belong to multiple folders (junction table
     * `track_folder`). Subsequent calls with different folderIds for the
     * same track add additional folder memberships rather than overwriting.
     */
    suspend fun upsertTrack(
        folderId: Long?,
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
        /**
         * Optional artist links + custom tags + personnel applied
         * inside the same transaction as the track upsert. Wrapping
         * everything atomically prevents a window where the track row
         * exists but its artist links don't (which made
         * `selectTopPlayedArtists` silently miss those plays).
         */
        artistIdsAndRoles: List<Pair<Long, String>>? = null,
        customTags: List<Tag>? = null,
        personnel: List<Personnel>? = null,
    ): Long = withContext(IODispatcher) {
        db.transactionWithResult {
            val now = nowMillis()
            db.libraryQueries.upsertTrack(
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
            )
            val trackId = db.libraryQueries.selectTrackIdByPath(path).executeAsOne()
            if (folderId != null) {
                db.libraryQueries.addTrackToFolder(trackId, folderId)
            }
            if (artistIdsAndRoles != null) {
                db.libraryQueries.unlinkAllArtistsFromTrack(trackId)
                artistIdsAndRoles.forEachIndexed { index, (artistId, role) ->
                    db.libraryQueries.linkTrackArtist(
                        trackId,
                        artistId,
                        role,
                        index.toLong(),
                    )
                }
            }
            if (customTags != null) {
                db.libraryQueries.deleteAllTagsForTrack(trackId)
                customTags.forEach { tag ->
                    db.libraryQueries.insertTrackTag(
                        trackId,
                        tag.key,
                        tag.value,
                        tag.position.toLong(),
                    )
                }
            }
            if (personnel != null) {
                db.libraryQueries.deleteAllPersonnelForTrack(trackId)
                personnel.forEach { p ->
                    db.libraryQueries.insertTrackPersonnel(
                        trackId,
                        p.role,
                        p.name,
                        p.position.toLong(),
                    )
                }
            }
            trackId
        }
    }

    /** Add a track ↔ folder membership. INSERT OR IGNORE; safe to re-run. */
    suspend fun addTrackToFolder(trackId: Long, folderId: Long) = withContext(IODispatcher) {
        db.libraryQueries.addTrackToFolder(trackId, folderId)
    }

    /**
     * Bulk variant: pass the track ids to register with [folderId] as a
     * Set/List, persists in one transaction. Used by the scanner when
     * scanning a folder that overlaps with a previously-scanned one and
     * we want to claim tracks the file walker discovered without re-
     * upserting their full metadata.
     */
    suspend fun addTracksToFolder(trackIds: Collection<Long>, folderId: Long) =
        withContext(IODispatcher) {
            db.transaction {
                trackIds.forEach { db.libraryQueries.addTrackToFolder(it, folderId) }
            }
        }

    suspend fun getTrackById(id: Long): Track? = withContext(IODispatcher) {
        db.libraryQueries.selectTrackById(id).executeAsOneOrNull()?.toDomain()
    }

    suspend fun getTrackByPath(path: String): Track? = withContext(IODispatcher) {
        db.libraryQueries.selectTrackByPath(path).executeAsOneOrNull()?.toDomain()
    }

    suspend fun listTracksByFolder(folderId: Long): List<Track> = withContext(IODispatcher) {
        db.libraryQueries.selectTracksByFolder(folderId).executeAsList().map { it.toDomain() }
    }

    suspend fun listAvailableTracks(limit: Long, offset: Long): List<Track> =
        withContext(IODispatcher) {
            db.libraryQueries.selectAvailableTracks(limit, offset).executeAsList()
                .map { it.toDomain() }
        }

    suspend fun newestTracks(limit: Long): List<Track> = withContext(IODispatcher) {
        db.libraryQueries.selectNewestTracks(limit).executeAsList().map { it.toDomain() }
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

    /**
     * Live track count. Used as a "library has changed" signal by
     * screens (Library Browser) that load track lists eagerly and
     * need to refresh when a scan adds rows. Counter-based rather
     * than direct list observation because the in-memory list of
     * 50k tracks is too heavy for a Flow without pagination.
     */
    fun observeTrackCount(): Flow<Long> =
        db.libraryQueries.countTracks()
            .asFlow()
            .map { it.executeAsOne() }
            .flowOn(IODispatcher)

    // ---- Track ↔ Artist ----

    /**
     * Atomically replaces the artist set for a track. Pass an ordered list — the
     * first entry's role defaults to "primary"; later entries default to "featured".
     * Caller can override roles by providing pairs of (artistId, role).
     */
    suspend fun setArtistsForTrack(trackId: Long, artistIdsAndRoles: List<Pair<Long, String>>) =
        withContext(IODispatcher) {
            db.transaction {
                db.libraryQueries.unlinkAllArtistsFromTrack(trackId)
                artistIdsAndRoles.forEachIndexed { index, (artistId, role) ->
                    db.libraryQueries.linkTrackArtist(trackId, artistId, role, index.toLong())
                }
            }
        }

    suspend fun linkTrackToArtist(
        trackId: Long,
        artistId: Long,
        role: String = "primary",
        position: Int = 0,
    ) = withContext(IODispatcher) {
        db.libraryQueries.linkTrackArtist(trackId, artistId, role, position.toLong())
    }

    suspend fun artistsForTrack(trackId: Long): List<Artist> = withContext(IODispatcher) {
        db.libraryQueries.selectArtistsByTrack(trackId).executeAsList().map { it.toDomain() }
    }

    suspend fun tracksForArtist(artistId: Long): List<Track> = withContext(IODispatcher) {
        db.libraryQueries.selectTracksByArtist(artistId).executeAsList().map { it.toDomain() }
    }

    /**
     * Batched (trackId → primaryArtistId) lookup. Returns only tracks
     * whose primary artist link exists; tracks without one (untagged
     * compilations, etc.) are absent from the map.
     */
    suspend fun primaryArtistMap(trackIds: Collection<Long>): Map<Long, Long> =
        withContext(IODispatcher) {
            if (trackIds.isEmpty()) return@withContext emptyMap()
            db.libraryQueries.selectPrimaryArtistForTracks(trackIds).executeAsList()
                .associate { it.track_id to it.artist_id }
        }

    suspend fun tracksForAlbum(albumId: Long): List<Track> = withContext(IODispatcher) {
        db.libraryQueries.selectTracksByAlbum(albumId).executeAsList().map { it.toDomain() }
    }

    suspend fun albumsForArtist(artistId: Long): List<Album> = withContext(IODispatcher) {
        db.libraryQueries.selectAlbumsByArtist(artistId).executeAsList().map { it.toDomain() }
    }

    suspend fun unlinkAllArtistsFromTrack(trackId: Long) = withContext(IODispatcher) {
        db.libraryQueries.unlinkAllArtistsFromTrack(trackId)
    }

    // ---- Tags ----

    /** Atomically replaces all tags for the given track. */
    suspend fun setTagsForTrack(trackId: Long, tags: List<Tag>) = withContext(IODispatcher) {
        db.transaction {
            db.libraryQueries.deleteAllTagsForTrack(trackId)
            tags.forEach { tag ->
                db.libraryQueries.insertTrackTag(trackId, tag.key, tag.value, tag.position.toLong())
            }
        }
    }

    suspend fun tagsForTrack(trackId: Long): List<Tag> = withContext(IODispatcher) {
        db.libraryQueries.selectTagsByTrack(trackId).executeAsList().map { it.toDomain() }
    }

    suspend fun tracksByTag(key: String, value: String): List<Track> = withContext(IODispatcher) {
        db.libraryQueries.selectTracksByTag(key, value).executeAsList().map { it.toDomain() }
    }

    suspend fun distinctTagValues(key: String): List<String> = withContext(IODispatcher) {
        db.libraryQueries.distinctTagValues(key).executeAsList()
    }

    // ---- Personnel ----

    /** Atomically replaces all personnel for the given track. */
    suspend fun setPersonnelForTrack(trackId: Long, personnel: List<Personnel>) =
        withContext(IODispatcher) {
            db.transaction {
                db.libraryQueries.deleteAllPersonnelForTrack(trackId)
                personnel.forEach { p ->
                    db.libraryQueries.insertTrackPersonnel(trackId, p.role, p.name, p.position.toLong())
                }
            }
        }

    suspend fun personnelForTrack(trackId: Long): List<Personnel> = withContext(IODispatcher) {
        db.libraryQueries.selectPersonnelByTrack(trackId).executeAsList().map { it.toDomain() }
    }
}
