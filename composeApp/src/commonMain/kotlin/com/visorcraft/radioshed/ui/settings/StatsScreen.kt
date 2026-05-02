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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.stations.StationPlayback
import org.koin.compose.koinInject

/**
 * Listening stats — total time, playback count, top tracks. Pulled
 * straight from `station_history` rows recorded by [StationPlayback]
 * on each track transition / stop.
 *
 * Tracks-as-stat is honest: we count rows in station_history, which
 * means a track played for 5 seconds and skipped counts identically to
 * one played to completion. A future pass could surface "completed
 * plays only" by filtering rows with `completed = 1`. For v1 the
 * top-N is more useful as raw popularity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    stationRepo: StationRepository = koinInject(),
    libraryRepo: LibraryRepository = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
) {
    var totalMs by remember { mutableStateOf(0L) }
    var totalPlays by remember { mutableStateOf(0L) }
    var topTracks by remember {
        mutableStateOf<List<Pair<Track, Long>>>(emptyList())
    }

    LaunchedEffect(Unit) {
        totalMs = stationRepo.totalListenedMs()
        totalPlays = stationRepo.countAllPlays()
        // top track ids → resolve to Track objects via LibraryRepo;
        // skip ids whose tracks have been deleted from the library.
        topTracks = stationRepo.topPlayedTracks(limit = 25L)
            .mapNotNull { (id, count) ->
                libraryRepo.getTrackById(id)?.let { it to count }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Listening stats") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    StatTile(
                        label = "Total listened",
                        value = formatTotalDuration(totalMs),
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Tracks played",
                        value = totalPlays.toString(),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            if (topTracks.isEmpty()) {
                item {
                    Text(
                        "No listening data yet — play some music to populate.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                }
            } else {
                item {
                    Text(
                        "Top played tracks",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                    )
                    Spacer(Modifier.height(4.dp))
                }
                items(topTracks, key = { (t, _) -> t.id }) { (track, plays) ->
                    TopTrackRow(
                        track = track,
                        plays = plays,
                        rank = topTracks.indexOfFirst { it.first.id == track.id } + 1,
                        onTap = {
                            // Tap-to-play a top track seeds an ad-hoc queue
                            // from the top list itself, mirroring how
                            // Library Browser tap-to-play works.
                            val list = topTracks.map { it.first }
                            val idx = list.indexOf(track).coerceAtLeast(0)
                            stationPlayback.playTracks(list, startIndex = idx)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun TopTrackRow(track: Track, plays: Long, rank: Int, onTap: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$rank",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
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
                    text = track.title.ifBlank { track.fileName },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "$plays play${if (plays == 1L) "" else "s"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * Format milliseconds into a compact "Xh Ym" or "Ym Zs" string. We
 * skip seconds once the duration crosses one hour (precision becomes
 * irrelevant for total-listened-time which spans days/weeks).
 */
private fun formatTotalDuration(ms: Long): String {
    if (ms <= 0L) return "0m"
    val totalSeconds = ms / 1_000L
    val days = totalSeconds / 86_400L
    val hours = (totalSeconds % 86_400L) / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    return when {
        days > 0L -> "${days}d ${hours}h"
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m"
        else -> "${totalSeconds}s"
    }
}
