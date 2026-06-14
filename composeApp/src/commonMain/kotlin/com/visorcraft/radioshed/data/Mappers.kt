package com.visorcraft.radioshed.data

import com.visorcraft.radioshed.domain.Album
import com.visorcraft.radioshed.domain.Artist
import com.visorcraft.radioshed.domain.Folder
import com.visorcraft.radioshed.domain.Personnel
import com.visorcraft.radioshed.domain.Playlist
import com.visorcraft.radioshed.domain.Rating
import com.visorcraft.radioshed.domain.Tag
import com.visorcraft.radioshed.domain.Track
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import com.visorcraft.radioshed.db.Album as AlbumRow
import com.visorcraft.radioshed.db.Artist as ArtistRow
import com.visorcraft.radioshed.db.Folder as FolderRow
import com.visorcraft.radioshed.db.Playlist as PlaylistRow
import com.visorcraft.radioshed.db.Rating as RatingRow
import com.visorcraft.radioshed.db.Track as TrackRow
import com.visorcraft.radioshed.db.Track_personnel as TrackPersonnelRow
import com.visorcraft.radioshed.db.Track_tag as TrackTagRow

/**
 * DB-row → domain-model conversions. Kept in one file so we don't drift between
 * repositories that all need to project the same shapes.
 */

internal fun TrackRow.toDomain(): Track =
    Track(
        id = id,
        path = path,
        fileName = file_name,
        fileSize = file_size,
        fileCtime = file_ctime,
        fileMtime = file_mtime,
        title = title,
        albumId = album_id,
        trackNumber = track_number?.toInt(),
        discNumber = disc_number?.toInt(),
        durationMs = duration_ms,
        bitrateKbps = bitrate_kbps?.toInt(),
        sampleRateHz = sample_rate_hz?.toInt(),
        channels = channels?.toInt(),
        format = format,
        replayGainTrack = replay_gain_track,
        replayGainAlbum = replay_gain_album,
        replayGainTrackPeak = replay_gain_track_peak,
        bpm = bpm,
        musicbrainzId = musicbrainz_id,
        acoustidFingerprint = acoustid_fingerprint,
        cueOffsetStartMs = cue_offset_start_ms,
        cueOffsetEndMs = cue_offset_end_ms,
        isAvailable = is_available == 1L,
        addedAt = added_at,
        updatedAt = updated_at,
    )

internal fun ArtistRow.toDomain(): Artist =
    Artist(
        id = id,
        name = name,
        sortName = sort_name,
        musicbrainzId = musicbrainz_id,
        imageUri = image_uri,
        bio = bio,
        createdAt = created_at,
        updatedAt = updated_at,
    )

internal fun AlbumRow.toDomain(): Album =
    Album(
        id = id,
        title = title,
        artistId = artist_id,
        sortTitle = sort_title,
        year = year?.toInt(),
        musicbrainzId = musicbrainz_id,
        artworkUri = artwork_uri,
        trackCount = track_count,
        createdAt = created_at,
        updatedAt = updated_at,
    )

internal fun FolderRow.toDomain(): Folder =
    Folder(
        id = id,
        displayName = display_name,
        uri = uri,
        recursive = recursive == 1L,
        includeGlobs = decodeStringList(include_globs),
        excludeGlobs = decodeStringList(exclude_globs),
        lastScannedAt = last_scanned_at,
        trackCount = track_count,
        isAvailable = is_available == 1L,
        accentColor = accent_color?.toInt(),
        createdAt = created_at,
        updatedAt = updated_at,
    )

internal fun PlaylistRow.toDomain(): Playlist =
    Playlist(
        id = id,
        name = name,
        description = description,
        artworkUri = artwork_uri,
        createdAt = created_at,
        updatedAt = updated_at,
    )

internal fun RatingRow.toDomain(): Rating = Rating(trackId = track_id, stars = stars, ratedAt = rated_at)

internal fun TrackTagRow.toDomain(): Tag = Tag(key = key, value = value_, position = position.toInt())

internal fun TrackPersonnelRow.toDomain(): Personnel = Personnel(role = role, name = name, position = position.toInt())

// ---- shared list-of-string JSON helpers (used for include_globs etc.) ----

internal fun encodeStringList(list: List<String>): String? =
    if (list.isEmpty()) {
        null
    } else {
        RadioShedJson.encodeToString(ListSerializer(String.serializer()), list)
    }

internal fun decodeStringList(json: String?): List<String> =
    if (json.isNullOrBlank()) {
        emptyList()
    } else {
        RadioShedJson.decodeFromString(ListSerializer(String.serializer()), json)
    }
