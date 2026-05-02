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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.domain.Album
import com.visorcraft.radioshed.domain.Artist
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.stations.StationPlayback
import com.visorcraft.radioshed.ui.playlists.AddToPlaylistSheet
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
    libraryRepo: LibraryRepository = koinInject(),
    audioEngine: AudioEngine = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    settings: SettingsStore = koinInject(),
) {
    val scope = rememberCoroutineScope()
    var addToPlaylistTrackId by remember { mutableStateOf<Long?>(null) }
    val artists by libraryRepo.observeArtists().collectAsState(initial = emptyList())
    val albums by libraryRepo.observeAlbums().collectAsState(initial = emptyList())
    var allTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var genres by remember { mutableStateOf<List<String>>(emptyList()) }

    // Eagerly load all tracks once. For huge libraries (>50k) this could
    // become a memory issue; we'd page or virtualize then. For typical
    // libraries (<5k tracks) it's fine and lets us filter / sort in-memory
    // without re-querying the DB.
    LaunchedEffect(Unit) {
        allTracks = libraryRepo.listAvailableTracks(limit = 100_000L, offset = 0L)
        // Distinct genre tag values. Index already exists on track_tag(key,
        // value) so this is a fast index scan even on large libraries.
        genres = libraryRepo.distinctTagValues("GENRE")
            .filter { it.isNotBlank() }
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
    LaunchedEffect(selectedTab) { settings.setLibraryTabIndex(selectedTab.ordinal) }
    LaunchedEffect(sortOrder) { settings.setLibrarySongSort(sortOrder.name) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    if (selectedTab == LibraryTab.Songs) {
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
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                LibraryTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.label) },
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
                    val sorted = when (sortOrder) {
                        SongSort.TitleAsc -> filtered.sortedBy { it.title.lowercase() }
                        SongSort.TitleDesc -> filtered.sortedByDescending { it.title.lowercase() }
                        SongSort.RecentlyAdded -> filtered.sortedByDescending { it.fileCtime }
                        SongSort.Duration -> filtered.sortedBy { it.durationMs }
                    }

                    // Tap-to-play seeds an ad-hoc queue from the visible list,
                    // so skip-next walks forward through the same view the
                    // user is browsing (and skip-previous walks back through
                    // history). Without this, tap-to-play would play just one
                    // track and the next skip would do nothing.
                    if (artistFilter != null) {
                        var artistTracks by remember(artistFilter) { mutableStateOf<List<Track>>(emptyList()) }
                        LaunchedEffect(artistFilter) {
                            artistTracks = libraryRepo.tracksForArtist(artistFilter!!.id)
                        }
                        SongList(
                            tracks = artistTracks,
                            onPlay = { track ->
                                val idx = artistTracks.indexOf(track).coerceAtLeast(0)
                                stationPlayback.playTracks(artistTracks, startIndex = idx)
                            },
                            onLongPress = { track -> addToPlaylistTrackId = track.id },
                        )
                    } else {
                        SongList(
                            tracks = sorted,
                            onPlay = { track ->
                                val idx = sorted.indexOf(track).coerceAtLeast(0)
                                stationPlayback.playTracks(sorted, startIndex = idx)
                            },
                            onLongPress = { track -> addToPlaylistTrackId = track.id },
                        )
                    }
                }
                LibraryTab.Artists -> {
                    // Tapping an artist now navigates to ArtistDetail rather
                    // than applying an in-place filter — the dedicated screen
                    // shows their albums + tracks with play / shuffle, which
                    // is what the user actually wants when they pick an
                    // artist's name from a list.
                    ArtistList(
                        artists = artists,
                        onPick = { a -> onArtistTap(a.id) },
                    )
                }
                LibraryTab.Albums -> {
                    AlbumList(
                        albums = albums,
                        onPick = { al -> onAlbumTap(al.id) },
                    )
                }
                LibraryTab.Genres -> {
                    GenreList(
                        genres = genres,
                        onPick = { g -> onGenreTap(g) },
                    )
                }
            }
        }
    }

    addToPlaylistTrackId?.let { id ->
        AddToPlaylistSheet(
            trackId = id,
            onDismiss = { addToPlaylistTrackId = null },
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
}

@Composable
private fun SongList(
    tracks: List<Track>,
    onPlay: (Track) -> Unit,
    onLongPress: (Track) -> Unit = {},
) {
    if (tracks.isEmpty()) {
        EmptyHint("No songs match this view.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(tracks, key = { it.id }) { track ->
            SongRow(
                track = track,
                onTap = { onPlay(track) },
                onLongPress = { onLongPress(track) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(track: Track, onTap: () -> Unit, onLongPress: () -> Unit = {}) {
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
                Text(
                    text = track.title.ifBlank { track.fileName },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatDuration(track.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun ArtistList(artists: List<Artist>, onPick: (Artist) -> Unit) {
    if (artists.isEmpty()) {
        EmptyHint("No artists yet — scan a folder with tagged music to populate.")
        return
    }
    // Tile grid with circular initials. We don't have artist photos in v1
    // (MusicBrainz/Discogs photo fetch is a future pass), so a colored
    // initial avatar reads cleaner than a generic person icon at every
    // tile. Hash artist name → hue so the same artist is always the same
    // color across sessions.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        gridItems(artists, key = { it.id }) { artist ->
            ArtistTile(artist = artist, onTap = { onPick(artist) })
        }
    }
}

@Composable
private fun AlbumList(albums: List<Album>, onPick: (Album) -> Unit) {
    if (albums.isEmpty()) {
        EmptyHint("No albums yet — scan a folder with tagged music to populate.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        gridItems(albums, key = { it.id }) { album ->
            AlbumTile(album = album, onTap = { onPick(album) })
        }
    }
}

@Composable
private fun AlbumTile(album: Album, onTap: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onTap)) {
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
    LazyVerticalGrid(
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

@Composable
private fun ArtistTile(artist: Artist, onTap: () -> Unit) {
    val hue = (artist.sortName.hashCode().rem(360) + 360).rem(360).toFloat()
    val tint = androidx.compose.ui.graphics.Color.hsv(hue, 0.55f, 0.85f)
    Column(
        modifier = Modifier.clickable(onClick = onTap),
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
