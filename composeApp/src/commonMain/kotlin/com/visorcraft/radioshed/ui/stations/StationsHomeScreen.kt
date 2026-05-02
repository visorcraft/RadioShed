package com.visorcraft.radioshed.ui.stations

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.PlayerState
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.domain.PlayOrder
import com.visorcraft.radioshed.domain.Station
import com.visorcraft.radioshed.stations.StationPlayback
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Stations Home — responsive grid of stations with tap-to-play.
 *
 * Width-driven column count (the §1.1 fluid-design principle):
 *   compact phone   < 600dp wide   → 1 column (list-style row)
 *   medium tablet   600–840dp      → 2 columns
 *   expanded        840–1200dp     → 3 columns
 *   car-console     ≥ 1200dp       → 4–5 columns
 *
 * The grid uses `GridCells.Adaptive(minSize=280.dp)` so the column count
 * tracks the actual width regardless of platform — same single rule
 * works for phones, tablets, foldables, and head-units.
 *
 * Drag-reorder, sort/group menus, and the "Add station" entry point land
 * with the Station Editor in Phase 5 part 2.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StationsHomeScreen(
    onBack: () -> Unit,
    onOpenNowPlaying: () -> Unit = {},
    onAddStation: () -> Unit = {},
    onEditStation: (Long) -> Unit = {},
    stationRepo: StationRepository = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    audioEngine: AudioEngine = koinInject(),
) {
    val stations by stationRepo.observeAll().collectAsState(initial = emptyList())
    val currentStation by stationPlayback.currentStation.collectAsState()
    val playerState by audioEngine.state.collectAsState()
    val nowPlaying by audioEngine.nowPlaying.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            // Slim top bar — just the back affordance. The page title moves
            // into a richer header inside the grid so it can reflow with the
            // content and breathe more on wide screens.
            TopAppBar(
                title = {},
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            val isCompact = maxWidth < 600.dp
            val outerPadding = if (isCompact) 16.dp else 24.dp

            if (stations.isEmpty()) {
                EmptyState(
                    isCompact = isCompact,
                    modifier = Modifier.fillMaxSize().padding(outerPadding),
                )
                return@BoxWithConstraints
            }

            val columns = if (isCompact) GridCells.Fixed(1)
                          else GridCells.Adaptive(minSize = 280.dp)

            LazyVerticalGrid(
                columns = columns,
                contentPadding = PaddingValues(
                    start = outerPadding,
                    end = outerPadding,
                    top = 8.dp,
                    bottom = outerPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 16.dp),
                horizontalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // Page header spanning the full row — replaces the TopAppBar
                // title with something proportional to a 1290dp screen.
                item(span = { GridItemSpan(maxLineSpan) }) {
                    StationsPageHeader(
                        stationCount = stations.size,
                        builtInCount = stations.count { it.isBuiltIn },
                        isCompact = isCompact,
                        onAddStation = onAddStation,
                    )
                }

                // Continue Listening callout — only when a station is active.
                currentStation?.let { active ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ContinueListeningCard(
                            station = active,
                            isPlaying = playerState == PlayerState.Playing,
                            trackTitle = nowPlaying?.title,
                            trackArtist = nowPlaying?.artist,
                            artworkUri = nowPlaying?.artworkUri,
                            onTap = onOpenNowPlaying,
                        )
                    }
                }

                items(stations, key = { it.id }) { station ->
                    if (isCompact) {
                        StationListRow(
                            station = station,
                            isCurrent = station.id == currentStation?.id,
                            isPlaying = station.id == currentStation?.id &&
                                playerState == PlayerState.Playing,
                            onPlay = {
                                scope.launch { stationPlayback.playStation(station.id) }
                            },
                            onLongPress = { onEditStation(station.id) },
                        )
                    } else {
                        StationGridTile(
                            station = station,
                            isCurrent = station.id == currentStation?.id,
                            isPlaying = station.id == currentStation?.id &&
                                playerState == PlayerState.Playing,
                            onPlay = {
                                scope.launch { stationPlayback.playStation(station.id) }
                            },
                            onEdit = { onEditStation(station.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StationsPageHeader(
    stationCount: Int,
    builtInCount: Int,
    isCompact: Boolean,
    onAddStation: () -> Unit,
) {
    val customCount = stationCount - builtInCount
    val subtitleParts = buildList {
        add("$stationCount station${if (stationCount == 1) "" else "s"}")
        if (builtInCount > 0) add("$builtInCount built-in")
        if (customCount > 0) add("$customCount custom")
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Stations",
                style = if (isCompact) MaterialTheme.typography.headlineMedium
                        else MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                subtitleParts.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
        Button(onClick = onAddStation) { Text("+ Add station") }
    }
}

@Composable
private fun ContinueListeningCard(
    station: Station,
    isPlaying: Boolean,
    trackTitle: String?,
    trackArtist: String?,
    artworkUri: String?,
    onTap: () -> Unit,
) {
    val accent = stationColor(station)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Album art when we have it, otherwise tinted gradient with a
            // playing/paused glyph overlay (same visual language as tiles).
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(accent, accent.copy(alpha = 0.55f)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (artworkUri != null) {
                    coil3.compose.AsyncImage(
                        model = artworkUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = Color.White,
                    )
                }
            }
            Spacer(Modifier.size(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isPlaying) "Now playing" else "Paused",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    text = trackTitle ?: station.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitleLine = listOfNotNull(
                    trackArtist,
                    "from ${station.name}".takeIf { trackTitle != null },
                ).joinToString(" · ")
                if (subtitleLine.isNotBlank()) {
                    Spacer(Modifier.size(2.dp))
                    Text(
                        text = subtitleLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Text(
                text = "Open ›",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun EmptyState(isCompact: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Stations",
            style = if (isCompact) MaterialTheme.typography.headlineMedium
                    else MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            "No stations yet.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            "Finish onboarding (or scan a folder in Library) — built-in presets are seeded automatically once tracks are indexed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StationListRow(
    station: Station,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onLongPress: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = onPlay,
            onLongClick = onLongPress,
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorTile(
                color = stationColor(station),
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        station.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                           else MaterialTheme.colorScheme.primary,
                )
                if (isPlaying) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "Playing",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * Square-ish tile suitable for grid layouts. Top is a tinted gradient
 * placeholder; bottom holds the name + subtitle. The whole card is
 * tappable; a "Now Playing" overlay shows on the current station.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StationGridTile(
    station: Station,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
) {
    val accent = stationColor(station)
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = onPlay,
            onLongClick = onEdit,
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isCurrent) 4.dp else 1.dp,
        ),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.5f)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(accent, accent.copy(alpha = 0.55f)),
                        ),
                    ),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(12.dp),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.PlayArrow else Icons.Filled.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Color.White.copy(alpha = 0.9f),
                    )
                    if (isPlaying) {
                        Spacer(Modifier.size(4.dp))
                        Text(
                            "Playing",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.9f),
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        station.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (station.isBuiltIn) {
                        Spacer(Modifier.size(8.dp))
                        BuiltInBadge()
                    }
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    text = station.customSubtitle
                        ?: station.playback.order.label().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (station.stats.playCount > 0) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "Played ${station.stats.playCount}×",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.55f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorTile(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
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

private fun stationColor(station: Station): Color {
    if (station.color == 0) return Color(0xFFFF7F1A) // brand amber default
    return Color(station.color.toLong() and 0xFFFFFFFFL)
}

private fun PlayOrder.label(): String = when (this) {
    PlayOrder.Shuffle -> "shuffle"
    PlayOrder.Sequential -> "sequential"
    PlayOrder.SmartShuffle -> "smart shuffle"
    PlayOrder.WeightedRandom -> "weighted"
}
