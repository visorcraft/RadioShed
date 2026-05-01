package com.visorcraft.radioshed.ui.stations

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.PlayerState
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.domain.Station
import com.visorcraft.radioshed.stations.StationPlayback
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Stations Home — list of all stations with tap-to-play. v1 is a single
 * vertical list; the SPEC-described grid + sort/group menus + drag-reorder
 * land alongside the Station Editor in Phase 5 part 2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationsHomeScreen(
    onBack: () -> Unit,
    stationRepo: StationRepository = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    audioEngine: AudioEngine = koinInject(),
) {
    val stations by stationRepo.observeAll().collectAsState(initial = emptyList())
    val currentStation by stationPlayback.currentStation.collectAsState()
    val playerState by audioEngine.state.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stations") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (stations.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No stations yet.\n\nFinish onboarding (or scan a folder in Library) — built-in presets are seeded automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(stations, key = { it.id }) { station ->
                    StationCard(
                        station = station,
                        isCurrent = station.id == currentStation?.id,
                        isPlaying = station.id == currentStation?.id &&
                            playerState == PlayerState.Playing,
                        onPlay = {
                            scope.launch { stationPlayback.playStation(station.id) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StationCard(
    station: Station,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorTile(color = Color(station.color.toLong() and 0xFFFFFFFFL))
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        station.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                    )
                    if (station.isBuiltIn) {
                        Spacer(Modifier.size(8.dp))
                        BuiltInBadge()
                    }
                }
                station.customSubtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                if (station.stats.playCount > 0) {
                    Text(
                        "Played ${station.stats.playCount}× · ${station.playback.order.label()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.55f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            Text(
                if (isPlaying) "▶ Playing" else "▶",
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ColorTile(color: Color) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (color == Color.Black) MaterialTheme.colorScheme.primary else color),
    )
}

@Composable
private fun BuiltInBadge() {
    Text(
        "BUILT-IN",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun com.visorcraft.radioshed.domain.PlayOrder.label(): String = when (this) {
    com.visorcraft.radioshed.domain.PlayOrder.Shuffle -> "shuffle"
    com.visorcraft.radioshed.domain.PlayOrder.Sequential -> "sequential"
    com.visorcraft.radioshed.domain.PlayOrder.SmartShuffle -> "smart shuffle"
    com.visorcraft.radioshed.domain.PlayOrder.WeightedRandom -> "weighted"
}
