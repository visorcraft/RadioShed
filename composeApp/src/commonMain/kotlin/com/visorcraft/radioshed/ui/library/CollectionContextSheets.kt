package com.visorcraft.radioshed.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.PlaylistRepository
import com.visorcraft.radioshed.domain.Album
import com.visorcraft.radioshed.domain.Artist
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.stations.StationPlayback
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private enum class CollectionStep { Main, PlaylistPicker }

/**
 * Long-press an album tile in Library Browser → this sheet. Same
 * actions as the per-track context sheet, just at album scope so
 * the user doesn't have to drill in just to queue the whole record.
 *
 * Tracks are loaded from the repo once on open. We deliberately
 * don't apply any reorder — albums play in track-order so the
 * record listens like a record. For shuffle mode we load → shuffle
 * → playTracks; the queue ends up randomized but ringfenced to
 * just this album, which is the user's expectation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumContextSheet(
    album: Album,
    onDismiss: () -> Unit,
    onGoToAlbum: () -> Unit,
    onCreateStation: () -> Unit = {},
    libraryRepo: LibraryRepository = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(CollectionStep.Main) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        when (step) {
            CollectionStep.Main ->
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = album.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    album.year?.let { y ->
                        Text(
                            text = "$y",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 0.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    CollectionActionRow(
                        icon = Icons.Filled.PlayArrow,
                        label = "Play album",
                        onClick = {
                            scope.launch {
                                val tracks = libraryRepo.tracksForAlbum(album.id)
                                if (tracks.isNotEmpty()) {
                                    stationPlayback.playTracks(tracks, startIndex = 0)
                                }
                                onDismiss()
                            }
                        },
                    )
                    CollectionActionRow(
                        icon = Icons.Filled.Shuffle,
                        label = "Shuffle album",
                        onClick = {
                            scope.launch {
                                val tracks = libraryRepo.tracksForAlbum(album.id).shuffled()
                                if (tracks.isNotEmpty()) {
                                    stationPlayback.playTracks(tracks, startIndex = 0)
                                }
                                onDismiss()
                            }
                        },
                    )
                    CollectionActionRow(
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                        label = "Add album to queue",
                        onClick = {
                            scope.launch {
                                val tracks = libraryRepo.tracksForAlbum(album.id)
                                stationPlayback.addAllToQueue(tracks)
                                onDismiss()
                            }
                        },
                    )
                    CollectionActionRow(
                        icon = Icons.Filled.PlayArrow,
                        label = "Play next",
                        onClick = {
                            scope.launch {
                                val tracks = libraryRepo.tracksForAlbum(album.id)
                                stationPlayback.playNextAll(tracks)
                                onDismiss()
                            }
                        },
                    )
                    CollectionActionRow(
                        icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                        label = "Add album to playlist…",
                        onClick = { step = CollectionStep.PlaylistPicker },
                    )
                    CollectionActionRow(
                        icon = Icons.Filled.Radio,
                        label = "Create station from this album",
                        onClick = onCreateStation,
                    )
                    CollectionActionRow(
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        label = "Go to album",
                        onClick = onGoToAlbum,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            CollectionStep.PlaylistPicker ->
                CollectionPlaylistPicker(
                    title = "Add ${album.title} to playlist",
                    resolveTracks = { libraryRepo.tracksForAlbum(album.id) },
                    onDone = onDismiss,
                    onBack = { step = CollectionStep.Main },
                )
        }
    }
}

/**
 * Long-press an artist tile in Library Browser → this sheet. Same
 * shape as [AlbumContextSheet] but tracksForArtist scope. Artists
 * with hundreds of tracks can take a moment to load on the IO
 * thread; the sheet stays open while the queue rebuilds, then
 * dismisses on completion — feedback is via the now-playing strip
 * lighting up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistContextSheet(
    artist: Artist,
    onDismiss: () -> Unit,
    onGoToArtist: () -> Unit,
    onCreateStation: () -> Unit = {},
    onEditArtist: () -> Unit = {},
    onMergeArtist: () -> Unit = {},
    libraryRepo: LibraryRepository = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(CollectionStep.Main) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        when (step) {
            CollectionStep.Main ->
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = artist.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    CollectionActionRow(
                        icon = Icons.Filled.PlayArrow,
                        label = "Play all",
                        onClick = {
                            scope.launch {
                                val tracks = libraryRepo.tracksForArtist(artist.id)
                                if (tracks.isNotEmpty()) {
                                    stationPlayback.playTracks(tracks, startIndex = 0)
                                }
                                onDismiss()
                            }
                        },
                    )
                    CollectionActionRow(
                        icon = Icons.Filled.Shuffle,
                        label = "Shuffle artist",
                        onClick = {
                            scope.launch {
                                val tracks = libraryRepo.tracksForArtist(artist.id).shuffled()
                                if (tracks.isNotEmpty()) {
                                    stationPlayback.playTracks(tracks, startIndex = 0)
                                }
                                onDismiss()
                            }
                        },
                    )
                    CollectionActionRow(
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                        label = "Add to queue",
                        onClick = {
                            scope.launch {
                                val tracks = libraryRepo.tracksForArtist(artist.id)
                                stationPlayback.addAllToQueue(tracks)
                                onDismiss()
                            }
                        },
                    )
                    CollectionActionRow(
                        icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                        label = "Add artist to playlist…",
                        onClick = { step = CollectionStep.PlaylistPicker },
                    )
                    CollectionActionRow(
                        icon = Icons.Filled.Radio,
                        label = "Create station from this artist",
                        onClick = onCreateStation,
                    )
                    CollectionActionRow(
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        label = "Go to artist",
                        onClick = onGoToArtist,
                    )
                    // Edit artist: rename + manage alias list. Alternate-
                    // name aliases let "Slim Shady" and "Eminem" resolve
                    // to the same artist on the next scan.
                    CollectionActionRow(
                        icon = Icons.Filled.Edit,
                        label = "Edit artist",
                        onClick = onEditArtist,
                    )
                    // Merge into another artist: pick a target, relink
                    // every track / album / alias, drop the source row.
                    // Useful for collapsing "Jennie" + "Jennie (제니)" or
                    // mismatched-case duplicates from old scans.
                    CollectionActionRow(
                        icon = Icons.AutoMirrored.Filled.MergeType,
                        label = "Merge into another artist…",
                        onClick = onMergeArtist,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            CollectionStep.PlaylistPicker ->
                CollectionPlaylistPicker(
                    title = "Add ${artist.name} to playlist",
                    resolveTracks = { libraryRepo.tracksForArtist(artist.id) },
                    onDone = onDismiss,
                    onBack = { step = CollectionStep.Main },
                )
        }
    }
}

/**
 * Generic playlist picker for "add this whole collection" — takes a
 * suspending track resolver so the sheet doesn't have to know
 * whether it's adding an album or artist. Same shape as
 * [com.visorcraft.radioshed.ui.playlists.TrackContextSheet]'s
 * inline picker, just operating on a list rather than a single id.
 */
@Composable
private fun CollectionPlaylistPicker(
    title: String,
    resolveTracks: suspend () -> List<Track>,
    onDone: () -> Unit,
    onBack: () -> Unit,
    repo: PlaylistRepository = koinInject(),
) {
    val playlists by repo.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var createOpen by remember { mutableStateOf(false) }
    // Re-entry guard: Once the user picks a destination playlist (or
    // hits the Create dialog's Save button) we lock subsequent taps
    // out until the launched coroutine finishes. Without this, a
    // double-tap on the same row launches two coroutines that both
    // read repo.trackCount() before either's writes commit, so both
    // compute the same starting position and end up duplicating the
    // album/artist into the same slots.
    var inFlight by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
            item {
                CollectionActionRow(
                    icon = Icons.Filled.Add,
                    label = "Create new playlist…",
                    onClick = { if (!inFlight) createOpen = true },
                )
            }
            items(playlists, key = { it.id }) { playlist ->
                CollectionActionRow(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    label = playlist.name,
                    onClick = {
                        if (!inFlight) {
                            inFlight = true
                            scope.launch {
                                try {
                                    val tracks = resolveTracks()
                                    // Atomic bulk append. Per-track
                                    // count+insert was racy across concurrent
                                    // album/artist adds and produced
                                    // half-imported playlists when the
                                    // process was killed mid-batch.
                                    repo.appendTracks(
                                        playlistId = playlist.id,
                                        trackIds = tracks.map { it.id },
                                    )
                                    onDone()
                                } finally {
                                    inFlight = false
                                }
                            }
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (createOpen) {
        var newName by remember { mutableStateOf("") }
        // Single submit path used by both the IME "Done" key and
        // the dialog's confirm button — keeps behavior identical
        // whether the user presses Enter or taps the button.
        val confirmCreate: () -> Unit = {
            val n = newName.trim()
            if (n.isNotEmpty() && !inFlight) {
                inFlight = true
                scope.launch {
                    try {
                        val id = repo.insert(name = n)
                        val tracks = resolveTracks()
                        tracks.forEachIndexed { idx, t ->
                            repo.addTrack(id, t.id, position = idx)
                        }
                        createOpen = false
                        onDone()
                    } finally {
                        inFlight = false
                    }
                }
            }
        }
        AlertDialog(
            onDismissRequest = { createOpen = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        ),
                    keyboardActions =
                        androidx.compose.foundation.text.KeyboardActions(
                            onDone = { confirmCreate() },
                        ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = confirmCreate,
                    enabled = newName.isNotBlank(),
                ) { Text("Create + add") }
            },
            dismissButton = {
                TextButton(onClick = { createOpen = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CollectionActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
