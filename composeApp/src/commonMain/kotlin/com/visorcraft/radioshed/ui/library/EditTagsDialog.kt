package com.visorcraft.radioshed.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.data.FolderRepository
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.scanner.Scanner
import com.visorcraft.radioshed.source.TagPatch
import com.visorcraft.radioshed.source.TagWriter
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Single-track tag editor. Fields: title, artist, album, album artist,
 * genre, year, track number, disc number. Writes via [TagWriter] to
 * the actual file on disk; on success, kicks a re-scan of the track's
 * parent folder so the library DB picks up the new metadata.
 *
 * What's intentionally NOT here:
 *  - Multi-value tags (multiple artists, multiple genres). The library
 *    schema collapses to primary on the track row, so a multi-edit
 *    workflow that round-trips through the existing scanner pipeline
 *    silently drops everything past the first value. Punted to a
 *    deferred "advanced tag editor".
 *  - Custom TXXX / Vorbis / iTunes atoms. Same reason.
 *  - Lyrics. The lyrics-cache + USLT/SYLT round-trip is its own
 *    can of worms; lyrics are read-only from the editor's
 *    perspective. (Lyrics search via tag-fix exists separately.)
 *  - Replay-gain. Auto-derived during scan; manual override is a
 *    separate feature.
 *
 * Failure handling: write errors surface as a status banner inside
 * the dialog so the user can decide whether to retry. Common cases —
 * read-only mounts, format-unsupported — are explicit; everything
 * else surfaces the underlying exception text. Successful writes
 * trigger an asynchronous folder rescan; the dialog dismisses
 * immediately rather than blocking on the rescan to finish.
 */
@Composable
fun EditTagsDialog(
    track: Track,
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {},
    tagWriter: TagWriter = koinInject(),
    folderRepo: FolderRepository = koinInject(),
    scanner: Scanner = koinInject(),
    libraryRepo: LibraryRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(track.title) }
    // Pre-fill artist / album by looking up the joined rows. We can't
    // populate from the Track domain object since it doesn't carry
    // the resolved string — only ids.
    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var albumArtist by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var trackNumber by remember { mutableStateOf(track.trackNumber?.toString() ?: "") }
    var discNumber by remember { mutableStateOf(track.discNumber?.toString() ?: "") }
    var status by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var initialised by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(track.id) {
        // First-load population. Uses the resolved-to-domain tracks
        // helpers; runs on a coroutine so the dialog renders the
        // disabled fields immediately and then fills in.
        runCatching {
            val artistRow = libraryRepo.artistsForTrack(track.id).firstOrNull()
            artist = artistRow?.name ?: ""
            val albumRow = track.albumId?.let { libraryRepo.getAlbumById(it) }
            album = albumRow?.title ?: ""
            year = albumRow?.year?.toString() ?: ""
            val albumArtistId = albumRow?.artistId
            if (albumArtistId != null && albumArtistId != artistRow?.id) {
                albumArtist = libraryRepo.getArtistById(albumArtistId)?.name ?: ""
            }
            genre = libraryRepo.tagsForTrack(track.id)
                .firstOrNull { it.key.equals("GENRE", ignoreCase = true) }
                ?.value ?: ""
        }
        initialised = true
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        confirmButton = {
            Button(
                enabled = !saving && initialised,
                onClick = {
                    saving = true
                    status = "Writing tags…"
                    scope.launch {
                        val patch = TagPatch(
                            title = TagPatch.Update(title),
                            artist = artist.takeIf { it.isNotBlank() }
                                ?.let { TagPatch.Update(it) },
                            album = album.takeIf { it.isNotBlank() }
                                ?.let { TagPatch.Update(it) },
                            albumArtist = albumArtist.takeIf { it.isNotBlank() }
                                ?.let { TagPatch.Update(it) },
                            genre = genre.takeIf { it.isNotBlank() }
                                ?.let { TagPatch.Update(it) },
                            year = year.toIntOrNull()?.let { TagPatch.Update(it) },
                            trackNumber = trackNumber.toIntOrNull()?.let { TagPatch.Update(it) },
                            discNumber = discNumber.toIntOrNull()?.let { TagPatch.Update(it) },
                        )
                        val writeResult = tagWriter.write(track.path, patch)
                        if (writeResult.isFailure) {
                            status = writeResult.exceptionOrNull()?.message
                                ?: "Tag write failed."
                            saving = false
                            return@launch
                        }
                        // Find the folder this file belongs to so we
                        // can fire a rescan to refresh the library
                        // DB. Path-prefix match against registered
                        // folders; pick the longest prefix to handle
                        // nested registrations (e.g. /storage/Music
                        // and /storage/Music/Albums both registered).
                        val folder = folderRepo.list()
                            .filter { track.path.startsWith(it.uri) }
                            .maxByOrNull { it.uri.length }
                        if (folder != null) {
                            status = "Rescanning folder…"
                            scanner.scanFolder(folder.id).collect { /* drain */ }
                        }
                        status = "Saved."
                        saving = false
                        onSaved()
                        onDismiss()
                    }
                },
            ) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text("Edit tags") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("Album") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = albumArtist,
                    onValueChange = { albumArtist = it },
                    label = { Text("Album artist (optional)") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = genre,
                    onValueChange = { genre = it },
                    label = { Text("Genre") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = year,
                    onValueChange = { year = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("Year") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = trackNumber,
                    onValueChange = {
                        trackNumber = it.filter { c -> c.isDigit() }.take(3)
                    },
                    label = { Text("Track #") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = discNumber,
                    onValueChange = {
                        discNumber = it.filter { c -> c.isDigit() }.take(2)
                    },
                    label = { Text("Disc #") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Writes are committed directly to the file. The library is rescanned automatically afterwards.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        },
    )
}
