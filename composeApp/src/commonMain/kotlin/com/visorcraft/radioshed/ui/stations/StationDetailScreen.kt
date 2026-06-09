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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.visorcraft.radioshed.ui.util.TooltipIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.RatingRepository
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.domain.SmartQueryKind
import com.visorcraft.radioshed.domain.SourceRule
import com.visorcraft.radioshed.domain.Station
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.stations.SourceRuleEvaluator
import com.visorcraft.radioshed.stations.StationPlayback
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Per-station detail. Shows the source rule summary, current candidate
 * count, pinned tracks, recently played, and top tracks for this
 * station. Tapping a row plays the track via [StationPlayback]; the
 * pinned section's chip lets the user unpin without diving into a
 * separate management surface.
 *
 * Distinct from the Station Editor (which is for *changing* a station)
 * — this screen is read-only-ish, more of a "what's in this station
 * right now" view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationDetailScreen(
    stationId: Long,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    stationRepo: StationRepository = koinInject(),
    libraryRepo: LibraryRepository = koinInject(),
    ratingRepo: RatingRepository = koinInject(),
    sourceEvaluator: SourceRuleEvaluator = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    audioEngine: com.visorcraft.radioshed.audio.AudioEngine = koinInject(),
) {
    var station by remember { mutableStateOf<Station?>(null) }
    var candidateCount by remember { mutableStateOf<Int?>(null) }
    var pinned by remember { mutableStateOf<List<Track>>(emptyList()) }
    var recent by remember { mutableStateOf<List<Track>>(emptyList()) }
    var top by remember { mutableStateOf<List<Pair<Track, Long>>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }
    val nowPlaying by audioEngine.nowPlaying.collectAsState()
    val currentTrackId = nowPlaying?.trackId
    val scope = rememberCoroutineScope()

    LaunchedEffect(stationId, refreshKey) {
        val s = stationRepo.getById(stationId)
        station = s
        if (s == null) return@LaunchedEffect
        candidateCount = runCatching { sourceEvaluator.evaluate(s.source).size }.getOrNull()
        pinned = stationRepo.pinnedTracks(stationId)
        // Recently played = last 20 history rows resolved to tracks.
        val recentRows = stationRepo.historyForStation(stationId, limit = 20L)
        recent = recentRows.mapNotNull { libraryRepo.getTrackById(it.trackId) }
        // Top tracks aggregated client-side from history (the SQL
        // selectTopPlayedTrackIds is global across stations; this is
        // station-scoped).
        val byTrackId = recentRows.groupingBy { it.trackId }.eachCount()
        top = byTrackId.entries
            .sortedByDescending { it.value }
            .take(10)
            .mapNotNull { (id, count) ->
                libraryRepo.getTrackById(id)?.let { it to count.toLong() }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(station?.name ?: "Station") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TooltipIconButton(
                        tooltip = "Play station",
                        onClick = {
                            scope.launch {
                                stationPlayback.playStation(stationId)
                                onPlay()
                            }
                        },
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val s = station
        if (s == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Station not found.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                )
            }
            return@Scaffold
        }

        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val showFab by remember {
            androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex > 8 }
        }
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { HeaderCard(station = s, candidateCount = candidateCount) }

            if (pinned.isNotEmpty()) {
                item { SectionHeader("Pinned (${pinned.size})") }
                items(pinned, key = { "p:${it.id}" }) { track ->
                    TrackRow(
                        track = track,
                        isCurrent = track.id == currentTrackId,
                        trailing = {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        stationRepo.unpinTrackFromStation(stationId, track.id)
                                        refreshKey += 1
                                    }
                                },
                            ) { Text("Unpin") }
                        },
                        onTap = {
                            scope.launch {
                                stationPlayback.playTracks(pinned, startIndex = pinned.indexOf(track))
                            }
                        },
                    )
                }
            }

            if (top.isNotEmpty()) {
                item { SectionHeader("Top played in this station") }
                items(top, key = { "t:${it.first.id}" }) { (track, plays) ->
                    TrackRow(
                        track = track,
                        isCurrent = track.id == currentTrackId,
                        trailing = {
                            Text(
                                text = "$plays play${if (plays == 1L) "" else "s"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        },
                        onTap = {
                            scope.launch {
                                stationPlayback.playTracks(top.map { it.first }, startIndex = top.indexOfFirst { it.first.id == track.id })
                            }
                        },
                    )
                }
            }

            if (recent.isNotEmpty()) {
                item { SectionHeader("Recently played") }
                items(recent, key = { "r:${it.id}" }) { track ->
                    TrackRow(
                        track = track,
                        isCurrent = track.id == currentTrackId,
                        onTap = {
                            scope.launch {
                                stationPlayback.playTracks(recent, startIndex = recent.indexOf(track))
                            }
                        },
                    )
                }
            }

            if (pinned.isEmpty() && recent.isEmpty() && top.isEmpty()) {
                item {
                    Text(
                        "No history yet — start playing this station to populate.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
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
                    imageVector = androidx.compose.material.icons.Icons.Filled.KeyboardArrowUp,
                    contentDescription = "Scroll to top",
                )
            }
        }
        }
    }
}

@Composable
private fun HeaderCard(station: Station, candidateCount: Int?) {
    val color = if (station.color == 0) Color(0xFFFF7F1A)
                else Color(station.color.toLong() and 0xFFFFFFFFL)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(color),
            )
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = station.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                station.customSubtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = describeSource(station.source) +
                        (candidateCount?.let { " · ~$it candidates" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun TrackRow(
    track: Track,
    isCurrent: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
    onTap: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = track.title.ifBlank { track.fileName },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    com.visorcraft.radioshed.ui.util.NowPlayingIndicator(isCurrent)
                }
                Text(
                    text = track.fileName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing?.invoke()
        }
    }
}

private fun describeSource(rule: SourceRule): String = when (rule) {
    is SourceRule.SmartQuery -> when (rule.kind) {
        SmartQueryKind.NewestN -> "Newest ${rule.limit}"
        SmartQueryKind.OldestN -> "Oldest ${rule.limit}"
        SmartQueryKind.LeastPlayed -> "Least played"
        SmartQueryKind.MostPlayed -> "Most played"
        SmartQueryKind.HighestRated -> "Highest rated"
        SmartQueryKind.LongestUnheard -> "Longest unheard"
        SmartQueryKind.RecentlyAdded -> "Recently added"
        SmartQueryKind.RecentlyRated -> "Recently rated"
        SmartQueryKind.NeverPlayed -> "Never played"
        SmartQueryKind.AdaptiveMix -> "Adaptive mix"
        SmartQueryKind.ForgottenGems -> "Forgotten gems"
        SmartQueryKind.ForgottenGemsByRating -> "Forgotten gems (by rating)"
        SmartQueryKind.LikedSongs -> "Liked songs"
    }
    is SourceRule.FolderSelector -> "${rule.folderIds.size} folder(s)"
    is SourceRule.PlaylistSelector -> "Playlist"
    is SourceRule.ArtistSelector -> "${rule.artistIds.size} artist(s)"
    is SourceRule.AlbumSelector -> "${rule.albumIds.size} album(s)"
    is SourceRule.TagFilter -> "Tag: ${rule.key}"
    is SourceRule.PathPattern -> "Path patterns"
    is SourceRule.DurationFilter -> "Duration filter"
    is SourceRule.FileFormatFilter -> "Format filter"
    is SourceRule.MediaSourceSelector -> "Media source: ${rule.mediaSourceId}"
    is SourceRule.Union -> "Union of ${rule.children.size} rules"
    is SourceRule.Intersection -> "Intersection of ${rule.children.size} rules"
    is SourceRule.Difference -> "Difference"
    is SourceRule.TopN -> "Top ${rule.limit}"
}
