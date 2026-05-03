package com.visorcraft.radioshed.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.PlayerState
import com.visorcraft.radioshed.audio.SleepTimer
import com.visorcraft.radioshed.stations.StationPlayback
import org.koin.compose.koinInject

/**
 * Sticky bottom transport bar shown whenever something is loaded into
 * the [AudioEngine]. Hidden completely when nothing is playing so it
 * doesn't waste space.
 *
 * Tap the bar to expand to a full Now Playing screen — that screen
 * doesn't exist yet (Phase 6 territory), so for now [onExpand] is a
 * no-op route. Transport buttons use Unicode glyphs to avoid pulling
 * in the material-icons-extended dep just for a v1 mini-player; we'll
 * swap to proper icons when the Now Playing screen lands.
 */
@Composable
fun MiniPlayer(
    onExpand: () -> Unit = {},
    audioEngine: AudioEngine = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    sleepTimer: SleepTimer = koinInject(),
) {
    val nowPlaying by audioEngine.nowPlaying.collectAsState()
    val state by audioEngine.state.collectAsState()
    val position by audioEngine.position.collectAsState()
    val sleepState by sleepTimer.state.collectAsState()

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
        // Tap + swipe-up are handled in a SINGLE pointerInput so they
        // can coexist. The previous combo of `.draggable + .clickable`
        // had a known Compose interaction issue: a slow deliberate tap
        // that crossed touchSlop got consumed by `draggable` and the
        // clickable never fired. detectTapGestures and
        // detectVerticalDragGestures inside one pointerInput cooperate
        // through the gesture-resolution algorithm — taps fire on
        // up-event without exceeding touchSlop, drags fire when slop
        // is crossed.
        //
        // Note: the Surface still gets ripple via the underlying
        // Material container; we lose the per-press ripple animation
        // here for the tap-to-expand. That's an acceptable tradeoff —
        // the transport buttons inside still get their own ripples.
        modifier = Modifier
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
                var accum = 0f
                detectVerticalDragGestures(
                    onDragStart = { accum = 0f },
                    onDragEnd = {
                        // Negative accum = upward swipe. ~20dp threshold
                        // matches Spotify / Apple Music swipe-up feel.
                        if (accum < -SWIPE_THRESHOLD_PX) onExpand()
                        accum = 0f
                    },
                    onDragCancel = { accum = 0f },
                    onVerticalDrag = { _, dy -> accum += dy },
                )
            }
            // Horizontal swipe: left = next track, right = previous.
            // Lives in its own pointerInput so Compose's gesture-
            // resolution can arbitrate between this and the vertical
            // swipe — whichever axis exceeds touch-slop first wins,
            // and the other is cancelled. Both fire only on drag-end
            // so the user has to commit to the gesture (no
            // accidental skips from a small horizontal twitch while
            // tapping).
            .pointerInput(Unit) {
                var accum = 0f
                detectHorizontalDragGestures(
                    onDragStart = { accum = 0f },
                    onDragEnd = {
                        when {
                            accum < -SWIPE_THRESHOLD_PX -> {
                                stationPlayback.skipNext()
                                haptics.performHapticFeedback(
                                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                )
                            }
                            accum > SWIPE_THRESHOLD_PX -> {
                                stationPlayback.skipPrevious()
                                haptics.performHapticFeedback(
                                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                )
                            }
                        }
                        accum = 0f
                    },
                    onDragCancel = { accum = 0f },
                    onHorizontalDrag = { _, dx -> accum += dx },
                )
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
                        text = playable.title.ifBlank { "Unknown title" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    val subtitle = listOfNotNull(playable.artist, playable.album)
                        .joinToString(" · ")
                        .ifBlank { stateLabel(state) }
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
                    icon = if (state == PlayerState.Playing) Icons.Filled.Pause
                           else Icons.Filled.PlayArrow,
                    contentDesc = if (state == PlayerState.Playing) "Pause" else "Play",
                    onClick = { audioEngine.togglePlayPause() },
                )
                TransportIconButton(
                    icon = Icons.Filled.SkipNext,
                    contentDesc = "Next",
                    onClick = { stationPlayback.skipNext() },
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
        modifier = Modifier
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
    val label: String = when (state) {
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

/** "5m" / "23m" / "1:04" / "12s" — compact enough for the MiniPlayer. */
private fun formatSleepCountdown(ms: Long): String {
    if (ms <= 0L) return "0s"
    val totalSec = ms / 1000L
    return when {
        totalSec >= 3600L -> "${totalSec / 3600L}:${((totalSec % 3600L) / 60L).toString().padStart(2, '0')}h"
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
    // contentDescription drives accessibility; the icon drives visuals.
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = contentDesc },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,  // outer modifier already labels it
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private const val SWIPE_THRESHOLD_PX: Float = 60f

private fun stateLabel(state: PlayerState): String = when (state) {
    PlayerState.Buffering -> "Buffering…"
    PlayerState.Playing -> "Playing"
    PlayerState.Paused -> "Paused"
    PlayerState.Idle -> "Idle"
    PlayerState.Ended -> "Ended"
    is PlayerState.Error -> "Error: ${state.message}"
}
