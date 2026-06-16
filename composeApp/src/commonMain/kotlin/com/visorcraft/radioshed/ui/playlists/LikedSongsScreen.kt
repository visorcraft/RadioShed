package com.visorcraft.radioshed.ui.playlists

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.data.PlaylistRepository
import com.visorcraft.radioshed.data.RatingRepository
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.stations.StationPlayback
import com.visorcraft.radioshed.ui.util.NowPlayingIndicator
import com.visorcraft.radioshed.ui.util.rememberHapticTick
import com.visorcraft.radioshed.ui.util.TooltipIconButton
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Dedicated view of every track the user has hearted. Reads from the
 * [RatingRepository.likedTracks] feed; ordered most-recently-liked
 * first (matches Spotify / Apple Music's "Liked Songs" order). Tap a
 * track to play, long-press for the standard track context sheet,
 * tap the heart icon at the row's trailing edge to unlike (which
 * removes the row from this view immediately on the next reload).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LikedSongsScreen(
    onBack: () -> Unit,
    ratingRepo: RatingRepository = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    audioEngine: AudioEngine = koinInject(),
    playlistRepo: PlaylistRepository = koinInject(),
) {
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var contextTrackId by remember { mutableStateOf<Long?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var sortOrder by remember { mutableStateOf(LikedSort.RecentlyLiked) }
    // Free-text filter on title or artist. Composes with the sort
    // dropdown — the visible list is the intersection. Session-local
    // (resets on screen exit), matching the other detail-screen
    // filter chips' UX.
    var searchQuery by remember { mutableStateOf("") }
    var saveAsPlaylistOpen by remember { mutableStateOf(false) }
    var savedPlaylistName by remember { mutableStateOf<String?>(null) }
    // Confirmation gate for the trailing heart-button. The previous
    // single-tap-to-unlike behaviour was a stray-finger away from
    // losing the row with no undo path; this dialog makes the action
    // intentional. Long-press still goes to the track context sheet.
    var unlikeConfirm by remember { mutableStateOf<Track?>(null) }
    val likedCount by ratingRepo.observeLikedCount().collectAsState(initial = 0L)
    val nowPlaying by audioEngine.nowPlaying.collectAsState()
    val currentTrackId = nowPlaying?.trackId
    val scope = rememberCoroutineScope()

    // Reload the resolved track list whenever the live count changes
    // — covers like/unlike from elsewhere in the app (Now Playing
    // heart, Track context sheet, etc.). Cheap: indexed read of
    // a small table.
    LaunchedEffect(likedCount) {
        tracks = ratingRepo.likedTracks()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Liked songs") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    Box {
                        TooltipIconButton(
                            tooltip = "Sort",
                            onClick = { sortMenuOpen = true },
                        ) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.Sort,
                                contentDescription = "Sort",
                            )
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                        ) {
                            LikedSort.entries.forEach { opt ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(opt.label, modifier = Modifier.weight(1f))
                                            if (sortOrder == opt) {
                                                Icon(
                                                    imageVector = androidx.compose.material.icons.Icons.Filled.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        sortOrder = opt
                                        sortMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        TooltipIconButton(
                            tooltip = "More",
                            onClick = { menuOpen = true },
                        ) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Save as playlist…") },
                                enabled = tracks.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    saveAsPlaylistOpen = true
                                },
                            )
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            // Long-press the header heart for the
                            // save-as-playlist shortcut, mirroring the
                            // overflow menu's "Save as playlist…" action
                            // — useful for keyboard-averse users who
                            // already know the gesture pattern from
                            // station tiles + history rows.
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    if (tracks.isNotEmpty()) saveAsPlaylistOpen = true
                                },
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(
                        "Liked songs",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // Subtitle: count + total runtime so the user reads
                    // "how big is this collection" at a glance instead of
                    // having to do shoebox math from the row count.
                    // formatTotalRuntime omits the runtime when sum is
                    // zero (e.g. cold-start with no liked tracks yet).
                    val totalDurationMs =
                        remember(tracks) {
                            tracks.sumOf { it.durationMs }
                        }
                    Text(
                        text =
                            buildString {
                                append(tracks.size)
                                append(if (tracks.size == 1) " track" else " tracks")
                                if (totalDurationMs > 0L) {
                                    append(" · ")
                                    append(formatLikedTotalRuntime(totalDurationMs))
                                }
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (tracks.isNotEmpty()) stationPlayback.playTracks(tracks, startIndex = 0)
                    },
                    enabled = tracks.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Play")
                }
                OutlinedButton(
                    onClick = {
                        if (tracks.isNotEmpty()) stationPlayback.playTracks(tracks.shuffled(), startIndex = 0)
                    },
                    enabled = tracks.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                ) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Shuffle")
                }
            }
            Spacer(Modifier.height(8.dp))
            if (tracks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(top = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text =
                            "No liked songs yet — tap the heart on Now Playing or " +
                                "long-press any track to add it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                val sortedTracks =
                    remember(tracks, sortOrder, searchQuery) {
                        val base =
                            when (sortOrder) {
                                LikedSort.RecentlyLiked -> tracks
                                LikedSort.TitleAsc -> tracks.sortedBy { it.title.lowercase() }
                                LikedSort.AlbumAsc ->
                                    tracks.sortedBy {
                                        (it.albumId?.toString() ?: it.title).lowercase()
                                    }
                                LikedSort.DurationAsc -> tracks.sortedBy { it.durationMs }
                                LikedSort.DurationDesc -> tracks.sortedByDescending { it.durationMs }
                            }
                        if (searchQuery.isBlank()) {
                            base
                        } else {
                            base.filter {
                                // Match on the displayed title (or file name
                                // for tracks with empty title tags) so a user
                                // searching for "ramble" still finds rips
                                // that have only the filename to identify them.
                                it.title.contains(searchQuery, ignoreCase = true) ||
                                    it.fileName.contains(searchQuery, ignoreCase = true)
                            }
                        }
                    }
                Column(modifier = Modifier.fillMaxSize()) {
                    if (tracks.size >= 6) {
                        androidx.compose.material3.OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Find in liked…") },
                            singleLine = true,
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = null,
                                )
                            },
                            trailingIcon =
                                if (searchQuery.isNotEmpty()) {
                                    {
                                        TooltipIconButton(
                                            tooltip = "Clear",
                                            onClick = { searchQuery = "" },
                                        ) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "Clear",
                                            )
                                        }
                                    }
                                } else {
                                    null
                                },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    val listState = rememberLazyListState()
                    val showScrollTop by remember {
                        derivedStateOf { listState.firstVisibleItemIndex > 8 }
                    }
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            items(sortedTracks, key = { it.id }) { track ->
                                LikedSongRow(
                                    track = track,
                                    isCurrent = track.id == currentTrackId,
                                    onTap = {
                                        val idx = sortedTracks.indexOf(track).coerceAtLeast(0)
                                        stationPlayback.playTracks(sortedTracks, startIndex = idx)
                                    },
                                    onLongPress = { contextTrackId = track.id },
                                    onUnlike = {
                                        // Quick-tap unlike used to fire
                                        // immediately, which was a one-tap
                                        // mistake away from losing a row
                                        // with no undo. Now we route through
                                        // a confirm dialog.
                                        unlikeConfirm = track
                                    },
                                )
                            }
                            if (sortedTracks.isEmpty() && searchQuery.isNotBlank()) {
                                item(key = "no-match") {
                                    Text(
                                        "No liked songs match \"$searchQuery\".",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                        modifier = Modifier.padding(16.dp),
                                    )
                                }
                            }
                        }
                        if (showScrollTop) {
                            SmallFloatingActionButton(
                                onClick = {
                                    scope.launch { listState.animateScrollToItem(0) }
                                },
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 16.dp, bottom = 16.dp),
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowUp,
                                    contentDescription = "Scroll to top",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    contextTrackId?.let { id ->
        TrackContextSheet(
            trackId = id,
            onDismiss = { contextTrackId = null },
        )
    }

    unlikeConfirm?.let { target ->
        AlertDialog(
            onDismissRequest = { unlikeConfirm = null },
            title = { Text("Unlike this song?") },
            text = {
                Text(
                    "Removes \"${target.title.ifBlank { target.fileName }}\" " +
                        "from Liked songs. The track stays in your library.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = target.id
                        unlikeConfirm = null
                        scope.launch { ratingRepo.unlike(id) }
                    },
                ) {
                    Text("Unlike", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { unlikeConfirm = null }) { Text("Cancel") }
            },
        )
    }

    if (saveAsPlaylistOpen) {
        var name by remember { mutableStateOf("Liked songs") }
        AlertDialog(
            onDismissRequest = { saveAsPlaylistOpen = false },
            title = { Text("Save Liked songs as playlist") },
            text = {
                Column {
                    Text(
                        "Creates a regular playlist with the same tracks. " +
                            "Future changes to Liked songs won't affect the " +
                            "playlist — it's a one-time snapshot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Playlist name") },
                        singleLine = true,
                        keyboardOptions =
                            androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                            ),
                        keyboardActions =
                            androidx.compose.foundation.text.KeyboardActions(
                                onDone = {
                                    if (name.isNotBlank()) {
                                        val cleanName = name.trim()
                                        val snapshot = tracks
                                        saveAsPlaylistOpen = false
                                        scope.launch {
                                            val playlistId = playlistRepo.insert(name = cleanName)
                                            playlistRepo.appendTracks(playlistId, snapshot.map { it.id })
                                            savedPlaylistName = cleanName
                                        }
                                    }
                                },
                            ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val cleanName = name.trim()
                        val snapshot = tracks
                        saveAsPlaylistOpen = false
                        scope.launch {
                            val playlistId = playlistRepo.insert(name = cleanName)
                            playlistRepo.appendTracks(playlistId, snapshot.map { it.id })
                            savedPlaylistName = cleanName
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { saveAsPlaylistOpen = false }) { Text("Cancel") }
            },
        )
    }
    savedPlaylistName?.let { name ->
        AlertDialog(
            onDismissRequest = { savedPlaylistName = null },
            confirmButton = {
                TextButton(onClick = { savedPlaylistName = null }) { Text("OK") }
            },
            title = { Text("Saved") },
            text = { Text("\"$name\" is in your Playlists tab.") },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LikedSongRow(
    track: Track,
    isCurrent: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onUnlike: () -> Unit,
) {
    val haptic = rememberHapticTick()
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onTap, onLongClick = { haptic(); onLongPress() }),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(10.dp))
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = track.title.ifBlank { track.fileName },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color =
                        if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onBackground
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                NowPlayingIndicator(isCurrent)
            }
            TooltipIconButton(tooltip = "Unlike", onClick = onUnlike) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = "Unlike",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Sort options for the Liked Songs list. Default [RecentlyLiked]
 * matches Spotify / Apple Music's "Liked Songs" order — the
 * `RatingRepository.likedTracks()` query already returns rows in
 * that order, so the default is a passthrough. Sort state is
 * session-local; the user picks per visit.
 */
private enum class LikedSort(val label: String) {
    RecentlyLiked("Recently liked"),
    TitleAsc("Title A→Z"),
    AlbumAsc("Album"),
    DurationAsc("Duration (shortest)"),
    DurationDesc("Duration (longest)"),
}

/**
 * Format a sum of track durations for the Liked Songs subtitle.
 * Tiered output (Xh Ym vs Mm) keeps narrow phone widths from
 * truncating "1h 23m 45s" — once you're past an hour the seconds
 * stop being meaningful for "how big is my collection".
 */
private fun formatLikedTotalRuntime(totalMs: Long): String {
    val totalSec = totalMs / 1000L
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m"
        else -> "<1m"
    }
}
