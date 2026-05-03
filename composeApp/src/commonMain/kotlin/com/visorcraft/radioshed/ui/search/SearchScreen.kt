package com.visorcraft.radioshed.ui.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.ui.Alignment
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.data.SearchRepository
import com.visorcraft.radioshed.data.SearchResults
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.domain.Album
import com.visorcraft.radioshed.domain.Artist
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.stations.StationPlayback
import com.visorcraft.radioshed.ui.playlists.TrackContextSheet
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
    onArtistTap: (Long) -> Unit = {},
    onAlbumTap: (Long) -> Unit = {},
    searchRepo: SearchRepository = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    settings: SettingsStore = koinInject(),
    audioEngine: com.visorcraft.radioshed.audio.AudioEngine = koinInject(),
    ratingRepo: com.visorcraft.radioshed.data.RatingRepository = koinInject(),
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(SearchResults(emptyList(), emptyList(), emptyList())) }
    var isSearching by remember { mutableStateOf(false) }
    var contextTrackId by remember { mutableStateOf<Long?>(null) }
    val nowPlaying by audioEngine.nowPlaying.collectAsState()
    val currentTrackId = nowPlaying?.trackId
    var scope by remember { mutableStateOf(SearchScope.All) }
    // Liked-only filter applied to the Tracks results only — albums
    // and artists don't carry a liked flag. The set refreshes after
    // each query lands AND when liked-count changes elsewhere
    // (long-press → like, etc.) so the filter reflects current state
    // without manual reload.
    var likedOnly by remember { mutableStateOf(false) }
    var likedSet by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val likedCount by ratingRepo.observeLikedCount().collectAsState(initial = 0L)
    LaunchedEffect(likedCount, results, likedOnly) {
        // Only hit the DB when the chip is on. Toggling off clears
        // the set so a stale snapshot can't accidentally keep
        // filtering after the user drops the filter chip. Without
        // the likedOnly gate, every search query was triggering a
        // full likedTrackIds() scan even when the filter wasn't
        // applied — wasted DB roundtrip on every keystroke debounce.
        if (likedOnly && results.tracks.isNotEmpty()) {
            likedSet = ratingRepo.likedTrackIds().toSet()
        } else if (!likedOnly) {
            likedSet = emptySet()
        }
    }
    // Read once on entry; we maintain it in-state and only flush back
    // to settings when a query produces results (so abandoned typing
    // doesn't pollute the list).
    var recentQueries by remember { mutableStateOf(settings.getRecentSearches()) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = SearchResults(emptyList(), emptyList(), emptyList())
            isSearching = false
            return@LaunchedEffect
        }
        // Reset the scope chip on a fresh query — sticky scope state
        // confused users who searched in Artists scope, then changed
        // the query to a track and got "No matches in this scope" with
        // no clear explanation.
        scope = SearchScope.All
        // Clear stale results immediately so the user sees feedback the moment
        // they edit; otherwise the previous query's results linger for the
        // 200 ms debounce window with no in-flight indicator.
        results = SearchResults(emptyList(), emptyList(), emptyList())
        isSearching = true
        delay(200)  // debounce
        val r = searchRepo.searchAll(query, limitPerCategory = 50)
        results = r
        isSearching = false
        // Save to history when the query produced anything — abandoned
        // typing ("R", "Re", "Rea"...) shouldn't fill the list.
        if (!r.isEmpty) {
            settings.pushRecentSearch(query)
            recentQueries = settings.getRecentSearches()
        }
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
            // Auto-focus the field on first composition so the keyboard
            // pops up immediately — saves the user a tap when they
            // navigate here intentionally to type a query. We only
            // request focus once (LaunchedEffect(Unit)) so re-entry
            // from a stale-state recomposition doesn't grab focus
            // back if they've moved away from the field.
            val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) {
                runCatching { focusRequester.requestFocus() }
            }
            val voicePicker = com.visorcraft.radioshed.ui.util.rememberVoiceInput { spoken ->
                query = spoken
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Title, artist, or album") },
                singleLine = true,
                trailingIcon = {
                    if (com.visorcraft.radioshed.ui.util.supportsVoiceInput()) {
                        androidx.compose.material3.IconButton(onClick = { voicePicker() }) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = "Voice search",
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
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
                // Liked-only filter applies only to track results.
                // Hidden when the user is in Artists or Albums scope
                // since liked is a per-track flag and would be a no-op.
                if (scope == SearchScope.All || scope == SearchScope.Tracks) {
                    FilterChip(
                        selected = likedOnly,
                        onClick = { likedOnly = !likedOnly },
                        label = { Text("Liked") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            when {
                query.isBlank() -> RecentSearchesBlock(
                    queries = recentQueries,
                    onPick = { q -> query = q },
                    onClear = {
                        settings.clearRecentSearches()
                        recentQueries = emptyList()
                    },
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
                        results.artists.isNotEmpty() && !likedOnly
                    val showAlbums = (scope == SearchScope.All || scope == SearchScope.Albums) &&
                        results.albums.isNotEmpty() && !likedOnly
                    // Liked-only narrows the visible track list to
                    // tracks in `likedSet`. We compute this lazily
                    // each frame — for typical libraries it's cheap
                    // (a few hundred items max).
                    val visibleTracks = if (likedOnly) {
                        results.tracks.filter { it.id in likedSet }
                    } else {
                        results.tracks
                    }
                    val showTracks = (scope == SearchScope.All || scope == SearchScope.Tracks) &&
                        visibleTracks.isNotEmpty()

                    if (showArtists) {
                        item { SectionHeader("Artists (${results.artists.size})") }
                        items(results.artists, key = { "artist:${it.id}" }) { artist ->
                            ArtistRow(artist, onTap = { onArtistTap(artist.id) })
                        }
                    }
                    if (showAlbums) {
                        item { SectionHeader("Albums (${results.albums.size})") }
                        items(results.albums, key = { "album:${it.id}" }) { album ->
                            AlbumRow(album, onTap = { onAlbumTap(album.id) })
                        }
                    }
                    if (showTracks) {
                        item {
                            // Section header lives on a Row with the
                            // play / shuffle actions so the user can
                            // seed an ad-hoc queue from EVERY matching
                            // track without having to tap into one of
                            // them. Mirrors the Library Browser
                            // Songs-tab Shuffle-all affordance.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SectionHeader("Tracks (${visibleTracks.size})")
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = {
                                    stationPlayback.playTracks(
                                        visibleTracks,
                                        startIndex = 0,
                                    )
                                }) { Text("Play all") }
                                TextButton(onClick = {
                                    stationPlayback.playTracks(
                                        visibleTracks.shuffled(),
                                        startIndex = 0,
                                    )
                                }) { Text("Shuffle") }
                            }
                        }
                        items(visibleTracks, key = { "track:${it.id}" }) { track ->
                            // Mirror the Library Browser: tap seeds an
                            // ad-hoc queue from the visible search hits so
                            // skip-next walks through the rest of the
                            // results instead of dead-ending after one
                            // track.
                            TrackRow(
                                track = track,
                                isCurrent = track.id == currentTrackId,
                                onClick = {
                                    val idx = visibleTracks.indexOf(track).coerceAtLeast(0)
                                    stationPlayback.playTracks(visibleTracks, startIndex = idx)
                                },
                                onLongPress = { contextTrackId = track.id },
                            )
                        }
                    }
                    if (!showArtists && !showAlbums && !showTracks) {
                        item {
                            // The Liked filter can zero out a non-
                            // empty result set; the user needs to see
                            // that's why, not "no matches" which
                            // implies the search itself missed.
                            val msg = if (likedOnly && results.tracks.isNotEmpty() && visibleTracks.isEmpty()) {
                                "No liked tracks in these results. Turn off the Liked filter to see all matches."
                            } else {
                                "No matches in this scope."
                            }
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            )
                        }
                    }
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
}

/**
 * Empty-state block: header + recent-query chips + clear button. Tap a
 * chip to populate the query field; tap "Clear" to wipe the saved
 * history. Hidden entirely until the user has searched at least once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentSearchesBlock(
    queries: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
) {
    if (queries.isEmpty()) {
        Text(
            "Type to search across your library.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
        return
    }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Recent searches",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            )
            TextButton(onClick = onClear) { Text("Clear") }
        }
        Spacer(Modifier.height(4.dp))
        // FlowRow would wrap nicely but adds an extra dep. A horizontal
        // scrolling Row is good enough for ≤10 chips.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            queries.forEach { q ->
                AssistChip(
                    onClick = { onPick(q) },
                    label = { Text(q) },
                )
            }
        }
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
private fun ArtistRow(artist: Artist, onTap: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Text(artist.name, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AlbumRow(album: Album, onTap: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
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
private fun TrackRow(
    track: Track,
    isCurrent: Boolean = false,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            com.visorcraft.radioshed.ui.util.NowPlayingIndicator(isCurrent)
        }
        Text(
            "${track.format.uppercase()} · ${track.fileName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
    }
}
