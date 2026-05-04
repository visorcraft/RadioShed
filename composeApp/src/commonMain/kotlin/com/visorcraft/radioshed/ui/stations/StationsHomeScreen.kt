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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
    onOpenStationDetail: (Long) -> Unit = {},
    onReorderStations: () -> Unit = {},
    stationRepo: StationRepository = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    audioEngine: AudioEngine = koinInject(),
    libraryRepo: LibraryRepository = koinInject(),
    scanner: com.visorcraft.radioshed.scanner.Scanner = koinInject(),
    folderRepo: com.visorcraft.radioshed.data.FolderRepository = koinInject(),
    settings: com.visorcraft.radioshed.data.SettingsStore = koinInject(),
) {
    // Tile-size preference. Held in compose state so the dropdown
    // re-themes the grid instantly without leaving the screen. The
    // four stops cover phone (Small / Medium) through tablet
    // (Large / X-Large) sensibilities.
    var tileSizeDp by remember { mutableStateOf(settings.getStationsTileSizeDp()) }
    // Filter & built-in visibility toggles — both purely client-side
    // off the live `stations` list. Filter shows a single inline text
    // field above the grid; visible only when the user has enough
    // stations that scrolling becomes friction (≥9). The built-in
    // toggle hides the seeded preset stations so power users with a
    // dozen custom stations don't have to scroll past the defaults.
    var stationFilter by remember { mutableStateOf("") }
    var hideBuiltIns by remember { mutableStateOf(settings.getStationsHideBuiltIns()) }
    val stations by stationRepo.observeAll().collectAsState(initial = emptyList())
    val currentStation by stationPlayback.currentStation.collectAsState()
    val playerState by audioEngine.state.collectAsState()
    val nowPlaying by audioEngine.nowPlaying.collectAsState()
    val scope = rememberCoroutineScope()

    // Recently played snapshot. We re-query whenever the active station
    // changes (so a tap immediately refreshes the row's order) — the
    // underlying query is cheap (LIMIT 6, indexed by last_played_at).
    var recent by remember { mutableStateOf<List<Station>>(emptyList()) }
    // (plays, skips) per station id. Used to show skip rate on the
    // station tile subtitle when there's enough history to be
    // statistically meaningful (≥5 plays). Refreshes on the same
    // signal as the recent-played row.
    var completionRates by remember {
        mutableStateOf<Map<Long, Pair<Long, Long>>>(emptyMap())
    }
    // Per-station up-to-4 album artwork URIs for the auto-collage that
    // replaces the gradient hero on tiles with no custom artwork. Pulled
    // in batch (one query per station, but only for stations with at
    // least one play). Refreshes on the same signal as the recent row
    // so a fresh play immediately propagates.
    var stationCollages by remember { mutableStateOf<Map<Long, List<String>>>(emptyMap()) }
    LaunchedEffect(currentStation?.id, playerState) {
        recent = stationRepo.listRecentlyPlayed(limit = 6L)
        completionRates = stationRepo.stationCompletionRates()
    }
    LaunchedEffect(stations.map { it.id }, currentStation?.id) {
        stationCollages = stations.associate { s ->
            s.id to (
                if (s.stats.playCount > 0L) stationRepo.recentAlbumArtwork(s.id, 4L)
                else emptyList()
            )
        }
    }

    // Recently-added tracks — pulled from the library by file ctime
    // (when the file was first scanned). Refreshes when the active
    // track changes so a fresh scan/index shows up promptly.
    //
    // Pre-resolve a Map<albumId, artworkUri> in the same pass so the
    // tile renderer can show cover art without doing one DB lookup
    // per recompose. The set is small (≤ 12 tracks → ≤ 12 albums) so
    // we just iterate.
    var recentlyAdded by remember { mutableStateOf<List<Track>>(emptyList()) }
    var recentlyAddedArtwork by remember { mutableStateOf<Map<Long, String?>>(emptyMap()) }
    var rediscover by remember { mutableStateOf<List<Track>>(emptyList()) }
    var rediscoverArtwork by remember { mutableStateOf<Map<Long, String?>>(emptyMap()) }

    // Reload Recently Added + Rediscover whenever the library track
    // count changes (a scan completed) OR the active track changes
    // (Rediscover should re-evaluate after a play crosses the
    // 30-day-ago threshold). Previously the dep was only
    // nowPlaying?.trackId, which meant a fresh folder scan didn't
    // surface in the carousel until the user changed tracks.
    val libraryTrackCount by libraryRepo.observeTrackCount().collectAsState(initial = -1L)
    LaunchedEffect(nowPlaying?.trackId, libraryTrackCount) {
        if (libraryTrackCount < 0L) return@LaunchedEffect
        recentlyAdded = libraryRepo.newestTracks(limit = 12L)
        recentlyAddedArtwork = recentlyAdded
            .mapNotNull { it.albumId }
            .distinct()
            .associateWith { id -> libraryRepo.getAlbumById(id)?.artworkUri }

        // Rediscover: tracks played at least once but not in the last
        // 30 days. Resolves track ids to full Track objects (skipping
        // any that have been removed from the library since the play
        // was recorded). 30-day cutoff matches Spotify's "On Repeat" /
        // "Repeat Rewind" rotation cadence — past that, the track has
        // been gone long enough to feel fresh again.
        val cutoff = com.visorcraft.radioshed.data.nowMillis() -
            REDISCOVER_THRESHOLD_DAYS * 86_400_000L
        rediscover = stationRepo.rediscoverTrackIds(
            olderThanMs = cutoff,
            limit = 12L,
        ).mapNotNull { libraryRepo.getTrackById(it) }
        rediscoverArtwork = rediscover
            .mapNotNull { it.albumId }
            .distinct()
            .associateWith { id -> libraryRepo.getAlbumById(id)?.artworkUri }
    }

    // Long-press → action sheet state. Holds the station id whose sheet
    // is currently visible; null when no sheet is open.
    var actionsForStationId by remember { mutableStateOf<Long?>(null) }
    var deleteConfirmStationId by remember { mutableStateOf<Long?>(null) }
    // Per-station stats reset confirmation. Distinct from delete —
    // this leaves the station configured and just zeroes its
    // play_count / skip_rate / history rows. Useful when the user
    // wants a clean slate on a preset that's accumulated bad data.
    var resetStatsStationId by remember { mutableStateOf<Long?>(null) }
    // Pull-to-refresh-rescan: lets the user trigger a fresh library
    // scan from Stations Home without navigating to Library Settings.
    // Useful when the user just dropped new files into a folder and
    // wants to see them surface in stations immediately.
    var rescanInFlight by remember { mutableStateOf(false) }
    val triggerRescan: () -> Unit = {
        if (!rescanInFlight) {
            rescanInFlight = true
            scope.launch {
                try {
                    val targets = folderRepo.list()
                    targets.forEach { folder ->
                        runCatching {
                            scanner.scanFolder(folder.id).collect { /* progress ignored */ }
                        }
                    }
                } finally {
                    rescanInFlight = false
                }
            }
        }
    }

    var tileSizeMenuOpen by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            // Slim top bar — back affordance + tile-size selector. The
            // page title moves into a richer header inside the grid so
            // it can reflow with the content and breathe more on wide
            // screens; the top bar carries only what needs to stay
            // pinned (nav + density override).
            TopAppBar(
                title = {},
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    Box {
                        IconButton(onClick = { tileSizeMenuOpen = true }) {
                            Icon(
                                imageVector = Icons.Filled.GridView,
                                contentDescription = "Tile size",
                            )
                        }
                        DropdownMenu(
                            expanded = tileSizeMenuOpen,
                            onDismissRequest = { tileSizeMenuOpen = false },
                        ) {
                            TILE_SIZE_STOPS.forEach { (label, dp) ->
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(label, modifier = Modifier.weight(1f))
                                            if (tileSizeDp == dp) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        tileSizeDp = dp
                                        settings.setStationsTileSizeDp(dp)
                                        tileSizeMenuOpen = false
                                    },
                                )
                            }
                            androidx.compose.material3.HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "Hide built-in stations",
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (hideBuiltIns) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    hideBuiltIns = !hideBuiltIns
                                    settings.setStationsHideBuiltIns(hideBuiltIns)
                                    tileSizeMenuOpen = false
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
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            val isCompact = maxWidth < 600.dp
            val outerPadding = if (isCompact) 16.dp else 24.dp

            if (stations.isEmpty()) {
                EmptyState(
                    isCompact = isCompact,
                    onOpenLibrary = onBack,
                    modifier = Modifier.fillMaxSize().padding(outerPadding),
                )
                return@BoxWithConstraints
            }

            // On compact (phone-portrait) widths the single-column
            // layout always wins — the tile-size selector is a
            // medium+ affordance, since under ~600dp width even the
            // smallest stop would still produce one column.
            val columns = if (isCompact) GridCells.Fixed(1)
                          else GridCells.Adaptive(minSize = tileSizeDp.dp)

            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = rescanInFlight,
                onRefresh = triggerRescan,
                modifier = Modifier.fillMaxSize(),
            ) {
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
                        builtInsHidden = hideBuiltIns,
                        isCompact = isCompact,
                        onAddStation = onAddStation,
                        onReorder = onReorderStations,
                        onSurpriseMe = {
                            // Pick one available track at random and
                            // play it as a single-item queue. Keeps the
                            // "Stations Home" mental model intact —
                            // user-driven discovery without committing
                            // to a station's rules.
                            scope.launch {
                                val track = libraryRepo.randomAvailableTrack() ?: return@launch
                                stationPlayback.playTracks(listOf(track), startIndex = 0)
                                onOpenNowPlaying()
                            }
                        },
                    )
                }

                // Inline filter field above the grid. Only surfaces
                // when the grid is busy enough that scroll-to-find
                // is a real friction point (≥9 stations) so a fresh-
                // install user with the seeded preset count doesn't
                // see clutter.
                if (stations.size >= 9) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        androidx.compose.material3.OutlinedTextField(
                            value = stationFilter,
                            onValueChange = { stationFilter = it },
                            placeholder = { Text("Filter stations…") },
                            singleLine = true,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = null,
                                )
                            },
                            trailingIcon = if (stationFilter.isNotEmpty()) {
                                {
                                    IconButton(onClick = { stationFilter = "" }) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "Clear filter",
                                        )
                                    }
                                }
                            } else null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
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
                            artworkByAlbumId = recentlyAddedArtwork,
                            onPlay = { idx ->
                                stationPlayback.playTracks(recentlyAdded, startIndex = idx)
                            },
                        )
                    }
                }

                if (rediscover.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        TrackCarouselRow(
                            label = "Rediscover",
                            tracks = rediscover,
                            artworkByAlbumId = rediscoverArtwork,
                            onPlay = { idx ->
                                stationPlayback.playTracks(rediscover, startIndex = idx)
                            },
                        )
                    }
                }

                // Apply hide-built-ins toggle and the substring filter
                // before rendering tiles. Filter checks both name and
                // customSubtitle so users can search "tag-driven" or
                // "60s" if they remember the subtitle but not the name.
                val displayedStations = stations
                    .let { src ->
                        if (hideBuiltIns) src.filterNot { it.isBuiltIn } else src
                    }
                    .let { src ->
                        val q = stationFilter.trim()
                        if (q.isBlank()) src
                        else src.filter {
                            it.name.contains(q, ignoreCase = true) ||
                                (it.customSubtitle?.contains(q, ignoreCase = true) == true)
                        }
                    }
                // Inline empty hint when an active filter (Hide
                // built-ins toggle, or text filter) has reduced the
                // visible grid to nothing. The full-screen EmptyState
                // only fires for an actually-empty stations table;
                // here we want a softer reset hint that points the
                // user at how to recover. Spans the full grid width
                // so it isn't squeezed into a single tile slot.
                if (displayedStations.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp, horizontal = outerPadding),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = if (hideBuiltIns && stationFilter.isBlank()) {
                                    "All stations are built-in. Tap + to add a custom station, " +
                                        "or untoggle \"Hide built-ins\" in the grid menu to see them."
                                } else if (stationFilter.isNotBlank()) {
                                    "No stations match \"${stationFilter.trim()}\"."
                                } else {
                                    "No stations to show."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
                items(displayedStations, key = { it.id }) { station ->
                    val rate = completionRates[station.id]
                    val skipRateLine = rate?.let { (plays, skips) ->
                        // Display only when we've got enough plays for
                        // the percentage to mean something — a single
                        // skip on a 1-play station rendering "100%
                        // skip" is just noise.
                        if (plays >= 5L) {
                            val pct = (skips * 100L / plays).toInt()
                            "$plays plays · $pct% skip"
                        } else null
                    }
                    if (isCompact) {
                        StationListRow(
                            station = station,
                            isCurrent = station.id == currentStation?.id,
                            isPlaying = station.id == currentStation?.id &&
                                playerState == PlayerState.Playing,
                            statsLine = skipRateLine,
                            collageArtwork = stationCollages[station.id].orEmpty(),
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
                            statsLine = skipRateLine,
                            collageArtwork = stationCollages[station.id].orEmpty(),
                            onPlay = {
                                scope.launch { stationPlayback.playStation(station.id) }
                            },
                            onEdit = { actionsForStationId = station.id },
                        )
                    }
                }
            }
            } // PullToRefreshBox
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
                onOpenDetail = {
                    actionsForStationId = null
                    onOpenStationDetail(id)
                },
                onEdit = {
                    actionsForStationId = null
                    onEditStation(id)
                },
                onDuplicate = {
                    actionsForStationId = null
                    scope.launch {
                        // Drop the user straight into the editor so
                        // they can tweak the clone immediately —
                        // duplicating without a follow-up edit is
                        // rare in practice (the whole point of a
                        // copy is to change something).
                        val newId = stationRepo.duplicate(id)
                        if (newId != null) onEditStation(newId)
                    }
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
                onMoveToTop = {
                    actionsForStationId = null
                    scope.launch {
                        stationRepo.moveStationToEnd(id, stations.map { it.id }, toTop = true)
                    }
                },
                onMoveToBottom = {
                    actionsForStationId = null
                    scope.launch {
                        stationRepo.moveStationToEnd(id, stations.map { it.id }, toTop = false)
                    }
                },
                canMoveUp = stations.indexOfFirst { it.id == id } > 0,
                canMoveDown = stations.indexOfFirst { it.id == id } in 0 until (stations.size - 1),
                onResetStats = {
                    actionsForStationId = null
                    resetStatsStationId = id
                },
                onDelete = {
                    actionsForStationId = null
                    deleteConfirmStationId = id
                },
            )
        }
    }

    resetStatsStationId?.let { id ->
        val station = stations.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { resetStatsStationId = null },
            title = { Text("Reset stats?") },
            text = {
                Text(
                    "Wipes \"${station?.name ?: ""}\"'s play count, skip rate, " +
                        "listened time, and history rows. The station itself, " +
                        "its source rules, and any liked / pinned tracks stay.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    resetStatsStationId = null
                    scope.launch { stationRepo.resetStationStats(id) }
                }) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { resetStatsStationId = null }) { Text("Cancel") }
            },
        )
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
 * (-1 = up, +1 = down) inside a single SQLDelight transaction so a
 * Flow observer firing mid-pair (or a process kill) can't leave both
 * stations sharing the same sort_position.
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
    repo.swapSortPositions(a.id, a.sortPosition, b.id, b.sortPosition)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StationActionSheet(
    station: Station,
    onDismiss: () -> Unit,
    onOpenDetail: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onTogglePin: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveToTop: () -> Unit,
    onMoveToBottom: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onResetStats: () -> Unit,
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
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                label = "Open station",
                onClick = onOpenDetail,
            )
            ActionRow(
                icon = Icons.Filled.Edit,
                label = if (station.isBuiltIn) "Edit (cosmetic only)" else "Edit",
                onClick = onEdit,
            )
            // Duplicate clones the source / playback / cosmetics into a
            // new user station and drops the user straight into the
            // editor on the copy — the canonical "I want this station's
            // rules but with one tweak" workflow.
            ActionRow(
                icon = Icons.Filled.ContentCopy,
                label = "Duplicate",
                onClick = onDuplicate,
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
            // One-shot reorders to the extremes — handy when a station
            // has drifted twenty rows down and the user wants it back
            // on the first screen without tapping Move up nineteen
            // times. Greys out at the boundary the move would be a
            // no-op, mirroring Move up / Move down's enabled logic.
            ActionRow(
                icon = Icons.Filled.VerticalAlignTop,
                label = "Move to top",
                onClick = onMoveToTop,
                enabled = canMoveUp,
            )
            ActionRow(
                icon = Icons.Filled.VerticalAlignBottom,
                label = "Move to bottom",
                onClick = onMoveToBottom,
                enabled = canMoveDown,
            )
            // Reset stats: zero play_count / last_played_at / skip_rate
            // and wipe this station's `station_history` rows. Survives
            // built-in re-seeding so resetting a preset works even
            // though the seeder also re-creates it on launch. Confirmed
            // via a separate dialog so a misclick can't nuke history.
            ActionRow(
                icon = Icons.Filled.RestartAlt,
                label = "Reset stats",
                onClick = onResetStats,
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
    builtInsHidden: Boolean = false,
    isCompact: Boolean,
    onAddStation: () -> Unit,
    onReorder: () -> Unit = {},
    onSurpriseMe: () -> Unit = {},
) {
    val customCount = stationCount - builtInCount
    // When the user has toggled "Hide built-ins" on, the grid only
    // shows custom stations; the subtitle then reads "X custom · Y
    // built-in hidden" so the count actually matches the visible
    // tiles. Without this, the page header reads "10 stations" while
    // the grid shows 4 — confusing.
    val subtitleParts = buildList {
        if (builtInsHidden && builtInCount > 0) {
            add("$customCount custom")
            add("$builtInCount built-in hidden")
        } else {
            add("$stationCount station${if (stationCount == 1) "" else "s"}")
            if (builtInCount > 0) add("$builtInCount built-in")
            if (customCount > 0) add("$customCount custom")
        }
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
        // Surprise-me chip — picks one available track at random and
        // plays it. Only renders when there's a library to draw from
        // (>= one station implies there's been at least a folder
        // scan); the underlying repo call still null-guards against
        // an empty result so a fresh-install user with no scanned
        // music doesn't crash on tap.
        androidx.compose.material3.OutlinedIconButton(
            onClick = onSurpriseMe,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Casino,
                contentDescription = "Play random track",
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.size(8.dp))
        // Reorder is only useful when there's more than one station;
        // hide the affordance entirely on tiny libraries.
        if (stationCount > 1) {
            androidx.compose.material3.OutlinedButton(onClick = onReorder) {
                Icon(
                    imageVector = Icons.Filled.SwapVert,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text("Reorder")
            }
            Spacer(Modifier.size(8.dp))
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
    artworkByAlbumId: Map<Long, String?>,
    onPlay: (Int) -> Unit,
) {
    TrackCarouselRow(
        label = "Recently added",
        tracks = tracks,
        artworkByAlbumId = artworkByAlbumId,
        onPlay = onPlay,
    )
}

/**
 * Generic horizontal track carousel — one stable component used for
 * "Recently added" + "Rediscover" + future similar rows so the visual
 * language stays consistent.
 */
@Composable
private fun TrackCarouselRow(
    label: String,
    tracks: List<Track>,
    artworkByAlbumId: Map<Long, String?>,
    onPlay: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
        )
        Spacer(Modifier.size(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            lazyItems(
                tracks.withIndex().toList(),
                key = { (_, t) -> "$label:${t.id}" },
            ) { (idx, track) ->
                RecentlyAddedTile(
                    track = track,
                    artworkUri = track.albumId?.let { artworkByAlbumId[it] },
                    onTap = { onPlay(idx) },
                )
            }
        }
    }
}

@Composable
private fun RecentlyAddedTile(
    track: Track,
    artworkUri: String?,
    onTap: () -> Unit,
) {
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
            if (artworkUri != null) {
                coil3.compose.AsyncImage(
                    model = artworkUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
private fun EmptyState(
    isCompact: Boolean,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        Spacer(Modifier.size(16.dp))
        // Direct path to Library so the user can register a folder
        // without having to bounce back through Home → Library by
        // hand. Reuses the existing onBack callback (which routes
        // back to Home, where Library is one tap away) — wired
        // straight to a button so the action's discoverable on the
        // same screen the empty state is showing.
        androidx.compose.material3.OutlinedButton(onClick = onOpenLibrary) {
            Text("Go to Library")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StationListRow(
    station: Station,
    isCurrent: Boolean,
    isPlaying: Boolean,
    statsLine: String?,
    collageArtwork: List<String>,
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
            // Custom artwork wins, then auto-collage, then color tile.
            if (station.artworkUri != null) {
                coil3.compose.AsyncImage(
                    model = station.artworkUri,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            } else if (collageArtwork.size >= 4) {
                AlbumCollage(
                    uris = collageArtwork.take(4),
                    modifier = Modifier.size(48.dp),
                )
            } else {
                ColorTile(
                    color = stationColor(station),
                    modifier = Modifier.size(48.dp),
                )
            }
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
                // Prefer the history-derived stats line ("12 plays ·
                // 25% skip") over the bare play count when it's
                // available, since it captures more useful info in
                // the same vertical space.
                val infoLine = statsLine
                    ?: if (station.stats.playCount > 0) {
                        "Played ${station.stats.playCount}× · ${station.playback.order.label()}"
                    } else null
                if (infoLine != null) {
                    Text(
                        infoLine,
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
    statsLine: String?,
    collageArtwork: List<String>,
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
            // Tile header. Precedence:
            //  1. Custom uploaded artwork wins outright.
            //  2. Otherwise, an auto-collage of up-to-4 recent album covers
            //     drives a 2x2 grid (only when we have 4+ distinct albums
            //     in the station's history).
            //  3. Otherwise, a tinted gradient with a preset-specific
            //     hero icon (Whatshot for Latest Hits, MusicNote for
            //     user-created, etc.).
            val presetIcon = presetIconFor(station)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.5f)
                    .background(stationGradient(accent)),
            ) {
                when {
                    station.artworkUri != null -> {
                        coil3.compose.AsyncImage(
                            model = station.artworkUri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    }
                    collageArtwork.size >= 4 -> {
                        AlbumCollage(
                            uris = collageArtwork.take(4),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    else -> {
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
                    }
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
                // Same precedence as the list-row variant: prefer the
                // history-derived stats line when available.
                val tileInfo = statsLine ?: if (station.stats.playCount > 0) {
                    "Played ${station.stats.playCount}×"
                } else null
                if (tileInfo != null) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        tileInfo,
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

/**
 * 2x2 album-cover collage for stations without custom artwork. Caller
 * must pass exactly 4 URIs (typically `take(4)` of the available
 * artwork list — fewer than 4 isn't currently rendered, since the
 * tile call site checks size >= 4 before invoking).
 *
 * The collage's pieces use ContentScale.Crop so non-square covers
 * fill their quadrants without letterboxing. Each quadrant has a 1px
 * gap between it and its neighbours, drawn via a tiny background
 * separator color, which gives the collage a subtle "tile" feel
 * without paying for borders on every render.
 */
@Composable
private fun AlbumCollage(uris: List<String>, modifier: Modifier = Modifier) {
    val gap = 1.dp
    val separator = MaterialTheme.colorScheme.surface
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(separator),
    ) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            CollageCell(uris.getOrNull(0), Modifier.weight(1f).fillMaxHeight())
            Spacer(Modifier.width(gap))
            CollageCell(uris.getOrNull(1), Modifier.weight(1f).fillMaxHeight())
        }
        Spacer(Modifier.height(gap))
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            CollageCell(uris.getOrNull(2), Modifier.weight(1f).fillMaxHeight())
            Spacer(Modifier.width(gap))
            CollageCell(uris.getOrNull(3), Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun CollageCell(uri: String?, modifier: Modifier) {
    if (uri == null) {
        Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    } else {
        coil3.compose.AsyncImage(
            model = uri,
            contentDescription = null,
            modifier = modifier,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
    }
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

/** "Forgotten about it" cutoff for the Rediscover carousel, in days. */
private const val REDISCOVER_THRESHOLD_DAYS: Long = 30L

/**
 * Stops for the Stations Home tile-size selector. Each maps a label
 * to the minimum cell width passed to `GridCells.Adaptive`. The grid
 * still flexes column count to whatever the screen affords; the
 * setting just nudges the lower bound. Below 600dp width the
 * selector is moot — single-column always wins on phones in
 * portrait.
 */
private val TILE_SIZE_STOPS: List<Pair<String, Int>> = listOf(
    "Small" to 200,
    "Medium" to 240,
    "Large" to 280,
    "Extra-large" to 360,
)

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
