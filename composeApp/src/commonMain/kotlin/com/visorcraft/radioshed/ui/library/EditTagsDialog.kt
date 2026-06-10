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
import androidx.compose.runtime.LaunchedEffect
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

/**
 * Batch tag editor for multiple tracks. Loads per-track snapshots,
 * merges them into a [MergedTagView], and writes only the fields the
 * user actually touched.
 */
@Composable
fun BatchEditTagsDialog(
    tracks: List<Track>,
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {},
    tagWriter: TagWriter = koinInject(),
    folderRepo: FolderRepository = koinInject(),
    scanner: Scanner = koinInject(),
    libraryRepo: LibraryRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    var merged by remember { mutableStateOf<MergedTagView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var albumArtist by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var trackNumber by remember { mutableStateOf("") }
    var discNumber by remember { mutableStateOf("") }

    var touchedFields by remember { mutableStateOf<Set<TagField>>(emptySet()) }

    LaunchedEffect(Unit) {
        runCatching {
            val snapshots = tracks.map { track ->
                val artistRow = libraryRepo.artistsForTrack(track.id).firstOrNull()
                val albumRow = track.albumId?.let { libraryRepo.getAlbumById(it) }
                val albumArtistId = albumRow?.artistId
                val albumArtistName = if (albumArtistId != null && albumArtistId != artistRow?.id) {
                    libraryRepo.getArtistById(albumArtistId)?.name ?: ""
                } else {
                    artistRow?.name ?: ""
                }
                val genreTag = libraryRepo.tagsForTrack(track.id)
                    .firstOrNull { it.key.equals("GENRE", ignoreCase = true) }
                    ?.value ?: ""

                TrackTagSnapshot(
                    title = track.title,
                    artist = artistRow?.name ?: "",
                    album = albumRow?.title ?: "",
                    albumArtist = albumArtistName,
                    genre = genreTag,
                    year = albumRow?.year?.toString() ?: "",
                    trackNumber = track.trackNumber?.toString() ?: "",
                    discNumber = track.discNumber?.toString() ?: "",
                )
            }
            val m = BatchTagMerge.merge(snapshots)
            merged = m

            title = (m.title as? MergedValue.Uniform)?.value ?: ""
            artist = (m.artist as? MergedValue.Uniform)?.value ?: ""
            album = (m.album as? MergedValue.Uniform)?.value ?: ""
            albumArtist = (m.albumArtist as? MergedValue.Uniform)?.value ?: ""
            genre = (m.genre as? MergedValue.Uniform)?.value ?: ""
            year = (m.year as? MergedValue.Uniform)?.value ?: ""
            trackNumber = (m.trackNumber as? MergedValue.Uniform)?.value ?: ""
            discNumber = (m.discNumber as? MergedValue.Uniform)?.value ?: ""
        }
        loading = false
    }

    fun markTouched(field: TagField) {
        touchedFields = touchedFields + field
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        confirmButton = {
            Button(
                enabled = !saving && !loading && touchedFields.isNotEmpty(),
                onClick = {
                    saving = true
                    status = "Writing tags…"
                    scope.launch {
                        val m = merged ?: return@launch
                        val edits = buildList {
                            if (TagField.Title in touchedFields) add(BatchTagEdit(TagField.Title, title))
                            if (TagField.Artist in touchedFields) add(BatchTagEdit(TagField.Artist, artist))
                            if (TagField.Album in touchedFields) add(BatchTagEdit(TagField.Album, album))
                            if (TagField.AlbumArtist in touchedFields) add(BatchTagEdit(TagField.AlbumArtist, albumArtist))
                            if (TagField.Genre in touchedFields) add(BatchTagEdit(TagField.Genre, genre))
                            if (TagField.Year in touchedFields) add(BatchTagEdit(TagField.Year, year))
                            if (TagField.TrackNumber in touchedFields) add(BatchTagEdit(TagField.TrackNumber, trackNumber))
                            if (TagField.DiscNumber in touchedFields) add(BatchTagEdit(TagField.DiscNumber, discNumber))
                        }
                        val patch = BatchTagMerge.buildPatch(edits, m)
                        var successes = 0
                        var failures = 0
                        tracks.forEach { track ->
                            val result = tagWriter.write(track.path, patch)
                            if (result.isSuccess) {
                                successes++
                            } else {
                                failures++
                            }
                        }
                        status = "$successes written, $failures failed"

                        if (failures > 0) {
                            saving = false
                            return@launch
                        }

                        val allFolders = folderRepo.list()
                        val affectedFolderIds = tracks.mapNotNull { track ->
                            allFolders
                                .filter { track.path.startsWith(it.uri) }
                                .maxByOrNull { it.uri.length }
                                ?.id
                        }.distinct()
                        affectedFolderIds.forEach { folderId ->
                            runCatching { scanner.scanFolder(folderId).collect { } }
                        }

                        onSaved()
                        onDismiss()
                    }
                },
            ) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text("Edit tags (${tracks.size} tracks)") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (loading) {
                    Text("Loading…", style = MaterialTheme.typography.bodySmall)
                } else {
                    status?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    fun placeholderFor(field: TagField): String {
                        val m = merged ?: return ""
                        val value = when (field) {
                            TagField.Title -> m.title
                            TagField.Artist -> m.artist
                            TagField.Album -> m.album
                            TagField.AlbumArtist -> m.albumArtist
                            TagField.Genre -> m.genre
                            TagField.Year -> m.year
                            TagField.TrackNumber -> m.trackNumber
                            TagField.DiscNumber -> m.discNumber
                        }
                        return when (value) {
                            is MergedValue.Uniform -> ""
                            MergedValue.Mixed -> "Multiple values"
                            MergedValue.Empty -> "Empty"
                        }
                    }

                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it; markTouched(TagField.Title) },
                        label = { Text("Title") },
                        placeholder = { Text(placeholderFor(TagField.Title)) },
                        singleLine = true,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = artist,
                        onValueChange = { artist = it; markTouched(TagField.Artist) },
                        label = { Text("Artist") },
                        placeholder = { Text(placeholderFor(TagField.Artist)) },
                        singleLine = true,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = album,
                        onValueChange = { album = it; markTouched(TagField.Album) },
                        label = { Text("Album") },
                        placeholder = { Text(placeholderFor(TagField.Album)) },
                        singleLine = true,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = albumArtist,
                        onValueChange = { albumArtist = it; markTouched(TagField.AlbumArtist) },
                        label = { Text("Album artist (optional)") },
                        placeholder = { Text(placeholderFor(TagField.AlbumArtist)) },
                        singleLine = true,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = genre,
                        onValueChange = { genre = it; markTouched(TagField.Genre) },
                        label = { Text("Genre") },
                        placeholder = { Text(placeholderFor(TagField.Genre)) },
                        singleLine = true,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = year,
                        onValueChange = {
                            year = it.filter { c -> c.isDigit() }.take(4)
                            markTouched(TagField.Year)
                        },
                        label = { Text("Year") },
                        placeholder = { Text(placeholderFor(TagField.Year)) },
                        singleLine = true,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = trackNumber,
                        onValueChange = {
                            trackNumber = it.filter { c -> c.isDigit() }.take(3)
                            markTouched(TagField.TrackNumber)
                        },
                        label = { Text("Track #") },
                        placeholder = { Text(placeholderFor(TagField.TrackNumber)) },
                        singleLine = true,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = discNumber,
                        onValueChange = {
                            discNumber = it.filter { c -> c.isDigit() }.take(2)
                            markTouched(TagField.DiscNumber)
                        },
                        label = { Text("Disc #") },
                        placeholder = { Text(placeholderFor(TagField.DiscNumber)) },
                        singleLine = true,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Writes are committed directly to each file. The library is rescanned automatically afterwards.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        },
    )
}

// ---- Batch tag merge model (helpers-in-file convention) ----

enum class TagField {
    Title,
    Artist,
    Album,
    AlbumArtist,
    Genre,
    Year,
    TrackNumber,
    DiscNumber,
}

data class BatchTagEdit(val field: TagField, val newValue: String)

data class TrackTagSnapshot(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val year: String,
    val trackNumber: String,
    val discNumber: String,
)

sealed class MergedValue {
    data class Uniform(val value: String) : MergedValue()
    data object Mixed : MergedValue()
    data object Empty : MergedValue()
}

data class MergedTagView(
    val title: MergedValue,
    val artist: MergedValue,
    val album: MergedValue,
    val albumArtist: MergedValue,
    val genre: MergedValue,
    val year: MergedValue,
    val trackNumber: MergedValue,
    val discNumber: MergedValue,
)

object BatchTagMerge {

    fun merge(tracks: List<TrackTagSnapshot>): MergedTagView = MergedTagView(
        title = mergeField(tracks.map { it.title }),
        artist = mergeField(tracks.map { it.artist }),
        album = mergeField(tracks.map { it.album }),
        albumArtist = mergeField(tracks.map { it.albumArtist }),
        genre = mergeField(tracks.map { it.genre }),
        year = mergeField(tracks.map { it.year }),
        trackNumber = mergeField(tracks.map { it.trackNumber }),
        discNumber = mergeField(tracks.map { it.discNumber }),
    )

    private fun mergeField(values: List<String>): MergedValue {
        if (values.isEmpty()) return MergedValue.Empty
        val nonBlank = values.filter { it.isNotBlank() }
        if (nonBlank.isEmpty()) return MergedValue.Empty
        return if (nonBlank.all { it == nonBlank.first() }) {
            MergedValue.Uniform(nonBlank.first())
        } else {
            MergedValue.Mixed
        }
    }

    fun buildPatch(edits: List<BatchTagEdit>, merged: MergedTagView): TagPatch {
        val editMap = edits.associate { it.field to it.newValue }
        return TagPatch(
            title = patchFor(TagField.Title, merged.title, editMap),
            artist = patchFor(TagField.Artist, merged.artist, editMap),
            album = patchFor(TagField.Album, merged.album, editMap),
            albumArtist = patchFor(TagField.AlbumArtist, merged.albumArtist, editMap),
            genre = patchFor(TagField.Genre, merged.genre, editMap),
            year = patchForInt(TagField.Year, merged.year, editMap),
            trackNumber = patchForInt(TagField.TrackNumber, merged.trackNumber, editMap),
            discNumber = patchForInt(TagField.DiscNumber, merged.discNumber, editMap),
        )
    }

    private fun patchFor(
        field: TagField,
        merged: MergedValue,
        edits: Map<TagField, String>,
    ): TagPatch.Update<String>? {
        val edited = edits[field] ?: return null
        if (edited.isBlank()) return null
        return TagPatch.Update(edited)
    }

    private fun patchForInt(
        field: TagField,
        merged: MergedValue,
        edits: Map<TagField, String>,
    ): TagPatch.Update<Int>? {
        val edited = edits[field] ?: return null
        if (edited.isBlank()) return null
        return edited.toIntOrNull()?.let { TagPatch.Update(it) }
    }
}
