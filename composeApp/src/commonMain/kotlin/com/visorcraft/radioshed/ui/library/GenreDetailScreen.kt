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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.stations.StationPlayback
import com.visorcraft.radioshed.ui.playlists.TrackContextSheet
import com.visorcraft.radioshed.ui.util.rememberHapticTick
import com.visorcraft.radioshed.ui.util.TooltipIconButton
import org.koin.compose.koinInject

/**
 * Per-genre track listing. We use the existing tracksByTag(key="GENRE")
 * query which round-trips through the track_tag junction table. Tap any
 * row to seed an ad-hoc queue from the listing; long-press for "Add to
 * playlist" (mirrors Library Browser semantics).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GenreDetailScreen(
    genre: String,
    onBack: () -> Unit,
    libraryRepo: LibraryRepository = koinInject(),
    stationRepo: com.visorcraft.radioshed.data.StationRepository = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    audioEngine: com.visorcraft.radioshed.audio.AudioEngine = koinInject(),
) {
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var contextTrackId by remember { mutableStateOf<Long?>(null) }
    var overflowOpen by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val settings: com.visorcraft.radioshed.data.SettingsStore = koinInject()
    var trackSort by remember {
        mutableStateOf(
            runCatching { GenreTrackSort.valueOf(settings.getGenreTrackSort() ?: "") }
                .getOrDefault(GenreTrackSort.TitleAsc),
        )
    }
    LaunchedEffect(trackSort) { settings.setGenreTrackSort(trackSort.name) }
    var playCounts by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    LaunchedEffect(tracks) {
        if (tracks.isNotEmpty()) {
            playCounts = stationRepo.playCountForTracks(tracks.map { it.id })
        }
    }
    val nowPlaying by audioEngine.nowPlaying.collectAsStateWithLifecycle()
    val currentTrackId = nowPlaying?.trackId

    LaunchedEffect(genre) {
        tracks = libraryRepo.tracksByTag(key = "GENRE", value = genre)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(genre, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    Box {
                        TooltipIconButton(
                            tooltip = "Sort tracks",
                            onClick = { sortMenuOpen = true },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Sort,
                                contentDescription = "Sort tracks",
                            )
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                        ) {
                            GenreTrackSort.entries.forEach { opt ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(opt.label, modifier = Modifier.weight(1f))
                                            if (trackSort == opt) {
                                                Icon(
                                                    imageVector = Icons.Filled.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        trackSort = opt
                                        sortMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        TooltipIconButton(
                            tooltip = "More",
                            onClick = { overflowOpen = true },
                        ) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Play next") },
                                leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                                enabled = tracks.isNotEmpty(),
                                onClick = {
                                    overflowOpen = false
                                    stationPlayback.playNextAll(tracks)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Add to queue") },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null)
                                },
                                enabled = tracks.isNotEmpty(),
                                onClick = {
                                    overflowOpen = false
                                    stationPlayback.addAllToQueue(tracks)
                                },
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
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            Text(
                genre,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val genreSummary = "${tracks.size} track${if (tracks.size == 1) "" else "s"}"
            val copySummary = com.visorcraft.radioshed.ui.util.rememberCopyToClipboard()
            Text(
                genreSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier =
                    Modifier.clickable {
                        copySummary("$genre — $genreSummary")
                    },
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (tracks.isNotEmpty()) stationPlayback.playTracks(tracks, startIndex = 0)
                    },
                    enabled = tracks.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Play")
                }
                OutlinedButton(
                    onClick = {
                        if (tracks.isNotEmpty()) stationPlayback.playTracks(tracks.shuffled(), startIndex = 0)
                    },
                    enabled = tracks.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                ) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Shuffle")
                }
            }

            Spacer(Modifier.height(8.dp))

            if (tracks.isEmpty()) {
                // Genre rows can persist after their tracks are
                // un-tagged or removed — surface that state instead
                // of rendering a silent empty list. The Play / Shuffle
                // buttons above also disable themselves on
                // tracks.isEmpty() so users can't end up here from a
                // mis-tap.
                Box(
                    modifier = Modifier.fillMaxSize().padding(top = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No tracks tagged with this genre — tags may have " +
                            "changed since the last scan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            } else {
                val sortedTracks =
                    remember(tracks, trackSort, playCounts) {
                        sortGenreTracks(tracks, trackSort, playCounts)
                    }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(sortedTracks, key = { it.id }) { track ->
                        GenreTrackRow(
                            track = track,
                            isCurrent = track.id == currentTrackId,
                            onTap = {
                                val idx = sortedTracks.indexOf(track).coerceAtLeast(0)
                                stationPlayback.playTracks(sortedTracks, startIndex = idx)
                            },
                            onLongPress = { contextTrackId = track.id },
                        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GenreTrackRow(
    track: Track,
    isCurrent: Boolean = false,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val haptic = rememberHapticTick()
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onTap, onLongClick = { haptic(); onLongPress() }),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(10.dp))
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
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
                com.visorcraft.radioshed.ui.util.NowPlayingIndicator(isCurrent)
            }
        }
    }
}

/**
 * Sort options for the Genre detail track list. Default
 * [TitleAsc] reads alphabetically; users browsing a genre tend to
 * scan by name rather than expect a canonical order.
 */
private enum class GenreTrackSort(val label: String) {
    TitleAsc("Title A→Z"),
    DurationAsc("Duration (shortest)"),
    DurationDesc("Duration (longest)"),
    MostPlayed("Most played"),
}

private fun sortGenreTracks(
    tracks: List<com.visorcraft.radioshed.domain.Track>,
    sort: GenreTrackSort,
    playCounts: Map<Long, Long>,
): List<com.visorcraft.radioshed.domain.Track> =
    when (sort) {
        GenreTrackSort.TitleAsc -> tracks.sortedBy { it.title.lowercase() }
        GenreTrackSort.DurationAsc -> tracks.sortedBy { it.durationMs }
        GenreTrackSort.DurationDesc -> tracks.sortedByDescending { it.durationMs }
        GenreTrackSort.MostPlayed -> tracks.sortedByDescending { playCounts[it.id] ?: 0L }
    }
