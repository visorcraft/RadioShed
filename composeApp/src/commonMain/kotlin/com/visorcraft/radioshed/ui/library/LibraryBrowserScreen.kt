package com.visorcraft.radioshed.ui.library

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.runtime.produceState
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
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.LibraryTrackSort
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.domain.Album
import com.visorcraft.radioshed.domain.Artist
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.nav.PlatformBackHandler
import com.visorcraft.radioshed.stations.StationPlayback
import com.visorcraft.radioshed.ui.playlists.TrackContextSheet
import com.visorcraft.radioshed.ui.util.formatDurationMs
import com.visorcraft.radioshed.ui.util.NowPlayingIndicator
import com.visorcraft.radioshed.ui.util.rememberHapticTick
import com.visorcraft.radioshed.ui.util.TooltipIconButton
import com.visorcraft.radioshed.ui.util.onRightClick
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.compose.foundation.lazy.grid.items as gridItems

/** Cap for shuffle-all to avoid loading every track into memory at once. */
private const val SHUFFLE_ALL_CAP = 5_000

/**
 * Library Browser — three tabs (Songs / Artists / Albums) for navigating
 * the indexed collection by hand instead of through stations.
 *
 * Drill-in for Artists / Albums is implemented as a "filter chip" model
 * rather than separate screens: tapping an artist switches to the Songs
 * tab and applies the artist filter, with a dismissible chip at the top.
 * This keeps state management simple (one screen, one stack of state) and
 * makes "back" semantics obvious — the user clears the chip to go back to
 * the unfiltered list.
 *
 * Tap-to-play on a Song row hands the track straight to AudioEngine as a
 * one-off Playable (trackId is real, but we aren't building a station
 * queue around it). Future improvement: tap-to-play seeds a temporary
 * "queue from this point" so prev/next walk the visible list.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryBrowserScreen(
    onBack: () -> Unit,
    onManage: () -> Unit,
    onArtistTap: (Long) -> Unit = {},
    onAlbumTap: (Long) -> Unit = {},
    onGenreTap: (String) -> Unit = {},
    onEditStation: (Long) -> Unit = {},
    libraryRepo: LibraryRepository = koinInject(),
    audioEngine: AudioEngine = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    settings: SettingsStore = koinInject(),
    stationRepo: com.visorcraft.radioshed.data.StationRepository = koinInject(),
    ratingRepo: com.visorcraft.radioshed.data.RatingRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    var contextTrackId by remember { mutableStateOf<Long?>(null) }
    // Long-press on an album / artist tile opens the corresponding
    // context sheet — Play / Shuffle / Play next / Add to queue.
    // Sister surface to TrackContextSheet, but at album/artist scope.
    var contextAlbum by remember { mutableStateOf<Album?>(null) }
    var contextArtist by remember { mutableStateOf<Artist?>(null) }
    // Batch-selection state (Songs tab only).
    var selectedTrackIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val inSelectionMode = selectedTrackIds.isNotEmpty()
    var showBatchEditDialog by remember { mutableStateOf(false) }
    // Now-playing indicator on Songs rows: collect the engine's
    // current track id so each row can self-tag.
    val nowPlaying by audioEngine.nowPlaying.collectAsStateWithLifecycle()
    val currentTrackId = nowPlaying?.trackId
    // Re-load paged lists whenever the total track count changes — covers
    // the "user scanned a folder while Library was open" case.
    // Hoist the repository flows so collectAsStateWithLifecycle doesn't
    // unsubscribe/resubscribe on every recomposition. Count flows are
    // cheap; observing the full artist/album tables here would hold the
    // entire library in memory, so we only keep counts.
    val trackCountFlow = remember(libraryRepo) { libraryRepo.observeTrackCount() }
    val artistCountFlow = remember(libraryRepo) { libraryRepo.observeArtistCount() }
    val albumCountFlow = remember(libraryRepo) { libraryRepo.observeAlbumCount() }
    val genreCountFlow = remember(libraryRepo) { libraryRepo.observeGenreCount() }
    val trackCount by trackCountFlow.collectAsStateWithLifecycle(initialValue = -1L)

    var songsFilter by remember { mutableStateOf("") }
    var sortOrder by remember {
        mutableStateOf(
            runCatching { SongSort.valueOf(settings.getLibrarySongSort()) }.getOrDefault(SongSort.TitleAsc),
        )
    }
    var artistSort by remember {
        mutableStateOf(
            runCatching { ArtistSort.valueOf(settings.getLibraryArtistSort()) }
                .getOrDefault(ArtistSort.NameAsc),
        )
    }
    var albumSort by remember {
        mutableStateOf(
            runCatching { AlbumSort.valueOf(settings.getLibraryAlbumSort()) }
                .getOrDefault(AlbumSort.TitleAsc),
        )
    }
    var genreSort by remember {
        mutableStateOf(
            runCatching { GenreSort.valueOf(settings.getLibraryGenreSort()) }
                .getOrDefault(GenreSort.NameAsc),
        )
    }

    // Album titles for the currently visible Songs rows. We fetch only
    // the ids present in the visible list instead of observing the full
    // album table, which would hold the entire library in memory.
    var albumNameById by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }

    // Most-played artist/album grids need the globally-ordered top-N
    // lists. We cache them once per sort/refresh so the paged loader can
    // slice by offset/limit instead of re-scanning history per page.
    var mostPlayedArtists by remember { mutableStateOf<List<com.visorcraft.radioshed.data.TopArtistEntry>>(emptyList()) }
    var mostPlayedAlbums by remember { mutableStateOf<List<com.visorcraft.radioshed.data.TopAlbumEntry>>(emptyList()) }
    LaunchedEffect(artistSort, trackCount) {
        mostPlayedArtists = if (artistSort == ArtistSort.MostPlayed && trackCount >= 0L) {
            stationRepo.topPlayedArtists(Long.MAX_VALUE)
        } else {
            emptyList()
        }
    }
    LaunchedEffect(albumSort, trackCount) {
        mostPlayedAlbums = if (albumSort == AlbumSort.MostPlayed && trackCount >= 0L) {
            stationRepo.topPlayedAlbums(Long.MAX_VALUE)
        } else {
            emptyList()
        }
    }

    val songsPager = remember(sortOrder, songsFilter, trackCount) { PagedListState<Track>() }
    LaunchedEffect(sortOrder, songsFilter, trackCount) {
        if (trackCount < 0L) return@LaunchedEffect
        songsPager.reset()
        songsPager.loadPage { limit, offset ->
            libraryRepo.listAvailableTracksPaged(
                limit = limit,
                offset = offset,
                sort = sortOrder.toLibrarySort(),
                filter = songsFilter.takeIf { it.isNotBlank() },
            )
        }
    }

    val artistsPager = remember(artistSort, trackCount) { PagedListState<Artist>() }
    LaunchedEffect(artistSort, trackCount) {
        if (trackCount < 0L) return@LaunchedEffect
        artistsPager.reset()
        artistsPager.loadPage { limit, offset ->
            when (artistSort) {
                ArtistSort.NameAsc -> libraryRepo.listArtistsPaged(limit, offset)
                ArtistSort.NameDesc -> libraryRepo.listArtistsPagedDesc(limit, offset)
                ArtistSort.MostPlayed -> {
                    val entries = mostPlayedArtists.drop(offset.toInt()).take(limit.toInt())
                    libraryRepo.artistsByIds(entries.map { it.id }).values.toList()
                        .sortedBy { artist -> entries.indexOfFirst { it.id == artist.id } }
                }
            }
        }
    }

    val albumsPager = remember(albumSort, trackCount) { PagedListState<Album>() }
    LaunchedEffect(albumSort, trackCount) {
        if (trackCount < 0L) return@LaunchedEffect
        albumsPager.reset()
        albumsPager.loadPage { limit, offset ->
            when (albumSort) {
                AlbumSort.TitleAsc -> libraryRepo.listAlbumsPaged(limit, offset)
                AlbumSort.TitleDesc -> libraryRepo.listAlbumsPagedDesc(limit, offset)
                AlbumSort.YearAsc -> libraryRepo.listAlbumsPagedByYear(limit, offset, ascending = true)
                AlbumSort.YearDesc -> libraryRepo.listAlbumsPagedByYear(limit, offset, ascending = false)
                AlbumSort.MostPlayed -> {
                    val entries = mostPlayedAlbums.drop(offset.toInt()).take(limit.toInt())
                    libraryRepo.albumsByIds(entries.map { it.id }).values.toList()
                        .sortedBy { album -> entries.indexOfFirst { it.id == album.id } }
                }
            }
        }
    }

    val genresPager = remember(genreSort, trackCount) { PagedListState<String>() }
    LaunchedEffect(genreSort, trackCount) {
        if (trackCount < 0L) return@LaunchedEffect
        genresPager.reset()
        genresPager.loadPage { limit, offset ->
            val values = when (genreSort) {
                GenreSort.NameAsc -> libraryRepo.distinctTagValuesPaged("GENRE", limit, offset)
                GenreSort.NameDesc -> libraryRepo.distinctTagValuesPagedDesc("GENRE", limit, offset)
            }
            values.filter { it.isNotBlank() }
        }
    }

    // Persist + restore tab + sort selection across navigations. Filters
    // (artist / album) are intentionally NOT persisted — they're transient
    // drill-in state that should reset when the user comes back fresh.
    var selectedTab by remember {
        mutableStateOf(LibraryTab.entries.getOrElse(settings.getLibraryTabIndex()) { LibraryTab.Songs })
    }
    var sortMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(selectedTab) {
        settings.setLibraryTabIndex(selectedTab.ordinal)
        selectedTrackIds = emptySet()
    }
    LaunchedEffect(sortOrder) { settings.setLibrarySongSort(sortOrder.name) }
    LaunchedEffect(artistSort) { settings.setLibraryArtistSort(artistSort.name) }
    LaunchedEffect(albumSort) { settings.setLibraryAlbumSort(albumSort.name) }
    LaunchedEffect(genreSort) { settings.setLibraryGenreSort(genreSort.name) }

    PlatformBackHandler(enabled = inSelectionMode) {
        selectedTrackIds = emptySet()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (inSelectionMode) {
                        Text("${selectedTrackIds.size} selected")
                    } else {
                        // Subtitle line shows the live counts beneath the
                        // header so users get a sense of library size at a
                        // glance — and the count helps confirm that a fresh
                        // scan picked things up.
                        Column {
                            Text("Library", style = MaterialTheme.typography.titleSmall)
                            val artistCount by artistCountFlow.collectAsStateWithLifecycle(initialValue = 0L)
                            val albumCount by albumCountFlow.collectAsStateWithLifecycle(initialValue = 0L)
                            val genreCount by genreCountFlow.collectAsStateWithLifecycle(initialValue = 0L)
                            val countLabel =
                                buildString {
                                    if (trackCount >= 0L) append("$trackCount tracks")
                                    if (artistCount > 0L) append(" · $artistCount artists")
                                    if (albumCount > 0L) append(" · $albumCount albums")
                                }
                            Text(
                                text = countLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    if (inSelectionMode) {
                        TextButton(onClick = { selectedTrackIds = emptySet() }) {
                            Text("Clear")
                        }
                    } else {
                        if (selectedTab == LibraryTab.Albums && albumsPager.items.isNotEmpty()) {
                            // Random-album entry point on the Albums view: picks
                            // one album from the visible grid at random and
                            // queues all of its tracks in album order. Sister
                            // action to the Songs tab's Shuffle-all — same idea
                            // ("surprise me, no station setup needed") but at
                            // album granularity, which is how plenty of users
                            // think about their music.
                            TooltipIconButton(
                                tooltip = "Random album",
                                onClick = {
                                    val pick = albumsPager.items.random()
                                    scope.launch {
                                        val tracks = libraryRepo.tracksForAlbum(pick.id)
                                        if (tracks.isNotEmpty()) {
                                            stationPlayback.playTracks(tracks, startIndex = 0)
                                        }
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Casino,
                                    contentDescription = "Random album",
                                )
                            }
                        }
                        if (selectedTab == LibraryTab.Artists && artistsPager.items.isNotEmpty()) {
                            // Mirror the Songs-tab sort dropdown for the
                            // Artists list. Same chevron-style icon so
                            // the affordance reads as "sort" without
                            // having to add a label that competes with
                            // the title row.
                            Box {
                                TooltipIconButton(
                                    tooltip = "Sort",
                                    onClick = { sortMenuOpen = true },
                                ) {
                                    Text(
                                        text = "↕",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                                DropdownMenu(
                                    expanded = sortMenuOpen,
                                    onDismissRequest = { sortMenuOpen = false },
                                ) {
                                    ArtistSort.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.label) },
                                            onClick = {
                                                artistSort = option
                                                sortMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        if (selectedTab == LibraryTab.Albums && albumsPager.items.isNotEmpty()) {
                            Box {
                                TooltipIconButton(
                                    tooltip = "Sort",
                                    onClick = { sortMenuOpen = true },
                                ) {
                                    Text(
                                        text = "↕",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                                DropdownMenu(
                                    expanded = sortMenuOpen,
                                    onDismissRequest = { sortMenuOpen = false },
                                ) {
                                    AlbumSort.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.label) },
                                            onClick = {
                                                albumSort = option
                                                sortMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        if (selectedTab == LibraryTab.Genres && genresPager.items.isNotEmpty()) {
                            Box {
                                TooltipIconButton(
                                    tooltip = "Sort",
                                    onClick = { sortMenuOpen = true },
                                ) {
                                    Text(
                                        text = "↕",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                                DropdownMenu(
                                    expanded = sortMenuOpen,
                                    onDismissRequest = { sortMenuOpen = false },
                                ) {
                                    GenreSort.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.label) },
                                            onClick = {
                                                genreSort = option
                                                sortMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        if (selectedTab == LibraryTab.Songs && songsPager.items.isNotEmpty()) {
                            // Shuffle-all on the visible Songs view: kicks
                            // off an ad-hoc queue from the entire current
                            // (filtered + sorted) track list, randomized.
                            // Distinct from the per-station SmartShuffle —
                            // this is the "play everything I own, surprise
                            // me" entry point that doesn't require building
                            // a station first.
                            // Long-press: "Random song" — picks a single
                            // track instead of seeding the whole library.
                            // Faster surprise without committing to a
                            // session-length queue.
                            Box(
                                modifier =
                                    Modifier.combinedClickable(
                                        onClick = {
                                            scope.launch {
                                                val ids = libraryRepo.listAvailableTrackIds(songsFilter.takeIf { it.isNotBlank() })
                                                    .shuffled()
                                                    .take(SHUFFLE_ALL_CAP)
                                                val tracks = libraryRepo.tracksByIds(ids).values.toList()
                                                stationPlayback.playTracks(tracks.shuffled(), startIndex = 0)
                                            }
                                        },
                                        onLongClick = {
                                            scope.launch {
                                                val pick = libraryRepo.randomAvailableTrack()
                                                if (pick != null) {
                                                    stationPlayback.playTracks(listOf(pick), startIndex = 0)
                                                }
                                            }
                                        },
                                    ),
                            ) {
                                TooltipIconButton(
                                    tooltip = "Shuffle all (long-press for one random)",
                                    onClick = {},
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Shuffle,
                                        contentDescription = "Shuffle all (long-press for one random)",
                                    )
                                }
                            }
                            Box {
                                TooltipIconButton(
                                    tooltip = "Sort",
                                    onClick = { sortMenuOpen = true },
                                ) {
                                    Text(
                                        text = "↕",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                                DropdownMenu(
                                    expanded = sortMenuOpen,
                                    onDismissRequest = { sortMenuOpen = false },
                                ) {
                                    SongSort.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.label) },
                                            onClick = {
                                                sortOrder = option
                                                sortMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        TooltipIconButton(
                            tooltip = "Manage folders",
                            onClick = onManage,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Manage folders",
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
        bottomBar = {
            if (inSelectionMode && selectedTab == LibraryTab.Songs) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 3.dp,
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${selectedTrackIds.size} selected",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { showBatchEditDialog = true },
                        ) {
                            Text("Edit tags")
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SecondaryTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                // Tab labels carry their own count badge so users can
                // tell at a glance how big each pile is. The count
                // adjusts as the library grows; rendered inline as
                // "Songs · 1234" style rather than a Material badge
                // because the badge clips at the tab's right edge on
                // narrow phones.
                val artistCount by artistCountFlow.collectAsStateWithLifecycle(initialValue = 0L)
                val albumCount by albumCountFlow.collectAsStateWithLifecycle(initialValue = 0L)
                val genreCount by genreCountFlow.collectAsStateWithLifecycle(initialValue = 0L)
                LibraryTab.entries.forEach { tab ->
                    val count =
                        when (tab) {
                            LibraryTab.Songs -> trackCount.coerceAtLeast(0L)
                            LibraryTab.Artists -> artistCount
                            LibraryTab.Albums -> albumCount
                            LibraryTab.Genres -> genreCount
                        }
                    val label = if (count > 0L) "${tab.label} · $count" else tab.label
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(label) },
                    )
                }
            }

            when (selectedTab) {
                LibraryTab.Songs -> {
                    // Quick text filter on the visible Songs view — substring
                    // match against title or filename, case-insensitive.
                    // Composes with sort + artist/album filters; gives users
                    // a "find that song" affordance without leaving the
                    // browser for the global Search screen. State hoisted
                    // to the function scope so the filter survives a tab
                    // switch.
                    androidx.compose.material3.OutlinedTextField(
                        value = songsFilter,
                        onValueChange = { songsFilter = it },
                        placeholder = { Text("Filter songs in this view…") },
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Filled.Search,
                                contentDescription = null,
                            )
                        },
                        trailingIcon =
                            if (songsFilter.isNotEmpty()) {
                                {
                                    TooltipIconButton(
                                        tooltip = "Clear filter",
                                        onClick = { songsFilter = "" },
                                    ) {
                                        Icon(
                                            imageVector = androidx.compose.material.icons.Icons.Filled.Clear,
                                            contentDescription = "Clear filter",
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    val onSongLongPress: (Track) -> Unit = { track ->
                        if (!inSelectionMode) {
                            contextTrackId = track.id
                        } else {
                            selectedTrackIds =
                                if (track.id in selectedTrackIds) {
                                    selectedTrackIds - track.id
                                } else {
                                    selectedTrackIds + track.id
                                }
                        }
                    }
                    val onToggleSelection: (Long) -> Unit = { id ->
                        selectedTrackIds =
                            if (id in selectedTrackIds) {
                                selectedTrackIds - id
                            } else {
                                selectedTrackIds + id
                            }
                    }
                    // Keep the album-id → title map in sync with the
                    // visible tracks only, instead of observing the full
                    // album table. The ids stay small even when the user
                    // scrolls deep into a huge library.
                    val visibleAlbumIds = remember(songsPager.items) {
                        songsPager.items.mapNotNull { it.albumId }.distinct()
                    }
                    LaunchedEffect(visibleAlbumIds) {
                        albumNameById = if (visibleAlbumIds.isEmpty()) {
                            emptyMap()
                        } else {
                            libraryRepo.albumsByIds(visibleAlbumIds).mapValues { it.value.title }
                        }
                    }
                    val listState = rememberLazyListState()
                    LaunchedEffect(listState, songsPager) {
                        snapshotFlow { listState.layoutInfo }
                            .map { it.totalItemsCount to (it.visibleItemsInfo.lastOrNull()?.index ?: 0) }
                            .distinctUntilChanged()
                            .collect { (total, last) ->
                                if (total > 0 && last >= total - LOAD_AHEAD && songsPager.hasMore && !songsPager.isLoading) {
                                    songsPager.loadPage { limit, offset ->
                                        libraryRepo.listAvailableTracksPaged(
                                            limit = limit,
                                            offset = offset,
                                            sort = sortOrder.toLibrarySort(),
                                            filter = songsFilter.takeIf { it.isNotBlank() },
                                        )
                                    }
                                }
                            }
                    }
                    SongList(
                        tracks = songsPager.items,
                        currentTrackId = currentTrackId,
                        listState = listState,
                        onPlay = { track ->
                            val idx = songsPager.items.indexOf(track).coerceAtLeast(0)
                            stationPlayback.playTracks(songsPager.items, startIndex = idx)
                        },
                        onLongPress = onSongLongPress,
                        albumNameById = albumNameById,
                        selectionMode = inSelectionMode,
                        selectedIds = selectedTrackIds,
                        onToggleSelection = onToggleSelection,
                    )
                }
                LibraryTab.Artists -> {
                    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                    LaunchedEffect(gridState, artistsPager) {
                        snapshotFlow { gridState.layoutInfo }
                            .map { it.totalItemsCount to (it.visibleItemsInfo.lastOrNull()?.index ?: 0) }
                            .distinctUntilChanged()
                            .collect { (total, last) ->
                                if (total > 0 && last >= total - LOAD_AHEAD && artistsPager.hasMore && !artistsPager.isLoading) {
                                    artistsPager.loadPage { limit, offset ->
                                        when (artistSort) {
                                            ArtistSort.NameAsc -> libraryRepo.listArtistsPaged(limit, offset)
                                            ArtistSort.NameDesc -> libraryRepo.listArtistsPagedDesc(limit, offset)
                                            ArtistSort.MostPlayed -> {
                                                val entries = mostPlayedArtists.drop(offset.toInt()).take(limit.toInt())
                                                libraryRepo.artistsByIds(entries.map { it.id }).values.toList()
                                                    .sortedBy { artist -> entries.indexOfFirst { it.id == artist.id } }
                                            }
                                        }
                                    }
                                }
                            }
                    }
                    ArtistList(
                        artists = artistsPager.items,
                        onPick = { a -> onArtistTap(a.id) },
                        onLongPress = { a -> contextArtist = a },
                        gridState = gridState,
                    )
                }
                LibraryTab.Albums -> {
                    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                    LaunchedEffect(gridState, albumsPager) {
                        snapshotFlow { gridState.layoutInfo }
                            .map { it.totalItemsCount to (it.visibleItemsInfo.lastOrNull()?.index ?: 0) }
                            .distinctUntilChanged()
                            .collect { (total, last) ->
                                if (total > 0 && last >= total - LOAD_AHEAD && albumsPager.hasMore && !albumsPager.isLoading) {
                                    albumsPager.loadPage { limit, offset ->
                                        when (albumSort) {
                                            AlbumSort.TitleAsc -> libraryRepo.listAlbumsPaged(limit, offset)
                                            AlbumSort.TitleDesc -> libraryRepo.listAlbumsPagedDesc(limit, offset)
                                            AlbumSort.YearAsc -> libraryRepo.listAlbumsPagedByYear(limit, offset, ascending = true)
                                            AlbumSort.YearDesc -> libraryRepo.listAlbumsPagedByYear(limit, offset, ascending = false)
                                            AlbumSort.MostPlayed -> {
                                                val entries = mostPlayedAlbums.drop(offset.toInt()).take(limit.toInt())
                                                libraryRepo.albumsByIds(entries.map { it.id }).values.toList()
                                                    .sortedBy { album -> entries.indexOfFirst { it.id == album.id } }
                                            }
                                        }
                                    }
                                }
                            }
                    }
                    AlbumList(
                        albums = albumsPager.items,
                        onPick = { al -> onAlbumTap(al.id) },
                        onLongPress = { al -> contextAlbum = al },
                        gridState = gridState,
                    )
                }
                LibraryTab.Genres -> {
                    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                    LaunchedEffect(gridState, genresPager) {
                        snapshotFlow { gridState.layoutInfo }
                            .map { it.totalItemsCount to (it.visibleItemsInfo.lastOrNull()?.index ?: 0) }
                            .distinctUntilChanged()
                            .collect { (total, last) ->
                                if (total > 0 && last >= total - LOAD_AHEAD && genresPager.hasMore && !genresPager.isLoading) {
                                    genresPager.loadPage { limit, offset ->
                                        val values = when (genreSort) {
                                            GenreSort.NameAsc -> libraryRepo.distinctTagValuesPaged("GENRE", limit, offset)
                                            GenreSort.NameDesc -> libraryRepo.distinctTagValuesPagedDesc("GENRE", limit, offset)
                                        }
                                        values.filter { it.isNotBlank() }
                                    }
                                }
                            }
                    }
                    GenreList(
                        genres = genresPager.items,
                        onPick = { g -> onGenreTap(g) },
                        gridState = gridState,
                    )
                }
            }
        }
    }

    contextTrackId?.let { id ->
        TrackContextSheet(
            trackId = id,
            onDismiss = { contextTrackId = null },
            onSelect = {
                selectedTrackIds = setOf(id)
            },
        )
    }
    contextAlbum?.let { album ->
        AlbumContextSheet(
            album = album,
            onDismiss = { contextAlbum = null },
            onGoToAlbum = {
                contextAlbum = null
                onAlbumTap(album.id)
            },
            onCreateStation = {
                contextAlbum = null
                scope.launch {
                    // Create a new user-station seeded with this
                    // album's selector + a sensible default name,
                    // then drop the user straight into the editor
                    // so they can tweak playback rules. The freshly
                    // created station is appended at the bottom of
                    // sort order.
                    val maxSort = stationRepo.list().maxOfOrNull { it.sortPosition } ?: 0
                    val newId =
                        stationRepo.insert(
                            name = album.title,
                            source =
                                com.visorcraft.radioshed.domain.SourceRule.AlbumSelector(
                                    albumIds = listOf(album.id),
                                ),
                            playback =
                                com.visorcraft.radioshed.domain.PlaybackRule(
                                    order = com.visorcraft.radioshed.domain.PlayOrder.Sequential,
                                ),
                            sortPosition = maxSort + 1,
                        )
                    onEditStation(newId)
                }
            },
        )
    }
    // Dialog state for the new Edit / Merge actions on the artist
    // context sheet. We surface them from the same artist row the
    // user long-pressed / right-clicked, so they're scoped to that
    // artist and disappear when the underlying context sheet is
    // dismissed.
    var editingArtist by remember { mutableStateOf<com.visorcraft.radioshed.domain.Artist?>(null) }
    var mergingArtist by remember { mutableStateOf<com.visorcraft.radioshed.domain.Artist?>(null) }
    contextArtist?.let { artist ->
        ArtistContextSheet(
            artist = artist,
            onDismiss = { contextArtist = null },
            onGoToArtist = {
                contextArtist = null
                onArtistTap(artist.id)
            },
            onEditArtist = {
                editingArtist = artist
                contextArtist = null
            },
            onMergeArtist = {
                mergingArtist = artist
                contextArtist = null
            },
            onCreateStation = {
                contextArtist = null
                scope.launch {
                    val maxSort = stationRepo.list().maxOfOrNull { it.sortPosition } ?: 0
                    val newId =
                        stationRepo.insert(
                            name = artist.name,
                            source =
                                com.visorcraft.radioshed.domain.SourceRule.ArtistSelector(
                                    artistIds = listOf(artist.id),
                                ),
                            sortPosition = maxSort + 1,
                        )
                    onEditStation(newId)
                }
            },
        )
    }
    editingArtist?.let { artist ->
        EditArtistDialog(
            artist = artist,
            onDismiss = { editingArtist = null },
        )
    }
    mergingArtist?.let { artist ->
        MergeArtistDialog(
            source = artist,
            onDismiss = { mergingArtist = null },
            onMerged = { mergingArtist = null },
        )
    }
    if (showBatchEditDialog) {
        val selectedTracks by produceState<List<Track>>(initialValue = emptyList(), selectedTrackIds) {
            value = libraryRepo.tracksByIds(selectedTrackIds).values.toList()
        }
        BatchEditTagsDialog(
            tracks = selectedTracks,
            onDismiss = {
                showBatchEditDialog = false
                selectedTrackIds = emptySet()
            },
            onSaved = { selectedTrackIds = emptySet() },
        )
    }
}

private enum class LibraryTab(val label: String) {
    Songs("Songs"),
    Artists("Artists"),
    Albums("Albums"),
    Genres("Genres"),
}

private enum class SongSort(val label: String) {
    TitleAsc("Title — A to Z"),
    TitleDesc("Title — Z to A"),
    RecentlyAdded("Recently added"),
    Duration("Duration"),
    MostPlayed("Most played"),
    TopRated("Top rated"),
}

private fun SongSort.toLibrarySort(): LibraryTrackSort =
    when (this) {
        SongSort.TitleAsc -> LibraryTrackSort.TitleAsc
        SongSort.TitleDesc -> LibraryTrackSort.TitleDesc
        SongSort.RecentlyAdded -> LibraryTrackSort.RecentlyAdded
        SongSort.Duration -> LibraryTrackSort.Duration
        SongSort.MostPlayed -> LibraryTrackSort.MostPlayed
        SongSort.TopRated -> LibraryTrackSort.TopRated
    }

/**
 * Sort options for the Artists tab. Mirrors SongSort's options that
 * are meaningful at artist granularity. "Most played" sums the per-
 * track play counts across each artist's catalogue. Persisted via
 * SettingsStore so the user's choice carries between sessions.
 */
private enum class ArtistSort(val label: String) {
    NameAsc("Name — A to Z"),
    NameDesc("Name — Z to A"),
    MostPlayed("Most played"),
}

private enum class AlbumSort(val label: String) {
    TitleAsc("Title — A to Z"),
    TitleDesc("Title — Z to A"),
    YearAsc("Year — oldest first"),
    YearDesc("Year — newest first"),
    MostPlayed("Most played"),
}

private enum class GenreSort(val label: String) {
    NameAsc("Name — A to Z"),
    NameDesc("Name — Z to A"),
}

@Composable
private fun SongList(
    tracks: List<Track>,
    currentTrackId: Long? = null,
    onPlay: (Track) -> Unit,
    onLongPress: (Track) -> Unit = {},
    albumNameById: Map<Long, String> = emptyMap(),
    selectionMode: Boolean = false,
    selectedIds: Set<Long> = emptySet(),
    onToggleSelection: (Long) -> Unit = {},
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
) {
    if (tracks.isEmpty()) {
        EmptyHint("No songs match this view.")
        return
    }
    val scope = rememberCoroutineScope()
    // Show the back-to-top FAB once the user has scrolled past the
    // ~first viewport. derivedStateOf keeps the snapshot read cheap
    // — only triggers when firstVisibleItemIndex actually changes
    // category (above/below threshold).
    val showFab by remember {
        androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex > 8 }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(tracks, key = { it.id }) { track ->
                SongRow(
                    track = track,
                    isCurrent = track.id == currentTrackId,
                    isSelected = track.id in selectedIds,
                    selectionMode = selectionMode,
                    onTap = { onPlay(track) },
                    onToggleSelection = { onToggleSelection(track.id) },
                    onLongPress = { onLongPress(track) },
                    albumName = track.albumId?.let { albumNameById[it] },
                )
            }
        }
        // Desktop scrollbar overlay for the Songs tab — same pattern
        // as the grid variant in GridWithBackToTopFab. No-op on
        // Android via the PlatformScrollbar android actual.
        com.visorcraft.radioshed.ui.util.ListScrollbar(
            state = listState,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
        )
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
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = "Scroll to top",
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    track: Track,
    isCurrent: Boolean = false,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    onTap: () -> Unit,
    onToggleSelection: () -> Unit = {},
    onLongPress: () -> Unit = {},
    albumName: String? = null,
) {
    val haptic = rememberHapticTick()
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = if (selectionMode) onToggleSelection else onTap,
                    onLongClick = { haptic(); onLongPress() },
                ),
        color =
            if (isSelected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            } else {
                Color.Transparent
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                // Subtitle: album · duration. Album omitted when the
                // track has no album link or the name lookup misses
                // (track scanned with album_id, then row deleted),
                // so we don't render a leading bullet that floats
                // alone. Artist isn't included here because Track
                // doesn't carry it directly — it'd require a per-row
                // artistsForTrack call.
                val subtitle =
                    listOfNotNull(albumName, formatDurationMs(track.durationMs))
                        .joinToString(" · ")
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selectionMode) {
                Spacer(Modifier.size(12.dp))
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() },
                )
            }
        }
    }
}

@Composable
private fun ArtistList(
    artists: List<Artist>,
    onPick: (Artist) -> Unit,
    onLongPress: (Artist) -> Unit = {},
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
) {
    if (artists.isEmpty()) {
        EmptyHint("No artists yet — scan a folder with tagged music to populate.")
        return
    }
    // Tile grid with circular initials. We don't have artist photos in v1
    // (MusicBrainz/Discogs photo fetch is a future pass), so a colored
    // initial avatar reads cleaner than a generic person icon at every
    // tile. Hash artist name → hue so the same artist is always the same
    // color across sessions.
    GridWithBackToTopFab(state = gridState) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            gridItems(artists, key = { it.id }) { artist ->
                ArtistTile(
                    artist = artist,
                    onTap = { onPick(artist) },
                    onLongPress = { onLongPress(artist) },
                )
            }
        }
    }
}

@Composable
private fun AlbumList(
    albums: List<Album>,
    onPick: (Album) -> Unit,
    onLongPress: (Album) -> Unit = {},
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
) {
    if (albums.isEmpty()) {
        EmptyHint("No albums yet — scan a folder with tagged music to populate.")
        return
    }
    GridWithBackToTopFab(state = gridState) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 140.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            gridItems(albums, key = { it.id }) { album ->
                AlbumTile(
                    album = album,
                    onTap = { onPick(album) },
                    onLongPress = { onLongPress(album) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTile(
    album: Album,
    onTap: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    Column(
        modifier =
            Modifier.combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
            ),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            if (album.artworkUri != null) {
                coil3.compose.AsyncImage(
                    model = album.artworkUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            } else {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.Filled.Album,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = album.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        album.year?.let { y ->
            Text(
                text = "$y",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun GenreList(
    genres: List<String>,
    onPick: (String) -> Unit,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
) {
    if (genres.isEmpty()) {
        EmptyHint("No genre tags found — files need a GENRE / TCON ID3 frame.")
        return
    }
    GridWithBackToTopFab(state = gridState) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 140.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            gridItems(genres, key = { it }) { genre ->
                GenreTile(genre = genre, onTap = { onPick(genre) })
            }
        }
    }
}

/**
 * Wraps a LazyVerticalGrid (passed as `content`) with a Back-to-top
 * FAB that appears once the user has scrolled past the first ~2
 * viewports of tiles (firstVisibleItemIndex > 16). Tap animates the
 * scroll back to position 0. Mirrors the FAB in the Songs LazyColumn
 * for consistent navigation across all four tabs.
 */
@Composable
private fun GridWithBackToTopFab(
    state: androidx.compose.foundation.lazy.grid.LazyGridState,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val showFab by remember {
        androidx.compose.runtime.derivedStateOf { state.firstVisibleItemIndex > 16 }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        content()
        // Desktop scrollbar — overlays the right edge of the grid
        // so Windows / Linux users get a draggable handle for fast
        // navigation through long Artists/Albums/Genres tabs. No-op
        // on Android (the PlatformScrollbar android actual is
        // empty); touch users still get the system fling indicator.
        com.visorcraft.radioshed.ui.util.GridScrollbar(
            state = state,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
        )
        if (showFab) {
            androidx.compose.material3.SmallFloatingActionButton(
                onClick = { scope.launch { state.animateScrollToItem(0) } },
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

@Composable
private fun GenreTile(
    genre: String,
    onTap: () -> Unit,
) {
    // Stable colored chip per genre. Same hash-to-hue trick as ArtistTile,
    // but the tile is a wide rounded rectangle (no avatar) so the genre
    // name reads horizontally.
    val hue = (genre.hashCode().rem(360) + 360).rem(360).toFloat()
    val tint = Color.hsv(hue, 0.5f, 0.85f)
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onTap),
        shape = RoundedCornerShape(12.dp),
        color = tint,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = genre,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.95f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistTile(
    artist: Artist,
    onTap: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    val hue = (artist.sortName.hashCode().rem(360) + 360).rem(360).toFloat()
    val tint = androidx.compose.ui.graphics.Color.hsv(hue, 0.55f, 0.85f)
    Column(
        // Right-click reaches the same context sheet as long-press
        // so Windows / Linux mouse users have a non-touch path to
        // Edit / Merge / Create-station-from-artist.
        modifier =
            Modifier
                .combinedClickable(
                    onClick = onTap,
                    onLongClick = onLongPress,
                )
                .onRightClick(onLongPress),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(tint),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = artist.name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.95f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
    }
}

