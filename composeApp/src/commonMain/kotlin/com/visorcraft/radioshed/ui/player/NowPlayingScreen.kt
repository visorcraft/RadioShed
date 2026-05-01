package com.visorcraft.radioshed.ui.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.PlayerState
import com.visorcraft.radioshed.data.RatingRepository
import com.visorcraft.radioshed.stations.StationPlayback
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Full-screen Now Playing. BoxWithConstraints drives a single-column
 * stacked layout on compact widths and a two-pane (art-left,
 * controls-right) layout from medium up.
 *
 * Album art is a tinted gradient until Phase 10 wires Cover Art Archive.
 * Lyrics and queue panes are deferred to Phase 6 part 2 (need lyrics
 * fetching + a queue projection out of StationPlayback respectively).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NowPlayingScreen(
    onClose: () -> Unit,
    audioEngine: AudioEngine = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    ratingRepo: RatingRepository = koinInject(),
) {
    val nowPlaying by audioEngine.nowPlaying.collectAsState()
    val state by audioEngine.state.collectAsState()
    val position by audioEngine.position.collectAsState()
    val currentStation by stationPlayback.currentStation.collectAsState()
    val scope = rememberCoroutineScope()

    var rating by remember { mutableStateOf(0.0) }
    LaunchedEffect(nowPlaying?.trackId) {
        rating = nowPlaying?.let { ratingRepo.getRating(it.trackId) } ?: 0.0
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Now Playing", style = MaterialTheme.typography.titleSmall) },
                navigationIcon = {
                    TextButton(onClick = onClose) { Text("▼ Close") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val playable = nowPlaying
        if (playable == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Nothing playing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                )
            }
            return@Scaffold
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            val isCompact = maxWidth < 600.dp
            val accent = stationAccent(currentStation?.color)

            if (isCompact) {
                CompactLayout(
                    playable = playable,
                    state = state,
                    positionMs = position.currentMs,
                    durationMs = position.durationMs,
                    rating = rating,
                    accent = accent,
                    stationName = currentStation?.name,
                    onSeek = { audioEngine.seekTo(it) },
                    onTogglePlayPause = { audioEngine.togglePlayPause() },
                    onPrev = { audioEngine.skipPrevious() },
                    onNext = { stationPlayback.skipNext() },
                    onRate = { stars ->
                        scope.launch {
                            ratingRepo.setRating(playable.trackId, stars)
                            rating = stars
                        }
                    },
                    onDislikeOnStation = {
                        val stationId = currentStation?.id ?: return@CompactLayout
                        scope.launch {
                            ratingRepo.dislikeForStation(playable.trackId, stationId)
                            stationPlayback.skipNext()
                        }
                    },
                )
            } else {
                TwoPaneLayout(
                    playable = playable,
                    state = state,
                    positionMs = position.currentMs,
                    durationMs = position.durationMs,
                    rating = rating,
                    accent = accent,
                    stationName = currentStation?.name,
                    onSeek = { audioEngine.seekTo(it) },
                    onTogglePlayPause = { audioEngine.togglePlayPause() },
                    onPrev = { audioEngine.skipPrevious() },
                    onNext = { stationPlayback.skipNext() },
                    onRate = { stars ->
                        scope.launch {
                            ratingRepo.setRating(playable.trackId, stars)
                            rating = stars
                        }
                    },
                    onDislikeOnStation = {
                        val stationId = currentStation?.id ?: return@TwoPaneLayout
                        scope.launch {
                            ratingRepo.dislikeForStation(playable.trackId, stationId)
                            stationPlayback.skipNext()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun CompactLayout(
    playable: com.visorcraft.radioshed.audio.Playable,
    state: PlayerState,
    positionMs: Long,
    durationMs: Long,
    rating: Double,
    accent: Color,
    stationName: String?,
    onSeek: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRate: (Double) -> Unit,
    onDislikeOnStation: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AlbumArtPlaceholder(
            accent = accent,
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Spacer(Modifier.height(24.dp))
        TrackInfo(playable, stationName, alignment = Alignment.CenterHorizontally)
        Spacer(Modifier.height(16.dp))
        Scrubber(positionMs, durationMs, onSeek)
        Spacer(Modifier.height(16.dp))
        TransportRow(
            state = state,
            onTogglePlayPause = onTogglePlayPause,
            onPrev = onPrev,
            onNext = onNext,
            onDislikeOnStation = onDislikeOnStation,
        )
        Spacer(Modifier.height(20.dp))
        RatingRow(current = rating, onRate = onRate)
    }
}

@Composable
private fun TwoPaneLayout(
    playable: com.visorcraft.radioshed.audio.Playable,
    state: PlayerState,
    positionMs: Long,
    durationMs: Long,
    rating: Double,
    accent: Color,
    stationName: String?,
    onSeek: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRate: (Double) -> Unit,
    onDislikeOnStation: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        AlbumArtPlaceholder(
            accent = accent,
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(1f),
        )
        Spacer(Modifier.width(32.dp))
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
        ) {
            TrackInfo(playable, stationName, alignment = Alignment.Start)
            Spacer(Modifier.height(24.dp))
            Scrubber(positionMs, durationMs, onSeek)
            Spacer(Modifier.height(24.dp))
            TransportRow(
                state = state,
                onTogglePlayPause = onTogglePlayPause,
                onPrev = onPrev,
                onNext = onNext,
                onDislikeOnStation = onDislikeOnStation,
            )
            Spacer(Modifier.height(28.dp))
            RatingRow(current = rating, onRate = onRate)
        }
    }
}

@Composable
private fun AlbumArtPlaceholder(accent: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(accent, accent.copy(alpha = 0.55f)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "♫",
            style = MaterialTheme.typography.displayLarge,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun TrackInfo(
    playable: com.visorcraft.radioshed.audio.Playable,
    stationName: String?,
    alignment: Alignment.Horizontal,
) {
    Column(horizontalAlignment = alignment, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = playable.title.ifBlank { "Unknown title" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (alignment == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start,
        )
        val subtitle = listOfNotNull(playable.artist, playable.album).joinToString(" · ")
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (alignment == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start,
            )
        }
        if (stationName != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "from $stationName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                textAlign = if (alignment == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start,
            )
        }
    }
}

@Composable
private fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    var isUserScrubbing by remember { mutableStateOf(false) }
    var pendingFraction by remember { mutableFloatStateOf(0f) }

    val durationSafe = durationMs.coerceAtLeast(0L)
    val currentFraction = if (durationSafe == 0L) 0f
                          else (positionMs.toFloat() / durationSafe).coerceIn(0f, 1f)
    val displayFraction = if (isUserScrubbing) pendingFraction else currentFraction

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = displayFraction,
            onValueChange = {
                isUserScrubbing = true
                pendingFraction = it
            },
            onValueChangeFinished = {
                if (durationSafe > 0L) {
                    onSeek((pendingFraction * durationSafe).toLong())
                }
                isUserScrubbing = false
            },
            colors = SliderDefaults.colors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatMs(if (isUserScrubbing) (pendingFraction * durationSafe).toLong() else positionMs),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
            Text(
                text = formatMs(durationSafe),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransportRow(
    state: PlayerState,
    onTogglePlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onDislikeOnStation: () -> Unit,
) {
    var dislikeMenuOpen by remember { mutableStateOf(false) }
    val playing = state == PlayerState.Playing

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BigGlyphButton(
            label = "⏮",
            description = "Previous",
            onClick = onPrev,
        )

        BigGlyphButton(
            label = if (playing) "⏸" else "▶",
            description = if (playing) "Pause" else "Play",
            emphasized = true,
            onClick = onTogglePlayPause,
        )

        // Skip-next: tap = skip; long-press opens "don't play on this station" menu.
        Box {
            Surface(
                color = Color.Transparent,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onNext,
                        onLongClick = { dislikeMenuOpen = true },
                    )
                    .semantics { contentDescription = "Next (long-press for options)" },
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "⏭",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
            DropdownMenu(
                expanded = dislikeMenuOpen,
                onDismissRequest = { dislikeMenuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Skip this track") },
                    onClick = {
                        onNext()
                        dislikeMenuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Don't play on this station again") },
                    onClick = {
                        onDislikeOnStation()
                        dislikeMenuOpen = false
                    },
                )
            }
        }
    }
}

@Composable
private fun BigGlyphButton(
    label: String,
    description: String,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(if (emphasized) 80.dp else 64.dp)
            .semantics { contentDescription = description },
    ) {
        Text(
            text = label,
            style = if (emphasized) MaterialTheme.typography.displaySmall
                    else MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun RatingRow(current: Double, onRate: (Double) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        (1..5).forEach { star ->
            val filled = star <= current
            TextButton(
                onClick = {
                    // Tap on the same star you've already given clears the rating.
                    val newValue = if (current == star.toDouble()) 0.0 else star.toDouble()
                    onRate(newValue)
                },
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "$star star${if (star == 1) "" else "s"}" },
            ) {
                Text(
                    text = if (filled) "★" else "☆",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (filled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                )
            }
        }
    }
}

private fun stationAccent(packedColor: Int?): Color {
    if (packedColor == null || packedColor == 0) return Color(0xFFFF7F1A) // brand amber default
    return Color(packedColor.toLong() and 0xFFFFFFFFL)
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000L
    val mins = totalSeconds / 60L
    val secs = totalSeconds % 60L
    return "$mins:${secs.toString().padStart(2, '0')}"
}
