package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * jaudiotagger-backed tag writer. Mutates ID3v2 / Vorbis / MP4 atoms
 * in-place. Read counterpart is [AndroidTagReader].
 *
 * Failure modes the user will hit in the wild:
 *  - File on a read-only mount (SD card before MEMS, USB mass storage):
 *    `canWrite()` returns false; we return failure with that diagnosis.
 *  - Format jaudiotagger doesn't write to (a handful of niche formats):
 *    surface the underlying exception text.
 *  - File missing on disk: filtered out before the write.
 *
 * jaudiotagger writes a temp file alongside the target then renames on
 * success, so a partial write doesn't corrupt the original — a power
 * loss mid-write leaves either the original or the new file, never a
 * truncated one.
 */
class AndroidTagWriter : TagWriter {
    init {
        // jaudiotagger logs at INFO during every commit. Mute it.
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    override suspend fun write(
        uri: String,
        patch: TagPatch,
    ): Result<Unit> =
        withContext(IODispatcher) {
            val file = File(uri)
            if (!file.exists()) {
                return@withContext Result.failure(
                    IllegalArgumentException("File not found: $uri"),
                )
            }
            if (!file.canWrite()) {
                return@withContext Result.failure(
                    IllegalStateException("File is not writable (read-only mount?): $uri"),
                )
            }
            runCatching {
                val audioFile = AudioFileIO.read(file)
                val tag = audioFile.tagOrCreateAndSetDefault
                patch.title?.let { tag.setField(FieldKey.TITLE, it.value) }
                patch.artist?.let { tag.setField(FieldKey.ARTIST, it.value) }
                patch.album?.let { tag.setField(FieldKey.ALBUM, it.value) }
                patch.albumArtist?.let { tag.setField(FieldKey.ALBUM_ARTIST, it.value) }
                patch.genre?.let { tag.setField(FieldKey.GENRE, it.value) }
                patch.year?.let { tag.setField(FieldKey.YEAR, it.value.toString()) }
                patch.trackNumber?.let { tag.setField(FieldKey.TRACK, it.value.toString()) }
                patch.discNumber?.let { tag.setField(FieldKey.DISC_NO, it.value.toString()) }
                audioFile.commit()
            }.fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { Result.failure(it) },
            )
        }
}
