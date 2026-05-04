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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.collectAsState
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
import com.visorcraft.radioshed.audio.toPlayable
import com.visorcraft.radioshed.ui.util.NowPlayingIndicator
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.domain.Album
import com.visorcraft.radioshed.domain.Artist
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.stations.StationPlayback
import com.visorcraft.radioshed.ui.playlists.TrackContextSheet
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

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
    // Now-playing indicator on Songs rows: collect the engine's
    // current track id so each row can self-tag.
    val nowPlaying by audioEngine.nowPlaying.collectAsState()
    val currentTrackId = nowPlaying?.trackId
    val artists by libraryRepo.observeArtists().collectAsState(initial = emptyList())
    val albums by libraryRepo.observeAlbums().collectAsState(initial = emptyList())
    // Re-load the eager track list and genre list whenever the total
    // track count changes — covers the "user scanned a folder while
    // Library was open" case. Without this trigger, allTracks stayed
    // at whatever was loaded on first composition until the user left
    // and re-entered the screen.
    val trackCount by libraryRepo.observeTrackCount().collectAsState(initial = -1L)
    var allTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var genres by remember { mutableStateOf<List<String>>(emptyList()) }
    // Per-track play counts + ratings, lazy-fetched once allTracks
    // resolves. We hold these as Map<TrackId, *> so the sort path
    // can do O(n log n) lookups without hitting the DB per row.
    // Empty default keeps the initial render snappy — sort by play
    // count or rating just shows whatever order we have until the
    // map fills.
    var playCounts by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    var ratings by remember { mutableStateOf<Map<Long, Double>>(emptyMap()) }

    // Eagerly load all tracks once. For huge libraries (>50k) this could
    // become a memory issue; we'd page or virtualize then. For typical
    // libraries (<5k tracks) it's fine and lets us filter / sort in-memory
    // without re-querying the DB.
    LaunchedEffect(trackCount) {
        // Initial collectAsState gives -1L; skip until SQLDelight emits
        // the real count (≥ 0).
        if (trackCount < 0L) return@LaunchedEffect
        allTracks = libraryRepo.listAvailableTracks(limit = 100_000L, offset = 0L)
        // Distinct genre tag values. Index already exists on track_tag(key,
        // value) so this is a fast index scan even on large libraries.
        genres = libraryRepo.distinctTagValues("GENRE")
            .filter { it.isNotBlank() }
        val ids = allTracks.map { it.id }
        playCounts = stationRepo.playCountForTracks(ids)
        ratings = ratingRepo.ratingsForTracks(ids)
    }

    // Persist + restore tab + sort selection across navigations. Filters
    // (artist / album) are intentionally NOT persisted — they're transient
    // drill-in state that should reset when the user comes back fresh.
    var selectedTab by remember {
        mutableStateOf(LibraryTab.entries.getOrElse(settings.getLibraryTabIndex()) { LibraryTab.Songs })
    }
    var artistFilter by remember { mutableStateOf<Artist?>(null) }
    var albumFilter by remember { mutableStateOf<Album?>(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }
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
    // Per-artist play count map for the "Most played" sort. Refetched
    // every time MostPlayed becomes active so a user who plays new
    // tracks while the browser is open and then switches back to the
    // sort sees fresh counts (not the snapshot from first activation).
    // Long.MAX_VALUE limit is fine — selectTopPlayedArtists already
    // runs an indexed GROUP BY and the result fits comfortably in
    // memory.
    var artistPlayCounts by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    LaunchedEffect(artistSort) {
        if (artistSort == ArtistSort.MostPlayed) {
            artistPlayCounts = stationRepo.topPlayedArtists(Long.MAX_VALUE)
                .associate { it.id to it.plays }
        }
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
    // Per-album play count map for the "Most played" sort. Refetched
    // every time MostPlayed becomes active so the snapshot is fresh
    // when the user switches sorts and switches back.
    var albumPlayCounts by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    LaunchedEffect(albumSort) {
        if (albumSort == AlbumSort.MostPlayed) {
            albumPlayCounts = stationRepo.topPlayedAlbums(Long.MAX_VALUE)
                .associate { it.id to it.plays }
        }
    }
    // Songs tab quick filter — hoisted to function scope so the value
    // survives a tab switch (was previously declared inside the
    // `LibraryTab.Songs ->` branch and silently reset every time the
    // user navigated away and back).
    var songsFilter by remember { mutableStateOf("") }
    LaunchedEffect(selectedTab) { settings.setLibraryTabIndex(selectedTab.ordinal) }
    LaunchedEffect(sortOrder) { settings.setLibrarySongSort(sortOrder.name) }
    LaunchedEffect(artistSort) { settings.setLibraryArtistSort(artistSort.name) }
    LaunchedEffect(albumSort) { settings.setLibraryAlbumSort(albumSort.name) }
    LaunchedEffect(genreSort) { settings.setLibraryGenreSort(genreSort.name) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // Subtitle line shows the live counts beneath the
                    // header so users get a sense of library size at a
                    // glance — and the count helps confirm that a fresh
                    // scan picked things up.
                    Column {
                        Text("Library", style = MaterialTheme.typography.titleSmall)
                        val countLabel = buildString {
                            append("${allTracks.size} tracks")
                            if (artists.isNotEmpty()) append(" · ${artists.size} artists")
                            if (albums.isNotEmpty()) append(" · ${albums.size} albums")
                        }
                        Text(
                            text = countLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    if (selectedTab == LibraryTab.Albums && albums.isNotEmpty()) {
                        // Random-album entry point on the Albums view: picks
                        // one album from the visible grid at random and
                        // queues all of its tracks in album order. Sister
                        // action to the Songs tab's Shuffle-all — same idea
                        // ("surprise me, no station setup needed") but at
                        // album granularity, which is how plenty of users
                        // think about their music.
                        IconButton(onClick = {
                            val pick = albums.random()
                            scope.launch {
                                val tracks = libraryRepo.tracksForAlbum(pick.id)
                                if (tracks.isNotEmpty()) {
                                    stationPlayback.playTracks(tracks, startIndex = 0)
                                }
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Casino,
                                contentDescription = "Random album",
                            )
                        }
                    }
                    if (selectedTab == LibraryTab.Artists && artists.isNotEmpty()) {
                        // Mirror the Songs-tab sort dropdown for the
                        // Artists list. Same chevron-style icon so
                        // the affordance reads as "sort" without
                        // having to add a label that competes with
                        // the title row.
                        Box {
                            IconButton(onClick = { sortMenuOpen = true }) {
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
                    if (selectedTab == LibraryTab.Albums && albums.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { sortMenuOpen = true }) {
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
                    if (selectedTab == LibraryTab.Genres && genres.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { sortMenuOpen = true }) {
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
                    if (selectedTab == LibraryTab.Songs && allTracks.isNotEmpty()) {
                        // Shuffle-all on the visible Songs view: kicks
                        // off an ad-hoc queue from the entire current
                        // (filtered + sorted) track list, randomized.
                        // Distinct from the per-station SmartShuffle —
                        // this is the "play everything I own, surprise
                        // me" entry point that doesn't require building
                        // a station first.
                        IconButton(onClick = {
                            val pool = allTracks.shuffled()
                            stationPlayback.playTracks(pool, startIndex = 0)
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Shuffle,
                                contentDescription = "Shuffle all",
                            )
                        }
                        Box {
                            IconButton(onClick = { sortMenuOpen = true }) {
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
                    IconButton(onClick = onManage) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Manage folders",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
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
                LibraryTab.entries.forEach { tab ->
                    val count = when (tab) {
                        LibraryTab.Songs -> allTracks.size
                        LibraryTab.Artists -> artists.size
                        LibraryTab.Albums -> albums.size
                        LibraryTab.Genres -> genres.size
                    }
                    val label = if (count > 0) "${tab.label} · $count" else tab.label
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(label) },
                    )
                }
            }

            // Active filter chips (only shown when something's filtered)
            if (artistFilter != null || albumFilter != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    artistFilter?.let { a ->
                        AssistChip(
                            onClick = { artistFilter = null },
                            label = { Text("Artist: ${a.name}") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Clear,
                                    contentDescription = "Clear artist filter",
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                    albumFilter?.let { al ->
                        AssistChip(
                            onClick = { albumFilter = null },
                            label = { Text("Album: ${al.title}") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Clear,
                                    contentDescription = "Clear album filter",
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
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
                        trailingIcon = if (songsFilter.isNotEmpty()) {
                            {
                                androidx.compose.material3.IconButton(onClick = {
                                    songsFilter = ""
                                }) {
                                    Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Filled.Clear,
                                        contentDescription = "Clear filter",
                                    )
                                }
                            }
                        } else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    val filtered = allTracks
                        .let { tracks ->
                            albumFilter?.let { al -> tracks.filter { it.albumId == al.id } } ?: tracks
                        }
                        .let { tracks ->
                            // Artist filter requires a join; we don't have artist
                            // info on Track domain. We DO have the artist's tracks
                            // via libraryRepo.tracksForArtist — but that's another
                            // query. For MVP: when artist filter is active, fetch
                            // their tracks separately. Simpler than passing the
                            // pre-fetched list around.
                            tracks
                        }
                        .let { tracks ->
                            val q = songsFilter.trim()
                            if (q.isBlank()) tracks
                            else tracks.filter {
                                it.title.contains(q, ignoreCase = true) ||
                                    it.fileName.contains(q, ignoreCase = true)
                            }
                        }
                    val sorted = when (sortOrder) {
                        SongSort.TitleAsc -> filtered.sortedBy { it.title.lowercase() }
                        SongSort.TitleDesc -> filtered.sortedByDescending { it.title.lowercase() }
                        SongSort.RecentlyAdded -> filtered.sortedByDescending { it.fileCtime }
                        SongSort.Duration -> filtered.sortedBy { it.durationMs }
                        SongSort.MostPlayed -> filtered.sortedByDescending {
                            playCounts[it.id] ?: 0L
                        }
                        SongSort.TopRated -> filtered.sortedByDescending {
                            ratings[it.id] ?: -1.0
                        }
                    }

                    // Tap-to-play seeds an ad-hoc queue from the visible list,
                    // so skip-next walks forward through the same view the
                    // user is browsing (and skip-previous walks back through
                    // history). Without this, tap-to-play would play just one
                    // track and the next skip would do nothing.
                    // Album-id → name map built once from the loaded
                    // album list. Used by SongRow's subtitle so each
                    // row can show its album without re-querying the
                    // DB. derivedStateOf would re-run on every album
                    // emission; remember(albums) is enough since the
                    // observe flow only re-emits when the set changes.
                    val albumNameById = remember(albums) {
                        albums.associate { it.id to it.title }
                    }
                    if (artistFilter != null) {
                        var artistTracks by remember(artistFilter) { mutableStateOf<List<Track>>(emptyList()) }
                        LaunchedEffect(artistFilter) {
                            artistTracks = libraryRepo.tracksForArtist(artistFilter!!.id)
                        }
                        // Apply the same filter the user typed into the
                        // Songs quick-filter field — without this, the
                        // filter text remains visible and looks active
                        // while the artist-filtered list ignores it
                        // entirely. Empty filter is a passthrough.
                        val filteredArtistTracks = remember(artistTracks, songsFilter) {
                            val q = songsFilter.trim()
                            if (q.isBlank()) artistTracks
                            else artistTracks.filter {
                                it.title.contains(q, true) ||
                                    it.fileName.contains(q, true)
                            }
                        }
                        SongList(
                            tracks = filteredArtistTracks,
                            currentTrackId = currentTrackId,
                            onPlay = { track ->
                                val idx = filteredArtistTracks
                                    .indexOf(track).coerceAtLeast(0)
                                stationPlayback.playTracks(
                                    filteredArtistTracks,
                                    startIndex = idx,
                                )
                            },
                            onLongPress = { track -> contextTrackId = track.id },
                            albumNameById = albumNameById,
                        )
                    } else {
                        SongList(
                            tracks = sorted,
                            currentTrackId = currentTrackId,
                            onPlay = { track ->
                                val idx = sorted.indexOf(track).coerceAtLeast(0)
                                stationPlayback.playTracks(sorted, startIndex = idx)
                            },
                            onLongPress = { track -> contextTrackId = track.id },
                            albumNameById = albumNameById,
                        )
                    }
                }
                LibraryTab.Artists -> {
                    // Tapping an artist now navigates to ArtistDetail rather
                    // than applying an in-place filter — the dedicated screen
                    // shows their albums + tracks with play / shuffle, which
                    // is what the user actually wants when they pick an
                    // artist's name from a list.
                    val sortedArtists = remember(artists, artistSort, artistPlayCounts) {
                        when (artistSort) {
                            ArtistSort.NameAsc ->
                                artists.sortedBy { it.name.lowercase() }
                            ArtistSort.NameDesc ->
                                artists.sortedByDescending { it.name.lowercase() }
                            ArtistSort.MostPlayed ->
                                artists.sortedByDescending {
                                    artistPlayCounts[it.id] ?: 0L
                                }
                        }
                    }
                    ArtistList(
                        artists = sortedArtists,
                        onPick = { a -> onArtistTap(a.id) },
                        onLongPress = { a -> contextArtist = a },
                    )
                }
                LibraryTab.Albums -> {
                    val sortedAlbums = remember(albums, albumSort, albumPlayCounts) {
                        when (albumSort) {
                            AlbumSort.TitleAsc ->
                                albums.sortedBy { it.title.lowercase() }
                            AlbumSort.TitleDesc ->
                                albums.sortedByDescending { it.title.lowercase() }
                            AlbumSort.YearAsc ->
                                albums.sortedBy { it.year ?: Int.MAX_VALUE }
                            AlbumSort.YearDesc ->
                                albums.sortedByDescending { it.year ?: Int.MIN_VALUE }
                            AlbumSort.MostPlayed ->
                                albums.sortedByDescending {
                                    albumPlayCounts[it.id] ?: 0L
                                }
                        }
                    }
                    AlbumList(
                        albums = sortedAlbums,
                        onPick = { al -> onAlbumTap(al.id) },
                        onLongPress = { al -> contextAlbum = al },
                    )
                }
                LibraryTab.Genres -> {
                    val sortedGenres = remember(genres, genreSort, allTracks) {
                        when (genreSort) {
                            GenreSort.NameAsc ->
                                genres.sortedBy { it.lowercase() }
                            GenreSort.NameDesc ->
                                genres.sortedByDescending { it.lowercase() }
                        }
                    }
                    GenreList(
                        genres = sortedGenres,
                        onPick = { g -> onGenreTap(g) },
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
                    val newId = stationRepo.insert(
                        name = album.title,
                        source = com.visorcraft.radioshed.domain.SourceRule.AlbumSelector(
                            albumIds = listOf(album.id),
                        ),
                        playback = com.visorcraft.radioshed.domain.PlaybackRule(
                            order = com.visorcraft.radioshed.domain.PlayOrder.Sequential,
                        ),
                        sortPosition = maxSort + 1,
                    )
                    onEditStation(newId)
                }
            },
        )
    }
    contextArtist?.let { artist ->
        ArtistContextSheet(
            artist = artist,
            onDismiss = { contextArtist = null },
            onGoToArtist = {
                contextArtist = null
                onArtistTap(artist.id)
            },
            onCreateStation = {
                contextArtist = null
                scope.launch {
                    val maxSort = stationRepo.list().maxOfOrNull { it.sortPosition } ?: 0
                    val newId = stationRepo.insert(
                        name = artist.name,
                        source = com.visorcraft.radioshed.domain.SourceRule.ArtistSelector(
                            artistIds = listOf(artist.id),
                        ),
                        sortPosition = maxSort + 1,
                    )
                    onEditStation(newId)
                }
            },
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
) {
    if (tracks.isEmpty()) {
        EmptyHint("No songs match this view.")
        return
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
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
                    onTap = { onPlay(track) },
                    onLongPress = { onLongPress(track) },
                    albumName = track.albumId?.let { albumNameById[it] },
                )
            }
        }
        if (showFab) {
            androidx.compose.material3.SmallFloatingActionButton(
                onClick = {
                    scope.launch { listState.animateScrollToItem(0) }
                },
                modifier = Modifier
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
    onTap: () -> Unit,
    onLongPress: () -> Unit = {},
    albumName: String? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
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
                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onBackground,
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
                val subtitle = listOfNotNull(albumName, formatDuration(track.durationMs))
                    .joinToString(" · ")
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
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
) {
    if (albums.isEmpty()) {
        EmptyHint("No albums yet — scan a folder with tagged music to populate.")
        return
    }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
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
        modifier = Modifier.combinedClickable(
            onClick = onTap,
            onLongClick = onLongPress,
        ),
    ) {
        Surface(
            modifier = Modifier
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
private fun GenreList(genres: List<String>, onPick: (String) -> Unit) {
    if (genres.isEmpty()) {
        EmptyHint("No genre tags found — files need a GENRE / TCON ID3 frame.")
        return
    }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
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
        if (showFab) {
            androidx.compose.material3.SmallFloatingActionButton(
                onClick = { scope.launch { state.animateScrollToItem(0) } },
                modifier = Modifier
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
private fun GenreTile(genre: String, onTap: () -> Unit) {
    // Stable colored chip per genre. Same hash-to-hue trick as ArtistTile,
    // but the tile is a wide rounded rectangle (no avatar) so the genre
    // name reads horizontally.
    val hue = (genre.hashCode().rem(360) + 360).rem(360).toFloat()
    val tint = Color.hsv(hue, 0.5f, 0.85f)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(12.dp),
        color = tint,
    ) {
        Box(
            modifier = Modifier
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
        modifier = Modifier.combinedClickable(
            onClick = onTap,
            onLongClick = onLongPress,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
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

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "—"
    val totalSeconds = ms / 1000L
    val mins = totalSeconds / 60L
    val secs = totalSeconds % 60L
    return "$mins:${secs.toString().padStart(2, '0')}"
}
