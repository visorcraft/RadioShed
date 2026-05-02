package com.visorcraft.radioshed.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
        // The MiniPlayer sits at the bottom of App.kt's outer Column, OUTSIDE
        // any Scaffold. We use enableEdgeToEdge() in MainActivity, so the
        // window draws under the system gesture/navigation bar; without this
        // padding the transport buttons render BEHIND the system 3-button
        // navigation strip on phones that use it (Galaxy Z Fold 7, etc.).
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .clickable(onClick = onExpand),
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
                TransportIconButton(
                    icon = Icons.Filled.SkipPrevious,
                    contentDesc = "Previous",
                    onClick = { audioEngine.skipPrevious() },
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
                    onClick = { audioEngine.skipNext() },
                )
            }
        }
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

private fun stateLabel(state: PlayerState): String = when (state) {
    PlayerState.Buffering -> "Buffering…"
    PlayerState.Playing -> "Playing"
    PlayerState.Paused -> "Paused"
    PlayerState.Idle -> "Idle"
    PlayerState.Ended -> "Ended"
    is PlayerState.Error -> "Error: ${state.message}"
}
