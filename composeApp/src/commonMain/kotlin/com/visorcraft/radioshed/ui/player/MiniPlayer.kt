package com.visorcraft.radioshed.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.domain.withoutBracketGroups
import com.visorcraft.radioshed.audio.PlayerState
import com.visorcraft.radioshed.audio.SleepTimer
import com.visorcraft.radioshed.isDesktopHost
import com.visorcraft.radioshed.stations.StationPlayback
import com.visorcraft.radioshed.ui.util.TooltipIconButton
import org.koin.compose.koinInject

/**
 * Sticky bottom transport bar shown whenever something is loaded into
 * the [AudioEngine]. Hidden completely when nothing is playing so it
 * doesn't waste space.
 *
 * Tap the bar, or the expand button at the end of the transport row,
 * to open Now Playing. The button is focusable so a remote can reach
 * it; the transport buttons alone are not that action.
 */
@Composable
fun MiniPlayer(
    onExpand: () -> Unit = {},
    audioEngine: AudioEngine = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    sleepTimer: SleepTimer = koinInject(),
) {
    val nowPlaying by audioEngine.nowPlaying.collectAsStateWithLifecycle()
    val state by audioEngine.state.collectAsStateWithLifecycle()
    val position by audioEngine.position.collectAsStateWithLifecycle()
    val sleepState by sleepTimer.state.collectAsStateWithLifecycle()
    // Desktop-only inline volume slider state. On Android the
    // hardware volume rocker handles this; surfacing an in-app
    // slider on top would crowd the bar without adding value.
    val userVolume by stationPlayback.userVolume.collectAsStateWithLifecycle()
    // Keyed alongside playable.trackId / state below so the "Next:"
    // peek refreshes whenever the priority queue mutates (e.g., the
    // user adds a track via Play next while paused). Without this
    // signal a queue add wouldn't re-evaluate the remembered peek.
    val priorityCount by stationPlayback.priorityCountFlow.collectAsStateWithLifecycle()

    val playable = nowPlaying ?: return
    var contextSheetOpen by remember { mutableStateOf(false) }
    // Tactile feedback when a horizontal-swipe-skip threshold is
    // crossed. Confirms the gesture actually fired without the
    // user having to look at the screen — the phone gives a tiny
    // bump as soon as the skip command goes out.
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    Surface(
        // The MiniPlayer sits at the bottom of App.kt's outer Column, OUTSIDE
        // any Scaffold. We use enableEdgeToEdge() in MainActivity, so the
        // window draws under the system gesture/navigation bar; without this
        // padding the transport buttons render BEHIND the system 3-button
        // navigation strip on phones that use it (Galaxy Z Fold 7, etc.).
        //
        // Gesture handling: tap + long-press live in their own
        // pointerInput (detectTapGestures only fires when no touch
        // slop is crossed, so drags don't conflict). The drag
        // pointerInput uses a single awaitEachGesture with manual axis
        // lock — Compose's gesture system can't arbitrate between two
        // sibling detectVertical/detectHorizontal blocks reliably (they
        // each receive the raw stream independently and can both fire
        // on a diagonal swipe), so we resolve axis at the first slop
        // crossing and only feed events to the matching channel.
        modifier =
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onExpand() },
                        // Long-press surfaces the standard Track context
                        // sheet (heart / play next / add to playlist / …)
                        // so users don't have to expand to Now Playing
                        // and dig into the overflow menu just to like
                        // the current track.
                        onLongPress = { contextSheetOpen = true },
                    )
                }
                .pointerInput(Unit) {
                    // PointerInputScope extends Density, so `.dp.toPx()`
                    // works via the implicit receiver. The threshold is
                    // computed here (rather than from a top-level const)
                    // so it scales with device density: a raw 60-px value
                    // collapses to ~9 dp of perceived movement on a
                    // 420-dpi phone like the Galaxy Fold 7, producing
                    // accidental skips on near-stationary taps.
                    val swipeThresholdPx = 20.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var horizontal = false
                        var vertical = false
                        // First slop crossing decides the axis. The drag
                        // delta is set on the consumed change so subsequent
                        // horizontalDrag/verticalDrag accumulators see it.
                        val slopChange =
                            awaitTouchSlopOrCancellation(down.id) { change, over ->
                                val absX = kotlin.math.abs(over.x)
                                val absY = kotlin.math.abs(over.y)
                                if (absX > absY) horizontal = true else vertical = true
                                change.consume()
                            } ?: return@awaitEachGesture
                        var accum = 0f
                        if (horizontal) {
                            accum += slopChange.positionChange().x
                            horizontalDrag(slopChange.id) { change ->
                                accum += change.positionChange().x
                                change.consume()
                            }
                            when {
                                accum < -swipeThresholdPx -> {
                                    stationPlayback.skipNext()
                                    haptics.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                                    )
                                }
                                accum > swipeThresholdPx -> {
                                    stationPlayback.skipPrevious()
                                    haptics.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                                    )
                                }
                            }
                        } else if (vertical) {
                            accum += slopChange.positionChange().y
                            verticalDrag(slopChange.id) { change ->
                                accum += change.positionChange().y
                                change.consume()
                            }
                            // Negative accum = upward swipe. 20-dp threshold
                            // matches Spotify / Apple Music swipe-up feel.
                            if (accum < -swipeThresholdPx) onExpand()
                        }
                    }
                },
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Column {
            LinearProgressIndicator(
                progress = { position.fraction },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                ArtworkPlaceholder(playable.artworkUri)
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = playable.title.withoutBracketGroups().ifBlank { "Unknown title" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    // When paused, surface what's coming up so the
                    // user knows what they'll hear when they tap
                    // Play. Falls back to the artist/album subtitle
                    // when the queue is empty (e.g., the current
                    // track is the last one in an ad-hoc list) or
                    // when the player is actively playing.
                    val baseSubtitle =
                        listOfNotNull(playable.artist, playable.album)
                            .joinToString(" · ")
                    val pausedNextLabel =
                        if (state == PlayerState.Paused) {
                            val next =
                                remember(state, playable.trackId, priorityCount) {
                                    stationPlayback.upcoming(1).firstOrNull()
                                }
                            next?.displayTitle()?.ifBlank { null }?.let { "Next: $it" }
                        } else {
                            null
                        }
                    val subtitle =
                        pausedNextLabel
                            ?: baseSubtitle.ifBlank { stateLabel(state) }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                    )
                }
                // Sleep-timer badge: persistent countdown so the
                // user knows the timer is armed without expanding to
                // Now Playing. Inserted between the title column and
                // transport buttons; the column carries weight=1f so
                // the badge gets a fixed width and the title
                // truncates cleanly when space is tight.
                SleepTimerBadge(sleepState)
                // Desktop volume control. Visible only on desktop —
                // on touch builds the OS volume keys are the natural
                // affordance, and an inline slider would conflict
                // with the swipe-to-skip gesture handler above.
                if (isDesktopHost) {
                    DesktopVolumeControl(
                        volume = userVolume,
                        onVolumeLive = { stationPlayback.previewUserVolume(it) },
                        onVolumeCommit = { stationPlayback.commitUserVolume(it) },
                    )
                }
                // Skip prev/next bypass the MediaController and call
                // StationPlayback / AudioEngine.seekTo directly. The
                // MediaController-based path filters these commands
                // somewhere we can't reach (PlayerWrapper inside
                // MediaSession re-derives them from queue state). Pause
                // stays on the controller path since it isn't filtered.
                TransportIconButton(
                    icon = Icons.Filled.SkipPrevious,
                    contentDesc = "Previous",
                    onClick = { stationPlayback.skipPrevious() },
                )
                TransportIconButton(
                    icon =
                        if (state == PlayerState.Playing) {
                            Icons.Filled.Pause
                        } else {
                            Icons.Filled.PlayArrow
                        },
                    contentDesc = if (state == PlayerState.Playing) "Pause" else "Play",
                    onClick = { audioEngine.togglePlayPause() },
                )
                TransportIconButton(
                    icon = Icons.Filled.SkipNext,
                    contentDesc = "Next",
                    onClick = { stationPlayback.skipNext() },
                )
                TransportIconButton(
                    icon = Icons.Filled.OpenInFull,
                    contentDesc = "Now playing",
                    onClick = onExpand,
                )
            }
        }
    }

    if (contextSheetOpen) {
        com.visorcraft.radioshed.ui.playlists.TrackContextSheet(
            trackId = playable.trackId,
            onDismiss = { contextSheetOpen = false },
        )
    }
}

@Composable
private fun ArtworkPlaceholder(artworkUri: String?) {
    Box(
        modifier =
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (artworkUri != null) {
            coil3.compose.AsyncImage(
                model = artworkUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun SleepTimerBadge(state: SleepTimer.State) {
    val label: String =
        when (state) {
            SleepTimer.State.Idle -> return
            is SleepTimer.State.Running -> formatSleepCountdown(state.remainingMs)
            SleepTimer.State.EndOfTrack -> "EOT"
        }
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(horizontal = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Filled.Bedtime,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
            )
        }
    }
}

/** Compact countdown for the MiniPlayer badge: "12s" / "23m" / "1h 04m". */
private fun formatSleepCountdown(ms: Long): String {
    if (ms <= 0L) return "0s"
    val totalSec = ms / 1000L
    return when {
        totalSec >= 3600L -> {
            val hours = totalSec / 3600L
            val mins = (totalSec % 3600L) / 60L
            "${hours}h ${mins.toString().padStart(2, '0')}m"
        }
        totalSec >= 60L -> "${totalSec / 60L}m"
        else -> "${totalSec}s"
    }
}

@Composable
private fun TransportIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDesc: String,
    onClick: () -> Unit,
) {
    // Vector icons render identically across system fonts (Roboto on
    // Pixel, Noto on Samsung, SF Pro on iOS) — the original Unicode-glyph
    // approach broke on Samsung Noto where U+23F8 PAUSE has different
    // vertical metrics from ⏮/⏭ and got clipped at the bottom. The
    // contentDescription drives accessibility AND the desktop hover
    // tooltip; the icon drives visuals.
    TooltipIconButton(
        tooltip = contentDesc,
        onClick = onClick,
        modifier =
            Modifier
                .size(48.dp)
                .semantics { contentDescription = contentDesc },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null, // outer modifier already labels it
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun stateLabel(state: PlayerState): String =
    when (state) {
        PlayerState.Buffering -> "Buffering…"
        PlayerState.Playing -> "Playing"
        PlayerState.Paused -> "Paused"
        PlayerState.Idle -> "Idle"
        PlayerState.Ended -> "Ended"
        is PlayerState.Error -> "Error: ${state.message}"
    }
