package com.visorcraft.radioshed.source

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android impl of [RingtoneSetter]. Inserts the audio file as a
 * ringtone via MediaStore (so the system Files-by-Google audio
 * picker can also see it under Ringtones), then promotes to the
 * default ringtone via [RingtoneManager].
 *
 * On API 30+ the file must already live under public storage that
 * MediaStore can index — for files outside of that the insert
 * silently no-ops. Our music tracks under
 * /storage/emulated/0/Music are already MediaStore-visible so this
 * works in practice; the rare power-user with files under app-
 * private storage would need to copy them first (deferred).
 */
class AndroidRingtoneSetter(private val context: Context) : RingtoneSetter {

    override suspend fun setAsRingtone(
        absolutePath: String,
        displayName: String,
    ): RingtoneSetter.Result = withContext(IODispatcher) {
        if (!Settings.System.canWrite(context)) {
            // Launch the system-Settings screen for the user to grant
            // WRITE_SETTINGS. We can't pre-grant programmatically
            // — Android requires a manual flip in this specific UI.
            runCatching {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            return@withContext RingtoneSetter.Result.NeedsPermission
        }
        val file = File(absolutePath)
        if (!file.exists()) {
            return@withContext RingtoneSetter.Result.Failed("File not found")
        }
        val externalRoot = Environment.getExternalStorageDirectory().absolutePath
        if (!file.absolutePath.startsWith(externalRoot)) {
            return@withContext RingtoneSetter.Result.Failed(
                "File isn't under public storage — copy it to /Music first.",
            )
        }
        val mime = guessMime(file.extension.lowercase())

        runCatching {
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val resolver = context.contentResolver

            // Look up an existing MediaStore row by path so we can
            // promote in place rather than inserting a duplicate.
            // The DATA column query is deprecated on API 30+ but
            // still works for matching against indexed audio files.
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DATA} = ?"
            val args = arrayOf(file.absolutePath)
            val existing = resolver.query(collection, projection, selection, args, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
            val uri = if (existing != null) {
                val u = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    .buildUpon().appendPath(existing.toString()).build()
                resolver.update(
                    u,
                    ContentValues().apply { put(MediaStore.Audio.Media.IS_RINGTONE, 1) },
                    null,
                    null,
                )
                u
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DATA, file.absolutePath)
                    put(MediaStore.MediaColumns.TITLE, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.Audio.Media.IS_RINGTONE, 1)
                    put(MediaStore.Audio.Media.IS_NOTIFICATION, 0)
                    put(MediaStore.Audio.Media.IS_ALARM, 0)
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                }
                resolver.insert(collection, values)
                    ?: return@runCatching RingtoneSetter.Result.Failed("MediaStore insert failed")
            }

            RingtoneManager.setActualDefaultRingtoneUri(
                context,
                RingtoneManager.TYPE_RINGTONE,
                uri,
            )
            RingtoneSetter.Result.Ok
        }.getOrElse {
            RingtoneSetter.Result.Failed(it.message ?: "Unknown error")
        }
    }

    private fun guessMime(ext: String): String = when (ext) {
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "audio/*"
    }
}
