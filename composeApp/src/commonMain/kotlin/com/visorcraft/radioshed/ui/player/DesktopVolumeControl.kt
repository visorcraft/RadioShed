package com.visorcraft.radioshed.ui.player

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.ui.util.TooltipIconButton

/**
 * Reusable inline volume control for the desktop UI. Speaker icon
 * (auto-swaps between off / down / up), mute-toggle on tap, and a
 * horizontal slider next to it. Identical visual + behavioural
 * contract used by both the bottom MiniPlayer bar and the Now
 * Playing top bar so users see the same thing wherever they reach
 * for it.
 *
 * The slider writes through [onChange] on every value change; the
 * caller routes that to `stationPlayback.setUserVolume(...)` which
 * persists to settings and applies effective volume after any
 * ReplayGain attenuation.
 */
@Composable
fun DesktopVolumeControl(
    volume: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    sliderWidth: Dp = 100.dp,
) {
    // Pre-mute level — restored when the user un-mutes via icon click.
    // Defaulting to 1.0 so the un-mute on a never-touched session
    // takes the user to full volume rather than back to zero.
    var preMute by remember { mutableStateOf(if (volume > 0.02f) volume else 1f) }
    val muted = volume < 0.02f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = 4.dp),
    ) {
        TooltipIconButton(
            tooltip = if (muted) "Unmute" else "Mute",
            onClick = {
                if (muted) {
                    onChange(preMute)
                } else {
                    preMute = volume
                    onChange(0f)
                }
            },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector =
                    when {
                        muted -> Icons.AutoMirrored.Filled.VolumeOff
                        volume < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
                        else -> Icons.AutoMirrored.Filled.VolumeUp
                    },
                contentDescription = if (muted) "Unmute" else "Mute",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Slider(
            value = volume,
            onValueChange = onChange,
            valueRange = 0f..1f,
            modifier = Modifier.width(sliderWidth),
            colors =
                SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                    thumbColor = MaterialTheme.colorScheme.primary,
                ),
        )
    }
}
