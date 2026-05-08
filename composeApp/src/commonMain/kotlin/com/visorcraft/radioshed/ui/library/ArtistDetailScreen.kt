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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Album
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import com.visorcraft.radioshed.ui.util.TooltipIconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.domain.Album
import com.visorcraft.radioshed.domain.Artist
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.stations.StationPlayback
import com.visorcraft.radioshed.ui.playlists.TrackContextSheet
import org.koin.compose.koinInject

/**
 * Artist detail — albums (horizontal carousel of cover cards) above
 * the artist's full track list. Play / Shuffle hit the full track list
 * (not just the album subset) so the user can hear everything by them
 * with one tap.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ArtistDetailScreen(
    artistId: Long,
    onBack: () -> Unit,
    onAlbumTap: (Long) -> Unit,
    libraryRepo: LibraryRepository = koinInject(),
    stationRepo: com.visorcraft.radioshed.data.StationRepository = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    audioEngine: com.visorcraft.radioshed.audio.AudioEngine = koinInject(),
) {
    var artist by remember { mutableStateOf<Artist?>(null) }
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    // Per-track play counts from station_history. Mirrors the Album
    // detail "× N" badges so users have consistent listening
    // visibility across both views. Populated in a follow-up effect
    // so the track list renders immediately.
    var playCounts by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    var contextTrackId by remember { mutableStateOf<Long?>(null) }
    var overflowOpen by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val settings: com.visorcraft.radioshed.data.SettingsStore = koinInject()
    var trackSort by remember {
        mutableStateOf(
            runCatching { ArtistTrackSort.valueOf(settings.getArtistTrackSort() ?: "") }
                .getOrDefault(ArtistTrackSort.AlbumOrder),
        )
    }
    LaunchedEffect(trackSort) { settings.setArtistTrackSort(trackSort.name) }
    val nowPlaying by audioEngine.nowPlaying.collectAsState()
    val currentTrackId = nowPlaying?.trackId

    LaunchedEffect(artistId) {
        artist = libraryRepo.getArtistById(artistId)
        albums = libraryRepo.albumsForArtist(artistId)
        tracks = libraryRepo.tracksForArtist(artistId)
    }
    LaunchedEffect(tracks) {
        if (tracks.isNotEmpty()) {
            playCounts = stationRepo.playCountForTracks(tracks.map { it.id })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(artist?.name ?: "Artist", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                            ArtistTrackSort.entries.forEach { opt ->
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
                                    Icon(
                                        Icons.AutoMirrored.Filled.QueueMusic,
                                        null,
                                    )
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val a = artist
        if (a == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "Artist not found.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = a.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val totalRuntimeMs = remember(tracks) {
                tracks.sumOf { it.durationMs }
            }
            val subtitle = buildString {
                append(albums.size).append(if (albums.size == 1) " album" else " albums")
                append(" · ").append(tracks.size).append(if (tracks.size == 1) " track" else " tracks")
                if (totalRuntimeMs > 0L) {
                    append(" · ").append(formatArtistRuntime(totalRuntimeMs))
                }
            }
            val copySummary = com.visorcraft.radioshed.ui.util.rememberCopyToClipboard()
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.clickable {
                    copySummary("${a.name} — $subtitle")
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
                    Text("Play all")
                }
                OutlinedButton(
                    onClick = {
                        if (tracks.isNotEmpty()) stationPlayback.playTracks(tracks.shuffled(), startIndex = 0)
                    },
                    enabled = tracks.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Shuffle")
                }
            }

            Spacer(Modifier.height(16.dp))

            if (albums.isNotEmpty()) {
                Text(
                    text = "Albums",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(albums, key = { it.id }) { album ->
                        AlbumCard(album = album, onTap = { onAlbumTap(album.id) })
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            Text(
                text = "Tracks",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(4.dp))

            val sortedTracks = remember(tracks, trackSort, playCounts) {
                sortArtistTracks(tracks, trackSort, playCounts)
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(sortedTracks, key = { it.id }) { track ->
                    ArtistTrackRow(
                        track = track,
                        isCurrent = track.id == currentTrackId,
                        playCount = playCounts[track.id] ?: 0L,
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

    contextTrackId?.let { id ->
        TrackContextSheet(
            trackId = id,
            onDismiss = { contextTrackId = null },
        )
    }
}

@Composable
private fun AlbumCard(album: Album, onTap: () -> Unit) {
    Column(
        modifier = Modifier.width(120.dp).clickable(onClick = onTap),
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
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.Filled.Album,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistTrackRow(
    track: Track,
    isCurrent: Boolean = false,
    playCount: Long = 0L,
    onTap: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
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
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                com.visorcraft.radioshed.ui.util.NowPlayingIndicator(isCurrent)
            }
            if (playCount > 0L) {
                Text(
                    text = "× $playCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

/**
 * Artist-scope runtime formatter. Always prefer hours since an
 * artist's catalogue almost certainly exceeds an hour; if it doesn't
 * we fall back to minutes for clarity. "1h 23m" / "47 min".
 */
private fun formatArtistRuntime(totalMs: Long): String {
    val totalMin = totalMs / 60_000L
    if (totalMin < 60L) return "$totalMin min"
    val hours = totalMin / 60L
    val mins = totalMin % 60L
    return if (mins == 0L) "${hours}h" else "${hours}h ${mins}m"
}

/**
 * Sort options for the Artist detail track list. Default
 * [AlbumOrder] matches the order coming out of the repository
 * (album → disc → track) — what users expect when they hit "Play"
 * on the artist. Sort state is local-only.
 */
private enum class ArtistTrackSort(val label: String) {
    AlbumOrder("Album order"),
    TitleAsc("Title A→Z"),
    DurationAsc("Duration (shortest)"),
    DurationDesc("Duration (longest)"),
    MostPlayed("Most played"),
    RecentlyAdded("Recently added"),
}

private fun sortArtistTracks(
    tracks: List<com.visorcraft.radioshed.domain.Track>,
    sort: ArtistTrackSort,
    playCounts: Map<Long, Long>,
): List<com.visorcraft.radioshed.domain.Track> = when (sort) {
    ArtistTrackSort.AlbumOrder -> tracks
    ArtistTrackSort.TitleAsc -> tracks.sortedBy { it.title.lowercase() }
    ArtistTrackSort.DurationAsc -> tracks.sortedBy { it.durationMs }
    ArtistTrackSort.DurationDesc -> tracks.sortedByDescending { it.durationMs }
    ArtistTrackSort.MostPlayed -> tracks.sortedByDescending { playCounts[it.id] ?: 0L }
    ArtistTrackSort.RecentlyAdded -> tracks.sortedByDescending { it.addedAt }
}
