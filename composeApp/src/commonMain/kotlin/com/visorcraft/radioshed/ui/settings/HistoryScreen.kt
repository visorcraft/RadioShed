package com.visorcraft.radioshed.ui.settings

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
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Chronological global play history. Each row shows what was played,
 * which station it came from, and when, all rendered with relative
 * time. Tap to play that track again as a one-off (seeds an ad-hoc
 * queue from the visible history list).
 */
@OptIn(ExperimentalMaterial3Api::class)
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
                            // Seed a queue from the resolved tracks of
                            // the visible history slice (skip the rows
                            // whose tracks have been deleted). Start at
                            // the tapped row.
                            val resolved = entries.mapNotNull { trackById[it.trackId] }
                            val target = trackById[entry.trackId] ?: return@HistoryRow
                            val idx = resolved.indexOf(target).coerceAtLeast(0)
                            stationPlayback.playTracks(resolved, startIndex = idx)
                        },
                    )
                }
            }
        }
    }

    if (clearConfirmOpen) {
        AlertDialog(
            onDismissRequest = { clearConfirmOpen = false },
            title = { Text("Clear listening history?") },
            text = {
                Text(
                    "This deletes every play record but leaves the library, " +
                        "ratings, playlists, and stations untouched. Top played " +
                        "stats will reset to zero.",
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

@Composable
private fun HistoryRow(
    entry: HistoryEntryWithStation,
    track: Track?,
    onTap: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap),
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
