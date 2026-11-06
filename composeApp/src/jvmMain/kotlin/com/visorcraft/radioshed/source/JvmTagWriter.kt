package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/** Shared JVM tag writer for Android and desktop (jaudiotagger in-place commit). */
class JvmTagWriter : TagWriter {
    init {
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
