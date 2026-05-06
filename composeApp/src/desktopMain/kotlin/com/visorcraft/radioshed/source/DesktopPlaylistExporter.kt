package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Desktop [PlaylistExporter]. AWT save dialog → write `.m3u` to the
 * user's chosen location. Same M3U format as
 * [AndroidPlaylistExporter] (#EXTM3U header + #EXTINF lines + raw
 * absolute paths) so a playlist exported on either platform plays
 * correctly in Foobar2000 / VLC / MusicBee.
 *
 * Why a save dialog instead of the Android share-sheet pattern:
 * desktops don't really have a share-sheet equivalent — the
 * user's natural mental model for "give me this file" is "save
 * it somewhere I can find it later". Cross-platform abstraction
 * accommodates both.
 */
class DesktopPlaylistExporter : PlaylistExporter {

    override suspend fun exportM3u(
        playlistName: String,
        entries: List<M3uEntry>,
    ): Boolean = withContext(IODispatcher) {
        runCatching {
            val safeName = playlistName
                .replace(Regex("""[\\/:*?"<>|]"""), "_")
                .ifBlank { "playlist" }
                .take(80)
            val dialog = FileDialog(null as Frame?, "Export playlist", FileDialog.SAVE)
            dialog.file = "$safeName.m3u"
            dialog.isVisible = true
            val name = dialog.file ?: return@runCatching false
            val dir = dialog.directory ?: return@runCatching false
            val target = File(dir, name)
            target.writeText(buildM3uText(entries))
            true
        }.getOrDefault(false)
    }

    private fun buildM3uText(entries: List<M3uEntry>): String = buildString {
        append("#EXTM3U\n")
        entries.forEach { e ->
            val dur = e.durationSec.coerceAtLeast(0)
            val artist = e.artist?.replace('\n', ' ')?.trim().orEmpty()
            val title = e.title?.replace('\n', ' ')?.trim().orEmpty()
            val label = listOf(artist, title)
                .filter { it.isNotBlank() }
                .joinToString(" - ")
                .ifBlank { File(e.absolutePath).nameWithoutExtension }
            append("#EXTINF:$dur,$label\n")
            append(e.absolutePath).append('\n')
        }
    }
}
