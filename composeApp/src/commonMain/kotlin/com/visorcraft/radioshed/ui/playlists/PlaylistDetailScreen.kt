package com.visorcraft.radioshed.ui.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.visorcraft.radioshed.domain.Playlist
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.stations.StationPlayback
import com.visorcraft.radioshed.ui.util.NowPlayingIndicator
import com.visorcraft.radioshed.ui.util.rememberHapticTick
import com.visorcraft.radioshed.ui.util.TooltipIconButton
import com.visorcraft.radioshed.ui.util.dragReorderItem
import com.visorcraft.radioshed.ui.util.rememberDragReorderState
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    onBack: () -> Unit,
    repo: PlaylistRepository = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    audioEngine: AudioEngine = koinInject(),
    libraryRepo: com.visorcraft.radioshed.data.LibraryRepository = koinInject(),
    playlistExporter: com.visorcraft.radioshed.source.PlaylistExporter = koinInject(),
) {
    var playlist by remember { mutableStateOf<Playlist?>(null) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }
    var deleteConfirmOpen by remember { mutableStateOf(false) }
    // Long-press a track row → opens the standard TrackContextSheet
    // for Play next / Add to queue / Like / Go-to-album / etc.
    var contextTrackId by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val nowPlaying by audioEngine.nowPlaying.collectAsStateWithLifecycle()
    val currentTrackId = nowPlaying?.trackId

    suspend fun reload() {
        playlist = repo.getById(playlistId)
        tracks = repo.tracksIn(playlistId)
    }

    LaunchedEffect(playlistId) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        playlist?.name ?: "Playlist",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
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
                            val summaryClipboard =
                                com.visorcraft.radioshed.ui.util
                                    .rememberCopyToClipboard()
                            DropdownMenuItem(
                                text = { Text("Copy summary") },
                                enabled = playlist != null,
                                onClick = {
                                    menuOpen = false
                                    val totalMs = tracks.sumOf { it.durationMs }
                                    val name = playlist?.name ?: "Playlist"
                                    val mins = totalMs / 60_000L
                                    val hrs = mins / 60L
                                    val durLabel =
                                        if (hrs > 0) {
                                            "${hrs}h ${mins % 60L}m"
                                        } else {
                                            "${mins}m"
                                        }
                                    summaryClipboard(
                                        "$name — ${tracks.size} track" +
                                            (if (tracks.size == 1) "" else "s") +
                                            " · $durLabel",
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Export as .m3u…") },
                                enabled = tracks.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    val name = playlist?.name ?: "playlist"
                                    val snapshot = tracks
                                    scope.launch {
                                        // Single batched query for all
                                        // (trackId → primary-artist
                                        // name) pairs. The previous
                                        // per-track artistsForTrack(id)
                                        // loop was N round-trips
                                        // through the IO dispatcher —
                                        // a 200-track playlist froze
                                        // the export for ~2s while the
                                        // queries serialized.
                                        val artistByTrack: Map<Long, String> =
                                            libraryRepo.primaryArtistNameMap(
                                                snapshot.map { it.id },
                                            )
                                        val entries =
                                            snapshot.map { t ->
                                                com.visorcraft.radioshed.source.M3uEntry(
                                                    absolutePath = t.path,
                                                    durationSec = (t.durationMs / 1000L).toInt(),
                                                    artist = artistByTrack[t.id],
                                                    title = t.displayTitle(),
                                                )
                                            }
                                        playlistExporter.exportM3u(name, entries)
                                    }
                                },
                            )
                            // One-click sort actions. These rewrite
                            // the persisted track order via
                            // setTrackOrder — distinct from the
                            // drag-to-reorder affordance which works
                            // one row at a time. Each sort produces
                            // a fresh ordering rather than toggling,
                            // so re-applying the same sort is
                            // idempotent (no-op once stable).
                            androidx.compose.material3.HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Sort A → Z") },
                                enabled = tracks.size > 1,
                                onClick = {
                                    menuOpen = false
                                    val ordered =
                                        tracks
                                            .sortedBy { it.title.lowercase() }
                                            .map { it.id }
                                    scope.launch {
                                        repo.setTrackOrder(playlistId, ordered)
                                        reload()
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Sort by duration") },
                                enabled = tracks.size > 1,
                                onClick = {
                                    menuOpen = false
                                    val ordered =
                                        tracks
                                            .sortedBy { it.durationMs }
                                            .map { it.id }
                                    scope.launch {
                                        repo.setTrackOrder(playlistId, ordered)
                                        reload()
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Shuffle once") },
                                enabled = tracks.size > 1,
                                onClick = {
                                    menuOpen = false
                                    val ordered = tracks.shuffled().map { it.id }
                                    scope.launch {
                                        repo.setTrackOrder(playlistId, ordered)
                                        reload()
                                    }
                                },
                            )
                            // Remove duplicates: keep the first occurrence
                            // of each trackId, drop the rest. Preserves the
                            // user's chosen order — only the duplicates get
                            // pruned. Disabled when there's nothing to
                            // remove so the menu item doesn't lie about
                            // having work to do.
                            val hasDupes =
                                remember(tracks) {
                                    tracks.map { it.id }.let { it.size != it.toSet().size }
                                }
                            DropdownMenuItem(
                                text = { Text("Remove duplicates") },
                                enabled = hasDupes,
                                onClick = {
                                    menuOpen = false
                                    val seen = mutableSetOf<Long>()
                                    val ordered =
                                        tracks.mapNotNull { t ->
                                            if (seen.add(t.id)) t.id else null
                                        }
                                    scope.launch {
                                        repo.setTrackOrder(playlistId, ordered)
                                        reload()
                                    }
                                },
                            )
                            androidx.compose.material3.HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Delete playlist") },
                                onClick = {
                                    menuOpen = false
                                    deleteConfirmOpen = true
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
            Text(
                playlist?.name ?: "",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            // Track count + total runtime so the user has a sense of
            // how long the playlist will run before pressing Play.
            // Hide the runtime half when empty since "0 tracks · 0
            // min" is just clutter.
            val trackCountLabel = "${tracks.size} track${if (tracks.size == 1) "" else "s"}"
            val subtitleText =
                if (tracks.isEmpty()) {
                    trackCountLabel
                } else {
                    val totalMs = tracks.sumOf { it.durationMs }
                    "$trackCountLabel · ${formatPlaylistRuntime(totalMs)}"
                }
            // Subtitle is tappable so the user can copy a paste-
            // friendly "{name} — {N tracks · runtime}" line into a
            // chat / journal without a separate Share flow. No
            // dedicated "Copy" affordance because the whole
            // subtitle row IS the affordance — single-tap surfaces
            // are unambiguous here.
            val playlistSubtitleClipboard =
                com.visorcraft.radioshed.ui.util
                    .rememberCopyToClipboard()
            val playlistName = playlist?.name.orEmpty()
            Text(
                subtitleText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier =
                    Modifier.clickable {
                        val combined =
                            if (playlistName.isBlank()) {
                                subtitleText
                            } else {
                                "$playlistName — $subtitleText"
                            }
                        playlistSubtitleClipboard(combined)
                    },
            )
            Spacer(Modifier.height(12.dp))
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
                        "No tracks yet — long-press a track in your Library, " +
                            "Album, or Search results and pick \"Add to playlist\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                val listState = rememberLazyListState()
                val dragState =
                    rememberDragReorderState(
                        lazyListState = listState,
                        onMove = { from, to ->
                            // In-memory swap so the row visually follows the
                            // finger; persistence is deferred to onDrop so a
                            // 5-row drag is a single SQL transaction rather
                            // than five.
                            tracks =
                                tracks.toMutableList().also {
                                    val item = it.removeAt(from)
                                    it.add(to, item)
                                }
                        },
                        onDrop = { _ ->
                            val finalOrder = tracks.map { it.id }
                            scope.launch {
                                repo.setTrackOrder(playlistId, finalOrder)
                            }
                        },
                    )
                val showFab by remember {
                    androidx.compose.runtime.derivedStateOf {
                        listState.firstVisibleItemIndex > 8
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                            PlaylistTrackRow(
                                track = track,
                                isDragging = dragState.draggedIndex == index,
                                isCurrent = track.id == currentTrackId,
                                modifier = Modifier.dragReorderItem(dragState, index),
                                onTap = {
                                    // indexOf-by-equality is safe here because
                                    // Track is a data class; if the dragged
                                    // state is mid-flight we ignore tap.
                                    if (dragState.draggedIndex != null) return@PlaylistTrackRow
                                    val idx = tracks.indexOf(track).coerceAtLeast(0)
                                    stationPlayback.playTracks(tracks, startIndex = idx)
                                },
                                // Long-press → standard track context
                                // sheet (Play next / Add to queue /
                                // Add to playlist / Like / Go to album
                                // / Go to artist / etc). Closes the
                                // gap with every other track-list
                                // surface that already routes long-
                                // press through the context sheet.
                                onLongPress = {
                                    contextTrackId = track.id
                                },
                                onRemove = {
                                    scope.launch {
                                        repo.removeTrack(playlistId, track.id)
                                        reload()
                                    }
                                },
                            )
                        }
                    }
                    if (showFab) {
                        androidx.compose.material3.SmallFloatingActionButton(
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
                                imageVector = androidx.compose.material.icons.Icons.Filled.KeyboardArrowUp,
                                contentDescription = "Scroll to top",
                            )
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

    if (deleteConfirmOpen) {
        AlertDialog(
            onDismissRequest = { deleteConfirmOpen = false },
            title = { Text("Delete playlist?") },
            text = {
                Text("\"${playlist?.name ?: ""}\" will be removed. The tracks themselves stay in your library.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteConfirmOpen = false
                        scope.launch {
                            repo.delete(playlistId)
                            onBack()
                        }
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmOpen = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlaylistTrackRow(
    track: Track,
    isDragging: Boolean = false,
    isCurrent: Boolean = false,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    onRemove: () -> Unit,
) {
    val haptic = rememberHapticTick()
    Surface(
        modifier =
            if (onLongPress != null) {
                modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = onTap, onLongClick = { haptic(); onLongPress() })
            } else {
                modifier.fillMaxWidth().clickable(onClick = onTap)
            },
        // Subtle background when actively dragging so the row visually
        // separates from its neighbours during the gesture.
        color =
            if (isDragging) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            } else {
                Color.Transparent
            },
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
                    text = track.displayTitle(),
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
            TooltipIconButton(tooltip = "Remove from playlist", onClick = onRemove) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove from playlist",
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
            // Drag-handle hint sits at the trailing edge so the user
            // discovers the long-press-and-drag affordance. Hit-testing
            // happens against the entire row (the modifier is on the
            // outer Surface), so this is purely a visual cue.
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = null,
                modifier = Modifier.size(20.dp).padding(end = 4.dp),
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
            )
        }
    }
}

/**
 * Playlist runtime formatter. Switches between "47 min" for short
 * playlists and "1h 23m" / "12h 5m" for longer ones — same
 * vocabulary the Album / Artist detail screens use, so users see
 * one consistent format across the library surfaces.
 */
private fun formatPlaylistRuntime(totalMs: Long): String {
    val totalMin = totalMs / 60_000L
    if (totalMin < 60L) return "$totalMin min"
    val hours = totalMin / 60L
    val mins = totalMin % 60L
    return if (mins == 0L) "${hours}h" else "${hours}h ${mins}m"
}
