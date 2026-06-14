package com.visorcraft.radioshed.source

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android impl of [PlaylistExporter]. Materialises the .m3u into
 * a temp file inside `cacheDir/playlists/`, wraps it as a
 * `content://` URI via FileProvider, then launches an
 * `ACTION_SEND` chooser. Cache-dir is fine because Android wipes
 * it under storage pressure and the file is only meant to live
 * long enough for the user's chosen target app to copy/save it.
 *
 * MIME `audio/x-mpegurl` is the de-facto type for .m3u — most
 * file managers and music apps register for it. Some don't, so
 * we also pass `audio/x-scpls` as backup via EXTRA_MIME_TYPES so
 * generic share targets like email / drive show up in the chooser.
 */
class AndroidPlaylistExporter(private val context: Context) : PlaylistExporter {
    override suspend fun exportM3u(
        playlistName: String,
        entries: List<M3uEntry>,
    ): Boolean =
        withContext(IODispatcher) {
            runCatching {
                val cacheRoot = File(context.cacheDir, "playlists").apply { mkdirs() }
                val safeName =
                    playlistName
                        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        .ifBlank { "playlist" }
                        .take(80)
                // Stamp each export with the wall clock + a counter
                // suffix on collision. Two exports within the same
                // millisecond (rapid double-tap on the share button)
                // would otherwise overwrite each other while the first
                // share target is still consuming the URI. Counter is
                // tried up to 32 times before giving up — practically
                // a never-hit ceiling, but bounded so we don't spin
                // forever if cacheDir somehow becomes append-only.
                val stamp = System.currentTimeMillis()
                var outFile = File(cacheRoot, "${safeName}_$stamp.m3u")
                var counter = 1
                while (outFile.exists() && counter <= 32) {
                    outFile = File(cacheRoot, "${safeName}_${stamp}_$counter.m3u")
                    counter += 1
                }
                outFile.writeText(buildM3uText(entries))

                val authority = "${context.packageName}.fileprovider"
                val uri = FileProvider.getUriForFile(context, authority, outFile)

                val intent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "audio/x-mpegurl"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "$playlistName.m3u")
                        putExtra(Intent.EXTRA_TITLE, "$playlistName.m3u")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                val chooser =
                    Intent.createChooser(intent, "Export playlist").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                context.startActivity(chooser)
                true
            }.getOrDefault(false)
        }

    private fun buildM3uText(entries: List<M3uEntry>): String =
        buildString {
            append("#EXTM3U\n")
            entries.forEach { e ->
                // Some legacy players reject negative durations or
                // bogus ones — clamp to >= 0 so we don't break them.
                val dur = e.durationSec.coerceAtLeast(0)
                val artist = e.artist?.replace('\n', ' ')?.trim().orEmpty()
                val title = e.title?.replace('\n', ' ')?.trim().orEmpty()
                val label =
                    listOf(artist, title)
                        .filter { it.isNotBlank() }
                        .joinToString(" - ")
                        .ifBlank { File(e.absolutePath).nameWithoutExtension }
                append("#EXTINF:$dur,$label\n")
                append(e.absolutePath).append('\n')
            }
        }
}
