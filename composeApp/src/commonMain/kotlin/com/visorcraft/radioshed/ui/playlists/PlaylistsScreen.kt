package com.visorcraft.radioshed.ui.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.data.PlaylistRepository
import com.visorcraft.radioshed.data.RatingRepository
import com.visorcraft.radioshed.domain.Playlist
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Top-level playlist library — list of all playlists with FAB to create
 * a new one. Tapping a row drills into [PlaylistDetailScreen]. Empty
 * state explains how to add tracks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    onBack: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenLikedSongs: () -> Unit = {},
    repo: PlaylistRepository = koinInject(),
    ratingRepo: RatingRepository = koinInject(),
) {
    val playlists by repo.observeAll().collectAsState(initial = emptyList())
    val likedCount by ratingRepo.observeLikedCount().collectAsState(initial = 0L)
    var createOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Resolve cover art per playlist whenever the list changes. The
    // queries are LIMIT 1 + indexed, so this is cheap even with dozens
    // of playlists.
    var coverByPlaylistId by remember { mutableStateOf<Map<Long, String?>>(emptyMap()) }
    var trackCountByPlaylistId by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    LaunchedEffect(playlists.map { it.id }) {
        coverByPlaylistId = playlists.associate { it.id to repo.firstTrackArtwork(it.id) }
        trackCountByPlaylistId = playlists.associate { it.id to repo.trackCount(it.id) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Playlists") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { createOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New playlist")
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        // Liked Songs is always present, even before the user has any
        // user-created playlists or has hearted anything yet — it
        // doubles as a discovery affordance for the heart-toggle
        // mechanic. Pinned to the top, never deletable from this view.
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item(key = "liked-songs") {
                LikedSongsRow(count = likedCount, onTap = onOpenLikedSongs)
            }
            if (playlists.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp, start = 24.dp, end = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No playlists yet — tap + to create one, then long-press any track to add it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        )
                    }
                }
            } else {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistRow(
                        playlist = playlist,
                        coverUri = coverByPlaylistId[playlist.id],
                        trackCount = trackCountByPlaylistId[playlist.id] ?: 0L,
                        onTap = { onOpenPlaylist(playlist.id) },
                    )
                }
            }
        }
    }

    if (createOpen) {
        CreatePlaylistDialog(
            onDismiss = { createOpen = false },
            onCreate = { name ->
                scope.launch {
                    if (name.isNotBlank()) repo.insert(name = name.trim())
                }
                createOpen = false
            },
        )
    }
}

/**
 * Pinned Liked Songs row at the top of the Playlists list. Visually
 * distinct from regular playlists (heart icon, brand-amber tinted
 * artwork tile) so the user reads it as a special always-on
 * collection rather than another user playlist.
 */
@Composable
private fun LikedSongsRow(count: Long, onTap: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Liked songs",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (count == 0L) "Tap the heart on any track to start"
                           else "$count track${if (count == 1L) "" else "s"} you love",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PlaylistRow(
    playlist: Playlist,
    coverUri: String?,
    trackCount: Long,
    onTap: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (coverUri != null) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.primaryContainer,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (coverUri != null) {
                    coil3.compose.AsyncImage(
                        model = coverUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = buildString {
                    append(trackCount).append(if (trackCount == 1L) " track" else " tracks")
                    playlist.description?.takeIf { it.isNotBlank() }?.let {
                        append(" · ").append(it)
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
