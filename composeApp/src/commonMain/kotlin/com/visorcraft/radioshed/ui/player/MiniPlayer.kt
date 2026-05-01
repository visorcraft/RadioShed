package com.visorcraft.radioshed.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.PlayerState
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
) {
    val nowPlaying by audioEngine.nowPlaying.collectAsState()
    val state by audioEngine.state.collectAsState()
    val position by audioEngine.position.collectAsState()

    val playable = nowPlaying ?: return

    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onExpand),
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
                ArtworkPlaceholder()
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
                TransportButton(label = "⏮", contentDesc = "Previous") {
                    audioEngine.skipPrevious()
                }
                TransportButton(
                    label = if (state == PlayerState.Playing) "⏸" else "▶",
                    contentDesc = if (state == PlayerState.Playing) "Pause" else "Play",
                    emphasized = true,
                ) { audioEngine.togglePlayPause() }
                TransportButton(label = "⏭", contentDesc = "Next") {
                    audioEngine.skipNext()
                }
            }
        }
    }
}

@Composable
private fun ArtworkPlaceholder() {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "♫",   // music note
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun TransportButton(
    label: String,
    contentDesc: String,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.size(width = 40.dp, height = 40.dp),
    ) {
        Text(
            text = label,
            style = if (emphasized) MaterialTheme.typography.titleLarge
                    else MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // contentDescription for accessibility — not visually rendered
        // because TextButton labels are textual, but kept here for
        // potential later refactor to a proper Icon.
        @Suppress("UNUSED_EXPRESSION")
        contentDesc
    }
}

private fun stateLabel(state: PlayerState): String = when (state) {
    PlayerState.Buffering -> "Buffering…"
    PlayerState.Playing -> "Playing"
    PlayerState.Paused -> "Paused"
    PlayerState.Idle -> "Idle"
    PlayerState.Ended -> "Ended"
    is PlayerState.Error -> "Error: ${state.message}"
}
