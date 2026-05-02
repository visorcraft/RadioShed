package com.visorcraft.radioshed.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box as LayoutBox
import androidx.compose.runtime.rememberCoroutineScope
import com.visorcraft.radioshed.data.HistoryEntryWithStation
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.data.nowMillis
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.stations.StationPlayback
import com.visorcraft.radioshed.ui.playlists.TrackContextSheet
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Chronological global play history. Each row shows what was played,
 * which station it came from, and when, all rendered with relative
 * time. Tap to play that track again as a one-off (seeds an ad-hoc
 * queue from the visible history list).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    stationRepo: StationRepository = koinInject(),
    libraryRepo: LibraryRepository = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
) {
    var entries by remember { mutableStateOf<List<HistoryEntryWithStation>>(emptyList()) }
    var trackById by remember { mutableStateOf<Map<Long, Track>>(emptyMap()) }
    var menuOpen by remember { mutableStateOf(false) }
    var clearConfirmOpen by remember { mutableStateOf(false) }
    var contextTrackId by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        entries = stationRepo.allHistory(limit = 200L)
        trackById = entries
            .map { it.trackId }
            .distinct()
            .mapNotNull { id -> libraryRepo.getTrackById(id)?.let { id to it } }
            .toMap()
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Listening history") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    LayoutBox {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Clear history") },
                                enabled = entries.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    clearConfirmOpen = true
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No history yet — start playing some music to populate.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    HistoryRow(
                        entry = entry,
                        track = trackById[entry.trackId],
                        onTap = {
                            // Build a parallel pair-list keyed by the
                            // history-row id, then find the index by
                            // entry.id rather than Track-object
                            // identity. Using indexOf(track) was wrong
                            // because the same track plays multiple
                            // times resolve to the SAME Track instance,
                            // so every tap on any occurrence of that
                            // track jumped to the position of the
                            // first-seen play in the resolved list.
                            val pairs = entries.mapNotNull { e ->
                                trackById[e.trackId]?.let { e.id to it }
                            }
                            val idx = pairs.indexOfFirst { it.first == entry.id }
                            if (idx < 0) return@HistoryRow
                            stationPlayback.playTracks(
                                pairs.map { it.second },
                                startIndex = idx,
                            )
                        },
                        onLongPress = {
                            // Skip rows whose track has been deleted
                            // — the context sheet would just show
                            // an empty title with no actionable info.
                            trackById[entry.trackId]?.let {
                                contextTrackId = entry.trackId
                            }
                        },
                    )
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

    if (clearConfirmOpen) {
        AlertDialog(
            onDismissRequest = { clearConfirmOpen = false },
            title = { Text("Clear listening history?") },
            text = {
                Text(
                    "This deletes every play record AND resets per-station play " +
                        "counts. Library, ratings, playlists, and station " +
                        "definitions are untouched. Top played tracks / artists " +
                        "/ albums and Top Stations all reset to zero.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    clearConfirmOpen = false
                    scope.launch {
                        stationRepo.clearAllHistory()
                        reload()
                    }
                }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirmOpen = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    entry: HistoryEntryWithStation,
    track: Track?,
    onTap: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track?.title?.ifBlank { track.fileName } ?: "Removed track",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (track == null)
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${entry.stationName} · ${formatHistoryTimestamp(entry.playedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Visual marker for skipped vs completed plays — completed
            // gets a small filled dot, skipped is hollow. Subtle but
            // useful for "what did I actually finish vs skip past?".
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (entry.completed) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
                    ),
            )
        }
    }
}

/** Same relative-time vocabulary as backups, just shorter. */
private fun formatHistoryTimestamp(ms: Long): String {
    val deltaMs = (nowMillis() - ms).coerceAtLeast(0L)
    val sec = deltaMs / 1_000L
    val min = sec / 60L
    val hr = min / 60L
    val day = hr / 24L
    return when {
        sec < 45L -> "Just now"
        min < 60L -> "${min}m ago"
        hr < 24L -> "${hr}h ago"
        day < 7L -> "${day}d ago"
        day < 30L -> "${day / 7L}w ago"
        else -> "${day / 30L}mo ago"
    }
}
