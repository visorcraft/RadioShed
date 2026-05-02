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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.PlayerState
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.domain.PlayOrder
import com.visorcraft.radioshed.domain.Station
import com.visorcraft.radioshed.domain.Track
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
    libraryRepo: LibraryRepository = koinInject(),
) {
    val stations by stationRepo.observeAll().collectAsState(initial = emptyList())
    val currentStation by stationPlayback.currentStation.collectAsState()
    val playerState by audioEngine.state.collectAsState()
    val nowPlaying by audioEngine.nowPlaying.collectAsState()
    val scope = rememberCoroutineScope()

    // Recently played snapshot. We re-query whenever the active station
    // changes (so a tap immediately refreshes the row's order) — the
    // underlying query is cheap (LIMIT 6, indexed by last_played_at).
    var recent by remember { mutableStateOf<List<Station>>(emptyList()) }
    LaunchedEffect(currentStation?.id, playerState) {
        recent = stationRepo.listRecentlyPlayed(limit = 6L)
    }

    // Recently-added tracks — pulled from the library by file ctime
    // (when the file was first scanned). Refreshes when the active
    // track changes so a fresh scan/index shows up promptly.
    var recentlyAdded by remember { mutableStateOf<List<Track>>(emptyList()) }
    LaunchedEffect(nowPlaying?.trackId) {
        recentlyAdded = libraryRepo.newestTracks(limit = 12L)
    }

    // Long-press → action sheet state. Holds the station id whose sheet
    // is currently visible; null when no sheet is open.
    var actionsForStationId by remember { mutableStateOf<Long?>(null) }
    var deleteConfirmStationId by remember { mutableStateOf<Long?>(null) }

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

                // Recently Played horizontal row — shown only after the
                // user has actually played something. Filters out the
                // currently-playing station (it's already pinned above
                // in the Continue Listening card).
                val recentToShow = recent
                    .filter { it.id != currentStation?.id && it.stats.lastPlayedAt != null }
                if (recentToShow.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        RecentlyPlayedRow(
                            stations = recentToShow,
                            onPlay = { id -> scope.launch { stationPlayback.playStation(id) } },
                        )
                    }
                }

                // Most Played row — top stations by play_count, computed
                // client-side from the live stations list. Skip if the
                // user hasn't played anything yet, and skip if the only
                // candidate is whatever's currently playing (Continue
                // Listening already has it).
                val mostPlayed = stations
                    .asSequence()
                    .filter { it.stats.playCount > 0 && it.id != currentStation?.id }
                    .sortedByDescending { it.stats.playCount }
                    .take(6)
                    .toList()
                if (mostPlayed.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        MostPlayedRow(
                            stations = mostPlayed,
                            onPlay = { id -> scope.launch { stationPlayback.playStation(id) } },
                        )
                    }
                }

                if (recentlyAdded.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        RecentlyAddedRow(
                            tracks = recentlyAdded,
                            onPlay = { idx ->
                                stationPlayback.playTracks(recentlyAdded, startIndex = idx)
                            },
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
                            onLongPress = { actionsForStationId = station.id },
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
                            onEdit = { actionsForStationId = station.id },
                        )
                    }
                }
            }
        }
    }

    actionsForStationId?.let { id ->
        val station = stations.firstOrNull { it.id == id }
        if (station == null) {
            actionsForStationId = null
        } else {
            StationActionSheet(
                station = station,
                onDismiss = { actionsForStationId = null },
                onEdit = {
                    actionsForStationId = null
                    onEditStation(id)
                },
                onTogglePin = {
                    actionsForStationId = null
                    scope.launch { stationRepo.setPinned(id, !station.isPinned) }
                },
                onMoveUp = {
                    actionsForStationId = null
                    scope.launch { swapStationPosition(stationRepo, stations, id, direction = -1) }
                },
                onMoveDown = {
                    actionsForStationId = null
                    scope.launch { swapStationPosition(stationRepo, stations, id, direction = +1) }
                },
                canMoveUp = stations.indexOfFirst { it.id == id } > 0,
                canMoveDown = stations.indexOfFirst { it.id == id } in 0 until (stations.size - 1),
                onDelete = {
                    actionsForStationId = null
                    deleteConfirmStationId = id
                },
            )
        }
    }

    deleteConfirmStationId?.let { id ->
        val station = stations.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { deleteConfirmStationId = null },
            title = { Text("Delete station?") },
            text = {
                Text(
                    "\"${station?.name ?: ""}\" will be removed. The tracks themselves stay in your library.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteConfirmStationId = null
                    scope.launch { stationRepo.delete(id) }
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmStationId = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Swap [stationId]'s sort_position with the neighbor in [direction]
 * (-1 = up, +1 = down). Position is rewritten on both rows so the
 * change persists across observed re-emissions.
 */
private suspend fun swapStationPosition(
    repo: com.visorcraft.radioshed.data.StationRepository,
    stations: List<Station>,
    stationId: Long,
    direction: Int,
) {
    val idx = stations.indexOfFirst { it.id == stationId }.takeIf { it >= 0 } ?: return
    val targetIdx = idx + direction
    if (targetIdx !in stations.indices) return
    val a = stations[idx]
    val b = stations[targetIdx]
    repo.reorder(a.id, sortPosition = b.sortPosition)
    repo.reorder(b.id, sortPosition = a.sortPosition)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StationActionSheet(
    station: Station,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(
                station.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            ActionRow(
                icon = Icons.Filled.Edit,
                label = if (station.isBuiltIn) "Edit (cosmetic only)" else "Edit",
                onClick = onEdit,
            )
            ActionRow(
                icon = if (station.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                label = if (station.isPinned) "Unpin" else "Pin to top",
                onClick = onTogglePin,
            )
            ActionRow(
                icon = Icons.Filled.ArrowUpward,
                label = "Move up",
                onClick = onMoveUp,
                enabled = canMoveUp,
            )
            ActionRow(
                icon = Icons.Filled.ArrowDownward,
                label = "Move down",
                onClick = onMoveDown,
                enabled = canMoveDown,
            )
            // Built-ins are deletable too — the seeder will re-create them
            // on next launch, so deletion just resets that one. Surfaced
            // without a guard so the user has a one-tap "remove this preset
            // I don't use" workflow without rummaging in settings.
            ActionRow(
                icon = Icons.Filled.Delete,
                label = "Delete",
                onClick = onDelete,
                danger = true,
            )
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
        )
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

/**
 * Horizontal row of recently-played station chips. Compact "stamp"-style
 * tiles — colored circle + station name. Tapping starts the station
 * directly without opening the editor / detail view.
 */
@Composable
private fun RecentlyPlayedRow(
    stations: List<Station>,
    onPlay: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Text(
            text = "Recently played",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
        )
        Spacer(Modifier.size(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            lazyItems(stations, key = { it.id }) { station ->
                RecentChip(station = station, onTap = { onPlay(station.id) })
            }
        }
    }
}

/**
 * Horizontal "Recently Added" row. Shows newest-by-ctime tracks with
 * cover art when available; tap a tile to play it as the head of the
 * recently-added queue (skip-next walks the rest of the row).
 */
@Composable
private fun RecentlyAddedRow(
    tracks: List<Track>,
    onPlay: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Text(
            text = "Recently added",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
        )
        Spacer(Modifier.size(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            lazyItems(tracks.withIndex().toList(), key = { (_, t) -> t.id }) { (idx, track) ->
                RecentlyAddedTile(track = track, onTap = { onPlay(idx) })
            }
        }
    }
}

@Composable
private fun RecentlyAddedTile(track: Track, onTap: () -> Unit) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable(onClick = onTap),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            // We don't have direct access to the album's artwork URI from
            // the Track domain object here without a DB hit, so we fall
            // back to the music-note placeholder. A future pass could
            // join album.artwork_uri into the newestTracks query.
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(6.dp))
        Text(
            text = track.title.ifBlank { track.fileName },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MostPlayedRow(
    stations: List<Station>,
    onPlay: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Text(
            text = "Most played",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
        )
        Spacer(Modifier.size(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            lazyItems(stations, key = { it.id }) { station ->
                MostPlayedChip(station = station, onTap = { onPlay(station.id) })
            }
        }
    }
}

@Composable
private fun MostPlayedChip(station: Station, onTap: () -> Unit) {
    val accent = stationColor(station)
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable(onClick = onTap),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.4f)
                .clip(RoundedCornerShape(12.dp))
                .background(stationGradient(accent)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = presetIconFor(station),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = Color.White.copy(alpha = 0.92f),
            )
            // Top-left chip showing play count — visual hierarchy hint
            // that this row is *ranked* by listening, not just recent.
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
                color = Color.Black.copy(alpha = 0.45f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    "${station.stats.playCount}×",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(
            text = station.name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RecentChip(station: Station, onTap: () -> Unit) {
    val accent = stationColor(station)
    Column(
        modifier = Modifier
            .width(96.dp)
            .clickable(onClick = onTap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(36.dp))
                .background(stationGradient(accent)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = presetIconFor(station),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = Color.White.copy(alpha = 0.92f),
            )
        }
        Spacer(Modifier.size(6.dp))
        Text(
            text = station.name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
            // Tile header — multi-stop diagonal gradient with a thematic
            // icon centered, and a subtle "now playing" pill in the corner
            // when this station is the active one. Built-in presets get a
            // preset-specific icon (Whatshot for "Latest Hits" etc.); user-
            // created stations fall back to a generic music note.
            val presetIcon = presetIconFor(station)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.5f)
                    .background(stationGradient(accent)),
            ) {
                // Centered hero icon
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = presetIcon,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = Color.White.copy(alpha = 0.92f),
                    )
                }
                // Now-playing chip in the corner
                if (isPlaying) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp),
                        color = Color.Black.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color.White,
                            )
                            Spacer(Modifier.size(4.dp))
                            Text(
                                "Playing",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                            )
                        }
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

/**
 * Three-stop diagonal gradient: full-saturation accent at top-left, a
 * darkened version at center, lighter-blended at bottom-right. Reads as
 * "designed" rather than "placeholder" without needing actual artwork
 * for each station.
 */
private fun stationGradient(accent: Color): Brush {
    // Slightly darken/lighten via alpha + black/white mix at the stops.
    val darker = Color(
        red = accent.red * 0.55f,
        green = accent.green * 0.55f,
        blue = accent.blue * 0.65f,
        alpha = 1f,
    )
    val lighter = Color(
        red = (accent.red + 0.1f).coerceAtMost(1f),
        green = (accent.green + 0.1f).coerceAtMost(1f),
        blue = (accent.blue + 0.1f).coerceAtMost(1f),
        alpha = 0.85f,
    )
    return Brush.linearGradient(
        colorStops = arrayOf(
            0f to accent,
            0.55f to darker,
            1f to lighter,
        ),
    )
}

/**
 * Maps a station to a thematic Material icon. Built-in presets are
 * matched by name (the seeded names are stable, see `StationPresets.kt`);
 * user-created stations and unmatched names fall back to a generic
 * music-library icon. Deliberately name-based not id-based so users who
 * rename a built-in keep its icon, and users who create their own
 * "Top Rated" get the matching star icon for free.
 */
private fun presetIconFor(station: Station): androidx.compose.ui.graphics.vector.ImageVector {
    val n = station.name.lowercase()
    return when {
        "latest" in n || "hits" in n -> Icons.Filled.Whatshot
        "discover" in n -> Icons.Filled.Explore
        "top" in n && "rated" in n -> Icons.Filled.Star
        "recent" in n || "new" in n -> Icons.Filled.NewReleases
        "all music" in n || "everything" in n -> Icons.Filled.LibraryMusic
        "favorite" in n || "loved" in n -> Icons.Filled.AutoAwesome
        else -> Icons.Filled.MusicNote
    }
}

private fun PlayOrder.label(): String = when (this) {
    PlayOrder.Shuffle -> "shuffle"
    PlayOrder.Sequential -> "sequential"
    PlayOrder.SmartShuffle -> "smart shuffle"
    PlayOrder.WeightedRandom -> "weighted"
}
