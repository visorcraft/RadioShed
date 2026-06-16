package com.visorcraft.radioshed.ui.library

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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.visorcraft.radioshed.data.RatingRepository
import com.visorcraft.radioshed.domain.Album
import com.visorcraft.radioshed.domain.Artist
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.stations.StationPlayback
import com.visorcraft.radioshed.ui.playlists.TrackContextSheet
import com.visorcraft.radioshed.ui.util.rememberHapticTick
import com.visorcraft.radioshed.ui.util.TooltipIconButton
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Album detail — large cover, album info, play-all / shuffle-all, ordered
 * track list. Tapping a track plays from that point through the rest of
 * the album (mirrors the Library Browser tap-to-play queue behavior).
 *
 * Layout adapts at 600dp: under that, art stacks above the metadata;
 * 600dp+ it sits side-by-side. Same breakpoint as Now Playing's
 * compact / two-pane split for visual consistency.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumDetailScreen(
    albumId: Long,
    onBack: () -> Unit,
    onArtistTap: (Long) -> Unit,
    libraryRepo: LibraryRepository = koinInject(),
    stationRepo: com.visorcraft.radioshed.data.StationRepository = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    ratingRepo: RatingRepository = koinInject(),
    audioEngine: com.visorcraft.radioshed.audio.AudioEngine = koinInject(),
) {
    var album by remember { mutableStateOf<Album?>(null) }
    var artist by remember { mutableStateOf<Artist?>(null) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    // Per-track play counts from station_history. Used to render a
    // subtle "× N" badge on each track row so the user can spot
    // their over-rotated favourites within the album. Populated in
    // a follow-up effect so the track list renders immediately.
    var playCounts by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    var contextTrackId by remember { mutableStateOf<Long?>(null) }
    val nowPlaying by audioEngine.nowPlaying.collectAsStateWithLifecycle()
    val currentTrackId = nowPlaying?.trackId
    var overflowOpen by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val settings: com.visorcraft.radioshed.data.SettingsStore = koinInject()
    var trackSort by remember {
        mutableStateOf(
            runCatching { AlbumTrackSort.valueOf(settings.getAlbumTrackSort() ?: "") }
                .getOrDefault(AlbumTrackSort.TrackNumber),
        )
    }
    LaunchedEffect(trackSort) { settings.setAlbumTrackSort(trackSort.name) }
    var massRateConfirm by remember { mutableStateOf<MassRateAction?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(albumId) {
        album = libraryRepo.getAlbumById(albumId)
        artist = album?.artistId?.let { libraryRepo.getArtistById(it) }
        // selectTracksByAlbum already orders by disc_number, track_number —
        // matches what the user expects from a "play album" sequential queue.
        tracks = libraryRepo.tracksForAlbum(albumId)
    }
    LaunchedEffect(tracks) {
        if (tracks.isNotEmpty()) {
            playCounts = stationRepo.playCountForTracks(tracks.map { it.id })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(album?.title ?: "Album", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                            AlbumTrackSort.entries.forEach { opt ->
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
                            DropdownMenuItem(
                                text = { Text("Rate all 5 stars") },
                                leadingIcon = { Icon(Icons.Filled.Star, null) },
                                enabled = tracks.isNotEmpty(),
                                onClick = {
                                    overflowOpen = false
                                    massRateConfirm = MassRateAction.RateFive
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Clear all ratings") },
                                leadingIcon = { Icon(Icons.Filled.StarOutline, null) },
                                enabled = tracks.isNotEmpty(),
                                onClick = {
                                    overflowOpen = false
                                    massRateConfirm = MassRateAction.Clear
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
        val a = album
        if (a == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "Album not found.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
            return@Scaffold
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            val isCompact = maxWidth < 600.dp

            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(16.dp))

                val totalRuntimeMs =
                    remember(tracks) {
                        tracks.sumOf { it.durationMs }
                    }
                if (isCompact) {
                    AlbumHeaderCompact(
                        album = a,
                        artist = artist,
                        trackCount = tracks.size,
                        totalRuntimeMs = totalRuntimeMs,
                        onArtistTap = onArtistTap,
                    )
                } else {
                    AlbumHeaderWide(
                        album = a,
                        artist = artist,
                        trackCount = tracks.size,
                        totalRuntimeMs = totalRuntimeMs,
                        onArtistTap = onArtistTap,
                    )
                }

                Spacer(Modifier.height(16.dp))

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

                val sortedTracks =
                    remember(tracks, trackSort, playCounts) {
                        sortAlbumTracks(tracks, trackSort, playCounts)
                    }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(sortedTracks, key = { it.id }) { track ->
                        AlbumTrackRow(
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
    }

    contextTrackId?.let { id ->
        TrackContextSheet(
            trackId = id,
            onDismiss = { contextTrackId = null },
        )
    }

    massRateConfirm?.let { action ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { massRateConfirm = null },
            title = {
                Text(
                    when (action) {
                        MassRateAction.RateFive -> "Rate every track 5 stars?"
                        MassRateAction.Clear -> "Clear ratings on every track?"
                    },
                )
            },
            text = {
                Text(
                    when (action) {
                        MassRateAction.RateFive ->
                            "This overwrites the ratings on all ${tracks.size} tracks " +
                                "in this album. Existing ratings (including 1★ tracks " +
                                "you've explicitly downvoted) will be replaced with 5★."
                        MassRateAction.Clear ->
                            "Removes the rating from all ${tracks.size} tracks in " +
                                "this album. Top Rated and Forgotten Gems stations " +
                                "won't include them anymore."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val snapshot = tracks
                    val applied = action
                    massRateConfirm = null
                    scope.launch {
                        snapshot.forEach { t ->
                            when (applied) {
                                MassRateAction.RateFive -> ratingRepo.setRating(t.id, 5.0)
                                MassRateAction.Clear -> ratingRepo.clearRating(t.id)
                            }
                        }
                    }
                }) {
                    Text(
                        when (action) {
                            MassRateAction.RateFive -> "Rate all"
                            MassRateAction.Clear -> "Clear"
                        },
                        color =
                            if (action == MassRateAction.Clear) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { massRateConfirm = null }) { Text("Cancel") }
            },
        )
    }
}

private enum class MassRateAction { RateFive, Clear }

@Composable
private fun AlbumHeaderCompact(
    album: Album,
    artist: Artist?,
    trackCount: Int,
    totalRuntimeMs: Long,
    onArtistTap: (Long) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        AlbumCover(
            artworkUri = album.artworkUri,
            modifier =
                Modifier
                    .widthIn(max = 320.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f),
        )
        Spacer(Modifier.height(12.dp))
        AlbumMeta(
            album = album,
            artist = artist,
            trackCount = trackCount,
            totalRuntimeMs = totalRuntimeMs,
            onArtistTap = onArtistTap,
        )
    }
}

@Composable
private fun AlbumHeaderWide(
    album: Album,
    artist: Artist?,
    trackCount: Int,
    totalRuntimeMs: Long,
    onArtistTap: (Long) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AlbumCover(
            artworkUri = album.artworkUri,
            modifier =
                Modifier
                    .size(180.dp)
                    .aspectRatio(1f),
        )
        Spacer(Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            AlbumMeta(
                album = album,
                artist = artist,
                trackCount = trackCount,
                totalRuntimeMs = totalRuntimeMs,
                onArtistTap = onArtistTap,
            )
        }
    }
}

@Composable
private fun AlbumMeta(
    album: Album,
    artist: Artist?,
    trackCount: Int,
    totalRuntimeMs: Long,
    onArtistTap: (Long) -> Unit,
) {
    Text(
        text = album.title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
    )
    if (artist != null) {
        Text(
            text = artist.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onArtistTap(artist.id) },
        )
    }
    val subtitle =
        buildString {
            album.year?.let { append(it).append(" · ") }
            append(trackCount).append(if (trackCount == 1) " track" else " tracks")
            if (totalRuntimeMs > 0L) {
                append(" · ").append(formatRuntime(totalRuntimeMs))
            }
        }
    val copySummary = com.visorcraft.radioshed.ui.util.rememberCopyToClipboard()
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        modifier =
            Modifier.clickable {
                val artistPart = artist?.name?.let { " · $it" } ?: ""
                copySummary("${album.title}$artistPart — $subtitle")
            },
    )
}

/**
 * Album / artist runtime formatter. "47:32" for under an hour, "1h 12m"
 * for ≥ 1h. The under-an-hour "mm:ss" matches what listeners see on the
 * track scrubber, so the album line reads as "this is what plays end to
 * end". Hours-format swaps to a friendlier "Xh Ym" since "1:12:34"
 * mid-line in a metadata blob is hard to parse.
 */
private fun formatRuntime(totalMs: Long): String {
    val totalSec = totalMs / 1000L
    if (totalSec < 60L * 60L) {
        val mm = totalSec / 60L
        val ss = totalSec % 60L
        return "$mm:${ss.toString().padStart(2, '0')}"
    }
    val hours = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    return "${hours}h ${mins}m"
}

@Composable
private fun AlbumCover(
    artworkUri: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (artworkUri != null) {
            coil3.compose.AsyncImage(
                model = artworkUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTrackRow(
    track: Track,
    isCurrent: Boolean = false,
    playCount: Long = 0L,
    onTap: () -> Unit,
    onLongPress: () -> Unit = {},
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
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Track number lane — fixed width so titles align.
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = track.trackNumber?.toString() ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                )
            }
            Spacer(Modifier.size(8.dp))
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
            // Play count — only shown when > 0 so the lane stays
            // empty for never-played tracks (visual cue is itself
            // useful: "what haven't I heard yet on this album?").
            // Format prefers a leading multiplication sign (× 12)
            // over a "12 plays" word so the lane stays narrow.
            if (playCount > 0L) {
                Text(
                    text = "× $playCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text(
                text = formatDurationShort(track.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

private fun formatDurationShort(ms: Long): String {
    if (ms <= 0L) return "—"
    val totalSeconds = ms / 1000L
    val mins = totalSeconds / 60L
    val secs = totalSeconds % 60L
    return "$mins:${secs.toString().padStart(2, '0')}"
}

/**
 * Sort options for the Album detail track list. Default is
 * [TrackNumber] which preserves the disc/track-number order coming
 * out of `selectTracksByAlbum`. Local-only state — not persisted
 * across screen entries, since the user's intent typically only
 * lives within one visit (re-entering the album resets to canonical
 * order, which is rarely what they want to override per-album
 * permanently).
 */
private enum class AlbumTrackSort(val label: String) {
    TrackNumber("Track number"),
    TitleAsc("Title A→Z"),
    DurationAsc("Duration (shortest)"),
    DurationDesc("Duration (longest)"),
    MostPlayed("Most played"),
}

private fun sortAlbumTracks(
    tracks: List<com.visorcraft.radioshed.domain.Track>,
    sort: AlbumTrackSort,
    playCounts: Map<Long, Long>,
): List<com.visorcraft.radioshed.domain.Track> =
    when (sort) {
        AlbumTrackSort.TrackNumber -> tracks
        AlbumTrackSort.TitleAsc -> tracks.sortedBy { it.title.lowercase() }
        AlbumTrackSort.DurationAsc -> tracks.sortedBy { it.durationMs }
        AlbumTrackSort.DurationDesc -> tracks.sortedByDescending { it.durationMs }
        AlbumTrackSort.MostPlayed -> tracks.sortedByDescending { playCounts[it.id] ?: 0L }
    }
