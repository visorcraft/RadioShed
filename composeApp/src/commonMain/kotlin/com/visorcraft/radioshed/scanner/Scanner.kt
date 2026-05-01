package com.visorcraft.radioshed.scanner

import com.visorcraft.radioshed.data.FolderRepository
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.nowMillis
import com.visorcraft.radioshed.domain.Folder
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
        val source = LocalFileSource(folder = folder, enumerator = fileEnumerator)

        source.listTracks().collect { discovered ->
            try {
                persistTrack(folder, discovered)
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

    private suspend fun persistTrack(folder: Folder, discovered: DiscoveredTrack) {
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

        val title = tags.title
            ?.takeIf { it.isNotBlank() }
            ?: discovered.fileName.substringBeforeLast('.', discovered.fileName)

        val trackId = libraryRepo.upsertTrack(
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
        )

        // Wire up junctions / multi-value tags / personnel atomically.
        val artistLinks = buildList {
            primaryArtistId?.let { add(it to "primary") }
            // Album artist gets linked too if it's a different artist than the track artist.
            if (albumArtistId != null && albumArtistId != primaryArtistId) {
                add(albumArtistId to "album_artist")
            }
        }
        if (artistLinks.isNotEmpty()) libraryRepo.setArtistsForTrack(trackId, artistLinks)
        if (tags.customTags.isNotEmpty()) libraryRepo.setTagsForTrack(trackId, tags.customTags)
        if (tags.personnel.isNotEmpty()) libraryRepo.setPersonnelForTrack(trackId, tags.personnel)
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
