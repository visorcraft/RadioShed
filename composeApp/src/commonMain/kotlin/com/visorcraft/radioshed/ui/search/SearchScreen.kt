package com.visorcraft.radioshed.ui.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.data.SearchRepository
import com.visorcraft.radioshed.data.SearchResults
import com.visorcraft.radioshed.domain.Album
import com.visorcraft.radioshed.domain.Artist
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.stations.StationPlayback
import com.visorcraft.radioshed.ui.playlists.AddToPlaylistSheet
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/**
 * Library search. v1 is plain full-text — DSL like `artist:Hendrix
 * year:>1968` is deferred. The query input debounces by 200 ms so we
 * don't hammer the database on every keystroke.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    searchRepo: SearchRepository = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(SearchResults(emptyList(), emptyList(), emptyList())) }
    var isSearching by remember { mutableStateOf(false) }
    var addToPlaylistTrackId by remember { mutableStateOf<Long?>(null) }
    var scope by remember { mutableStateOf(SearchScope.All) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = SearchResults(emptyList(), emptyList(), emptyList())
            isSearching = false
            return@LaunchedEffect
        }
        // Clear stale results immediately so the user sees feedback the moment
        // they edit; otherwise the previous query's results linger for the
        // 200 ms debounce window with no in-flight indicator.
        results = SearchResults(emptyList(), emptyList(), emptyList())
        isSearching = true
        delay(200)  // debounce
        results = searchRepo.searchAll(query, limitPerCategory = 50)
        isSearching = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Title, artist, or album") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SearchScope.entries.forEach { s ->
                    FilterChip(
                        selected = scope == s,
                        onClick = { scope = s },
                        label = { Text(s.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            when {
                query.isBlank() -> Text(
                    "Type to search across your library.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
                isSearching && results.isEmpty -> Text(
                    "Searching…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
                results.isEmpty -> Text(
                    "No results.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val showArtists = (scope == SearchScope.All || scope == SearchScope.Artists) &&
                        results.artists.isNotEmpty()
                    val showAlbums = (scope == SearchScope.All || scope == SearchScope.Albums) &&
                        results.albums.isNotEmpty()
                    val showTracks = (scope == SearchScope.All || scope == SearchScope.Tracks) &&
                        results.tracks.isNotEmpty()

                    if (showArtists) {
                        item { SectionHeader("Artists (${results.artists.size})") }
                        items(results.artists, key = { "artist:${it.id}" }) { ArtistRow(it) }
                    }
                    if (showAlbums) {
                        item { SectionHeader("Albums (${results.albums.size})") }
                        items(results.albums, key = { "album:${it.id}" }) { AlbumRow(it) }
                    }
                    if (showTracks) {
                        item { SectionHeader("Tracks (${results.tracks.size})") }
                        items(results.tracks, key = { "track:${it.id}" }) { track ->
                            // Mirror the Library Browser: tap seeds an
                            // ad-hoc queue from the visible search hits so
                            // skip-next walks through the rest of the
                            // results instead of dead-ending after one
                            // track.
                            TrackRow(
                                track = track,
                                onClick = {
                                    val idx = results.tracks.indexOf(track).coerceAtLeast(0)
                                    stationPlayback.playTracks(results.tracks, startIndex = idx)
                                },
                                onLongPress = { addToPlaylistTrackId = track.id },
                            )
                        }
                    }
                    if (!showArtists && !showAlbums && !showTracks) {
                        item {
                            Text(
                                "No matches in this scope.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            )
                        }
                    }
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

/** Filter chips above results — narrows what's shown without re-querying. */
private enum class SearchScope(val label: String) {
    All("All"),
    Tracks("Tracks"),
    Artists("Artists"),
    Albums("Albums"),
}

@Composable
private fun SectionHeader(label: String) {
    Spacer(Modifier.height(8.dp))
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun ArtistRow(artist: Artist) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(artist.name, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AlbumRow(album: Album) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(album.title, style = MaterialTheme.typography.bodyLarge)
        album.year?.let {
            Text(
                "$it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(track: Track, onClick: () -> Unit, onLongPress: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Text(track.title, style = MaterialTheme.typography.bodyLarge)
        Text(
            "${track.format.uppercase()} · ${track.fileName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
    }
}
