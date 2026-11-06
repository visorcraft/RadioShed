package com.visorcraft.radioshed.source

/**
 * Writes a playlist as a `.m3u` text file and hands it to the
 * platform share sheet so the user can drop it into Foobar2000,
 * VLC, MusicBee, or pipe it through a sync tool. M3U is plain
 * text, one absolute path per line, plus an `#EXTINF:duration,
 * artist - title` header per entry — simple enough to read in any
 * editor and universally understood by external players.
 *
 * Why share-sheet (not a Save-As dialog): the same intent flow
 * already powers track sharing and backup sharing; users move
 * files via "share" on Android, not via OS file save dialogs.
 *
 * iOS impl is a Null stub — UIActivityViewController is the
 * equivalent but needs a Mac to build / test.
 */
interface PlaylistExporter {
    /** Returns true when the share sheet was successfully launched. */
    suspend fun exportM3u(
        playlistName: String,
        entries: List<M3uEntry>,
    ): Boolean
}

/**
 * One row in an .m3u export. [absolutePath] is what foreign
 * players will resolve; [durationSec] / [artist] / [title] go
 * into the `#EXTINF` header which most players surface as the
 * row label. Keep the artist + title fields ASCII-friendly when
 * possible — older players choke on UTF-8 BOM-less M3U so we
 * write the file without BOM but accept that some legacy
 * players won't render non-ASCII metadata.
 */
data class M3uEntry(
    val absolutePath: String,
    val durationSec: Int,
    val artist: String?,
    val title: String?,
)
