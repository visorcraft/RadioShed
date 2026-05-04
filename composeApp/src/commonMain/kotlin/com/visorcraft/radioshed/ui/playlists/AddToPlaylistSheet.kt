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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.QueueMusic
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
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.data.PlaylistRepository
import com.visorcraft.radioshed.domain.Playlist
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Bottom sheet shown when the user long-presses a track and picks
 * "Add to playlist". Lists existing playlists + a "Create new..." row.
 *
 * The position assigned to the new entry is `current_track_count`,
 * so newly added tracks always land at the end of the playlist
 * (matches user expectation: "I just added it, it's at the bottom").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    trackId: Long,
    onDismiss: () -> Unit,
    repo: PlaylistRepository = koinInject(),
) {
    val sheetState = rememberModalBottomSheetState()
    val playlists by repo.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var createOpen by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Add to playlist",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    PlaylistPickRow(
                        leadingIcon = Icons.Filled.Add,
                        title = "Create new playlist…",
                        onClick = { createOpen = true },
                    )
                }
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistPickRow(
                        leadingIcon = Icons.AutoMirrored.Filled.QueueMusic,
                        title = playlist.name,
                        onClick = {
                            scope.launch {
                                addTrackAtEnd(repo, playlist, trackId)
                                onDismiss()
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (createOpen) {
        var newName by remember { mutableStateOf("") }
        val confirmCreate: () -> Unit = {
            val n = newName.trim()
            if (n.isNotEmpty()) {
                scope.launch {
                    val id = repo.insert(name = n)
                    repo.addTrack(id, trackId, position = 0)
                    createOpen = false
                    onDismiss()
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
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
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

private suspend fun addTrackAtEnd(
    repo: PlaylistRepository,
    playlist: Playlist,
    trackId: Long,
) {
    // Atomic append. The previous count + addTrack split was racy:
    // two near-simultaneous adds both read the same count and
    // wrote rows at the same `position`, scrambling the visible
    // playlist order. `appendTrack` wraps the read + write in a
    // transaction so the SQLite write lock serializes concurrent
    // appends.
    repo.appendTrack(playlist.id, trackId)
}

@Composable
private fun PlaylistPickRow(
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = leadingIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
