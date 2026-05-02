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
import androidx.compose.material3.IconButton
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
import com.visorcraft.radioshed.data.RatingRepository
import com.visorcraft.radioshed.domain.Album
import com.visorcraft.radioshed.domain.Artist
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.stations.StationPlayback
import com.visorcraft.radioshed.ui.playlists.TrackContextSheet
import androidx.compose.runtime.rememberCoroutineScope
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
    stationPlayback: StationPlayback = koinInject(),
    ratingRepo: RatingRepository = koinInject(),
) {
    var album by remember { mutableStateOf<Album?>(null) }
    var artist by remember { mutableStateOf<Artist?>(null) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var contextTrackId by remember { mutableStateOf<Long?>(null) }
    var overflowOpen by remember { mutableStateOf(false) }
    var massRateConfirm by remember { mutableStateOf<MassRateAction?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(albumId) {
        album = libraryRepo.getAlbumById(albumId)
        artist = album?.artistId?.let { libraryRepo.getArtistById(it) }
        // selectTracksByAlbum already orders by disc_number, track_number —
        // matches what the user expects from a "play album" sequential queue.
        tracks = libraryRepo.tracksForAlbum(albumId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(album?.title ?: "Album", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false },
                        ) {
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
                colors = TopAppBarDefaults.topAppBarColors(
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

                if (isCompact) {
                    AlbumHeaderCompact(
                        album = a,
                        artist = artist,
                        trackCount = tracks.size,
                        onArtistTap = onArtistTap,
                    )
                } else {
                    AlbumHeaderWide(
                        album = a,
                        artist = artist,
                        trackCount = tracks.size,
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
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Shuffle")
                    }
                }

                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(tracks, key = { it.id }) { track ->
                        AlbumTrackRow(
                            track = track,
                            onTap = {
                                val idx = tracks.indexOf(track).coerceAtLeast(0)
                                stationPlayback.playTracks(tracks, startIndex = idx)
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
                        color = if (action == MassRateAction.Clear)
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
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
    onArtistTap: (Long) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        AlbumCover(
            artworkUri = album.artworkUri,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Spacer(Modifier.height(12.dp))
        AlbumMeta(album = album, artist = artist, trackCount = trackCount, onArtistTap = onArtistTap)
    }
}

@Composable
private fun AlbumHeaderWide(
    album: Album,
    artist: Artist?,
    trackCount: Int,
    onArtistTap: (Long) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AlbumCover(
            artworkUri = album.artworkUri,
            modifier = Modifier
                .size(180.dp)
                .aspectRatio(1f),
        )
        Spacer(Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            AlbumMeta(album = album, artist = artist, trackCount = trackCount, onArtistTap = onArtistTap)
        }
    }
}

@Composable
private fun AlbumMeta(
    album: Album,
    artist: Artist?,
    trackCount: Int,
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
    val subtitle = buildString {
        album.year?.let { append(it).append(" · ") }
        append(trackCount).append(if (trackCount == 1) " track" else " tracks")
    }
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
    )
}

@Composable
private fun AlbumCover(artworkUri: String?, modifier: Modifier = Modifier) {
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
private fun AlbumTrackRow(track: Track, onTap: () -> Unit, onLongPress: () -> Unit = {}) {
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title.ifBlank { track.fileName },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
