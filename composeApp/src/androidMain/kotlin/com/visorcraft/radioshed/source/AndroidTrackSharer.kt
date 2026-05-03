package com.visorcraft.radioshed.source

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android impl of [TrackSharer]. Wraps the audio file as a
 * `content://` URI via FileProvider and launches an
 * `ACTION_SEND` chooser. The MIME type is sniffed from the file
 * extension since Intent receivers (messaging apps especially) use
 * MIME to filter their share targets — passing the wildcard audio
 * MIME is a fallback when we can't determine the specific subtype.
 */
class AndroidTrackSharer(private val context: Context) : TrackSharer {

    override suspend fun share(
        absolutePath: String,
        displayName: String,
    ): Boolean = withContext(IODispatcher) {
        runCatching {
            val file = File(absolutePath).takeIf { it.exists() && it.canRead() }
                ?: return@runCatching false
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = guessMime(file.extension.lowercase())
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, displayName)
                putExtra(Intent.EXTRA_TITLE, displayName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Share track").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }

    private fun guessMime(ext: String): String = when (ext) {
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "wma" -> "audio/x-ms-wma"
        else -> "audio/*"
    }
}
