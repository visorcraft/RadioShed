package com.visorcraft.radioshed.ui.playlists

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
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.LaunchedEffect
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
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.stations.StationPlayback
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Long-press track menu. Three core actions for v1:
 *  - **Play next** — inject after the current track
 *  - **Add to queue** — append to the priority buffer's tail
 *  - **Add to playlist** — opens [AddToPlaylistSheet] on top
 *
 * Why bottom sheet not dropdown: the actions affect the playback
 * queue, which feels like a "modal commit" rather than an inline
 * tweak. The sheet's height + dismiss gesture is also more thumb-
 * friendly while driving than a tiny dropdown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackContextSheet(
    trackId: Long,
    onDismiss: () -> Unit,
    libraryRepo: LibraryRepository = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
) {
    val sheetState = rememberModalBottomSheetState()
    var track by remember { mutableStateOf<Track?>(null) }
    // Internal step machine — when the user picks "Add to playlist",
    // we replace the visible content of the SAME sheet rather than
    // opening a second ModalBottomSheet on top (which produces
    // z-order / dismiss-coordination artifacts on Material3).
    var step by remember { mutableStateOf(Step.Main) }

    LaunchedEffect(trackId) {
        track = libraryRepo.getTrackById(trackId)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        when (step) {
            Step.Main -> MainStep(
                track = track,
                onPlayNext = {
                    track?.let { stationPlayback.playNext(it) }
                    onDismiss()
                },
                onAddToQueue = {
                    track?.let { stationPlayback.addToQueue(it) }
                    onDismiss()
                },
                onAddToPlaylist = { step = Step.PlaylistPicker },
            )
            Step.PlaylistPicker -> AddToPlaylistInline(
                trackId = trackId,
                onDone = onDismiss,
                onBack = { step = Step.Main },
            )
        }
    }
}

private enum class Step { Main, PlaylistPicker }

@Composable
private fun MainStep(
    track: Track?,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
        // Track title header — gives the user a moment of confirmation
        // that they long-pressed the right row. While the title is
        // resolving from the DB we render whitespace of the same
        // height so the actions don't bounce when it lands.
        Text(
            text = track?.title?.ifBlank { track.fileName } ?: " ",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        ContextActionRow(
            icon = Icons.Filled.PlayArrow,
            label = "Play next",
            enabled = track != null,
            onClick = onPlayNext,
        )
        ContextActionRow(
            icon = Icons.AutoMirrored.Filled.QueueMusic,
            label = "Add to queue",
            enabled = track != null,
            onClick = onAddToQueue,
        )
        ContextActionRow(
            icon = Icons.AutoMirrored.Filled.PlaylistAdd,
            label = "Add to playlist…",
            enabled = track != null,
            onClick = onAddToPlaylist,
        )
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Inline playlist-picker shown inside the same ModalBottomSheet as
 * MainStep. Mirrors AddToPlaylistSheet's content (existing playlists +
 * "Create new" + new-playlist dialog) without nesting a second sheet.
 */
@Composable
private fun AddToPlaylistInline(
    trackId: Long,
    onDone: () -> Unit,
    onBack: () -> Unit,
    repo: PlaylistRepository = koinInject(),
) {
    val playlists by repo.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var createOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(
                "Add to playlist",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
            item {
                ContextActionRow(
                    icon = Icons.Filled.Add,
                    label = "Create new playlist…",
                    enabled = true,
                    onClick = { createOpen = true },
                )
            }
            items(playlists, key = { it.id }) { playlist ->
                ContextActionRow(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    label = playlist.name,
                    enabled = true,
                    onClick = {
                        scope.launch {
                            val count = repo.trackCount(playlist.id).toInt()
                            repo.addTrack(playlist.id, trackId, position = count)
                            onDone()
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (createOpen) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { createOpen = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val n = newName.trim()
                        if (n.isNotEmpty()) {
                            scope.launch {
                                val id = repo.insert(name = n)
                                repo.addTrack(id, trackId, position = 0)
                                createOpen = false
                                onDone()
                            }
                        }
                    },
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
private fun ContextActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurface
               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
        )
    }
}
