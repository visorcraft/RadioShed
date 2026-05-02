package com.visorcraft.radioshed.scanner

import com.visorcraft.radioshed.data.FolderRepository
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.nowMillis
import com.visorcraft.radioshed.domain.Folder
import com.visorcraft.radioshed.source.ArtworkPersister
import com.visorcraft.radioshed.source.DiscoveredTrack
import com.visorcraft.radioshed.source.FileEnumerator
import com.visorcraft.radioshed.source.LocalFileSource
import com.visorcraft.radioshed.source.ParsedTags
import com.visorcraft.radioshed.source.TagReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Library scanner. Walks a folder via [FileEnumerator], reads tags via
 * [TagReader], and persists tracks/artists/albums via the repositories.
 * Emits [ScanProgress] events as a cold flow.
 *
 * Design:
 * - Each track is upserted by path; rescans are idempotent.
 * - Artists and albums are upserted by sort-keys, so re-runs don't dup them.
 * - Errors on individual files are surfaced as [ScanProgress.Warning];
 *   only setup-level failures (folder not registered) abort the scan.
 * - Progress is emitted every [PROGRESS_BATCH] tracks to avoid flooding
 *   the UI on huge libraries.
 */
class Scanner(
    private val folderRepo: FolderRepository,
    private val libraryRepo: LibraryRepository,
    private val fileEnumerator: FileEnumerator,
    private val tagReader: TagReader,
    private val artworkPersister: ArtworkPersister,
) {

    fun scanFolder(folderId: Long): Flow<ScanProgress> = flow {
        val folder = folderRepo.getById(folderId)
        if (folder == null) {
            emit(ScanProgress.Failed(folderId, "Folder $folderId is not registered"))
            return@flow
        }
        val started = nowMillis()
        emit(ScanProgress.Started(folderId))

        var processed = 0
        // Tracks album ids whose embedded art we've already persisted this
        // scan. Without this, every track of an album re-writes (no-op due
        // to content hashing) and re-UPDATEs the album row, which is just
        // wasted work — and on compilation albums where each track ships
        // its own art, we'd flicker between covers as the scan progresses.
        // First-seen wins.
        val albumsWithArtwork = mutableSetOf<Long>()
        val source = LocalFileSource(folder = folder, enumerator = fileEnumerator)

        source.listTracks().collect { discovered ->
            try {
                persistTrack(folder, discovered, albumsWithArtwork)
                processed += 1
                if (processed % PROGRESS_BATCH == 0) {
                    emit(ScanProgress.Progress(folderId, processed, discovered.fileName))
                }
            } catch (e: Throwable) {
                emit(
                    ScanProgress.Warning(
                        folderId = folderId,
                        message = "Failed: ${discovered.fileName} (${e.message ?: e::class.simpleName})",
                    ),
                )
            }
        }

        folderRepo.updateScanResult(
            id = folderId,
            lastScannedAt = nowMillis(),
            trackCount = processed.toLong(),
        )

        emit(
            ScanProgress.Completed(
                folderId = folderId,
                totalTracks = processed,
                durationMs = nowMillis() - started,
            ),
        )
    }

    private suspend fun persistTrack(
        folder: Folder,
        discovered: DiscoveredTrack,
        albumsWithArtwork: MutableSet<Long>,
    ) {
        val tags = tagReader.read(discovered.uri) ?: ParsedTags()

        // Upsert artist + album. Album-artist falls back to the track artist
        // when not separately tagged (very common in single-artist albums).
        val albumArtistName = tags.albumArtist ?: tags.artist
        val albumArtistId = albumArtistName?.takeIf { it.isNotBlank() }?.let {
            libraryRepo.upsertArtist(name = it, sortName = it.normalizeForSort())
        }
        val primaryArtistId = tags.artist?.takeIf { it.isNotBlank() }?.let {
            // Reuse the album-artist id when it's the same name to avoid an
            // unnecessary second row.
            if (it == albumArtistName) albumArtistId
            else libraryRepo.upsertArtist(name = it, sortName = it.normalizeForSort())
        }

        val albumId = tags.album?.takeIf { it.isNotBlank() }?.let {
            libraryRepo.upsertAlbum(
                title = it,
                sortTitle = it.normalizeForSort(),
                artistId = albumArtistId,
                year = tags.year,
                musicbrainzId = tags.musicbrainzAlbumId,
            )
        }

        // Persist embedded album art the first time we see it for each album
        // in this scan. ArtworkPersister is content-addressed (SHA-256), so
        // duplicate bytes across tracks share a single on-disk file even
        // across scans.
        if (albumId != null && albumId !in albumsWithArtwork) {
            tags.artworkBytes?.let { bytes ->
                artworkPersister.persist(bytes)?.let { uri ->
                    libraryRepo.setAlbumArtwork(albumId, uri)
                }
            }
            albumsWithArtwork += albumId
        }

        val title = tags.title
            ?.takeIf { it.isNotBlank() }
            ?: discovered.fileName.substringBeforeLast('.', discovered.fileName)

        // Build artist links + tags + personnel up-front so the
        // upsertTrack call can apply them inside the same transaction.
        // Without atomicity, a play recorded between the track upsert
        // and the (separate) setArtistsForTrack call would have no
        // 'primary' artist link and be silently excluded from
        // selectTopPlayedArtists forever.
        val artistLinks = buildList {
            primaryArtistId?.let { add(it to "primary") }
            // Album artist gets linked too if it's a different artist than the track artist.
            if (albumArtistId != null && albumArtistId != primaryArtistId) {
                add(albumArtistId to "album_artist")
            }
        }

        // Normalize GENRE tag values to title case at scan time so
        // the Genres tab consolidates "Rock" / "rock" / "ROCK" into
        // a single tile. Other tag keys are left untouched — only
        // GENRE is user-facing in a way that surfaces case duplicates.
        val normalizedTags = tags.customTags.map { tag ->
            if (tag.key == "GENRE") tag.copy(value = tag.value.canonicalizeGenre()) else tag
        }

        libraryRepo.upsertTrack(
            folderId = folder.id,
            path = discovered.uri,
            fileName = discovered.fileName,
            fileSize = discovered.fileSize,
            fileCtime = discovered.fileCtime,
            fileMtime = discovered.fileMtime,
            title = title,
            albumId = albumId,
            trackNumber = tags.trackNumber,
            discNumber = tags.discNumber,
            durationMs = tags.durationMs,
            bitrateKbps = tags.bitrateKbps,
            sampleRateHz = tags.sampleRateHz,
            channels = tags.channels,
            format = discovered.format,
            replayGainTrack = tags.replayGainTrack,
            replayGainAlbum = tags.replayGainAlbum,
            replayGainTrackPeak = tags.replayGainTrackPeak,
            bpm = tags.bpm,
            musicbrainzId = tags.musicbrainzTrackId,
            artistIdsAndRoles = artistLinks.takeIf { it.isNotEmpty() },
            customTags = normalizedTags.takeIf { it.isNotEmpty() },
            personnel = tags.personnel.takeIf { it.isNotEmpty() },
        )
    }

    companion object {
        /** How often to emit [ScanProgress.Progress] events. Tuned for
         *  large libraries — too small floods the UI, too large makes
         *  progress feel stuck. */
        private const val PROGRESS_BATCH = 25
    }
}

/**
 * Cheap normalization for sort-key columns: lowercase + trim. Future work
 * could fold diacritics and strip leading "The "; for v1, lowercasing covers
 * 95% of the practical "same artist" cases.
 */
internal fun String.normalizeForSort(): String = trim().lowercase()

/**
 * Title-case a genre tag value so "Rock", "rock", "ROCK" all collapse
 * to the same "Rock" entry. Word-by-word capitalization keeps
 * compound genres readable ("Synth Pop" not "Synth pop"). Ignores
 * hyphens by treating each space-separated word independently —
 * "post-rock" stays "Post-rock" rather than "Post-Rock", which is
 * an acceptable v1 simplification.
 */
internal fun String.canonicalizeGenre(): String {
    val trimmed = trim()
    if (trimmed.isEmpty()) return trimmed
    return trimmed.split(' ').joinToString(" ") { word ->
        if (word.isEmpty()) word
        else word[0].uppercaseChar() + word.substring(1).lowercase()
    }
}
