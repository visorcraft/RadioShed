package com.visorcraft.radioshed.ui.car

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.audio.PlayerState
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.stations.StationPlayback
import com.visorcraft.radioshed.ui.util.rememberHapticTick
import com.visorcraft.radioshed.ui.util.rememberVoiceInput
import com.visorcraft.radioshed.ui.util.supportsVoiceInput
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Big-button hit target for Drive Mode — well past the 64dp Material default
 *  and the 96dp at-a-glance minimum. */
private val DRIVE_BUTTON_SIZE = 88.dp
private val DRIVE_EMPHASIZED_SIZE = 108.dp
private val DRIVE_ICON_SIZE = 48.dp
private val DRIVE_EMPHASIZED_ICON_SIZE = 64.dp

/**
 * Configurable Drive Mode transport row (Task 2.4). Replaces the hardcoded
 * three-button set that the manual Drive Mode re-skin rendered with one driven
 * by [SettingsStore.getDriveButtons], laid out in the stored canonical order
 * ([SettingsStore.DRIVE_BUTTON_ID_ORDER]: previous, play_pause, next,
 * station_picker, voice_search, mute).
 *
 * The transport-core actions (previous / play_pause / next) come in as
 * callbacks already wired by [com.visorcraft.radioshed.ui.player.NowPlayingScreen]
 * — keeping the big-file diff minimal. The three Drive-only actions are handled
 * here against injected dependencies:
 *
 *  - **station_picker** opens [DriveStationPicker] as a self-hosted full-screen
 *    overlay (local state) so NowPlaying's layout signatures don't have to
 *    grow a hoisted open-flag through three layout composables.
 *  - **voice_search** triggers [rememberVoiceInput]; there is no nav callback
 *    from NowPlaying to the Search screen and no station search-by-name on the
 *    repository, so on a result we play the best station-name match from
 *    [StationRepository.list] (case-insensitive contains, falling back to the
 *    first word). This is the simplest honest behaviour reachable from here.
 *    The button is omitted when the platform has no recognizer.
 *  - **mute** toggles the engine to silence via the USER volume path
 *    ([StationPlayback.previewUserVolume] + [StationPlayback.commitUserVolume]),
 *    remembering the prior level. Routing through the user-volume layer (not a
 *    raw `AudioEngine.setVolume`) means the next track-load's
 *    `applyEffectiveVolume` keeps honouring it and ReplayGain attenuation
 *    multiplies on top rather than fighting it.
 *
 * Button config is read once per composition (the canonical ordering filter
 * already lives in [SettingsStore.getDriveButtons]); Drive Mode itself is
 * observed live upstream, so re-entering the row after a config change picks up
 * the new set.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DriveTransportRow(
    state: PlayerState,
    onPrev: () -> Unit,
    onLongPressPrev: (() -> Unit)?,
    onTogglePlayPause: () -> Unit,
    onLongPressTogglePlayPause: (() -> Unit)?,
    onNext: () -> Unit,
    onLongPressNext: (() -> Unit)?,
    preMuteLevel: Float,
    onPreMuteLevelChange: (Float) -> Unit,
    settings: SettingsStore = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    stationRepo: StationRepository = koinInject(),
) {
    val playing = state == PlayerState.Playing
    val haptic = rememberHapticTick()
    // Distinct "rejection" buzz for a voice command that was heard but
    // matched no station — same haptic helper, different feedback type, so
    // the driver gets eyes-free confirmation the command failed rather than
    // silence. No-op on platforms without haptics (iOS/desktop), like the tick.
    val rejectHaptic = rememberHapticTick(HapticFeedbackType.Reject)
    val scope = rememberCoroutineScope()

    val volume by stationPlayback.userVolume.collectAsState()
    // Pre-mute level so unmute restores the prior level rather than a fixed
    // default. Hoisted to the NowPlaying screen scope (shared with the
    // VolumeRow mute toggle) so muting via one control and unmuting via the
    // other restores the same level rather than two desynced snapshots.

    var pickerOpen by remember { mutableStateOf(false) }

    val voicePicker = rememberVoiceInput { spoken ->
        scope.launch {
            val match = bestStationMatch(stationRepo.list().map { it.id to it.name }, spoken)
            if (match != null) stationPlayback.playStation(match) else rejectHaptic()
        }
    }

    // Stored order is already canonical (SettingsStore filters + the editor
    // re-orders before persist), so render ids straight through.
    val buttonIds = settings.getDriveButtons()
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        buttonIds.forEach { id ->
            when (id) {
                "previous" -> DriveButton(
                    icon = Icons.Filled.SkipPrevious,
                    description = "Previous (long-press to restart current)",
                    onClick = onPrev,
                    onLongClick = onLongPressPrev,
                )

                "play_pause" -> DriveButton(
                    icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    description = if (playing) "Pause" else "Play",
                    emphasized = true,
                    onClick = onTogglePlayPause,
                    onLongClick = onLongPressTogglePlayPause,
                )

                "next" -> DriveButton(
                    icon = Icons.Filled.SkipNext,
                    description = "Next",
                    onClick = onNext,
                    onLongClick = onLongPressNext,
                )

                "station_picker" -> DriveButton(
                    icon = Icons.Filled.GridView,
                    description = "Station picker",
                    onClick = {
                        haptic()
                        pickerOpen = true
                    },
                )

                "voice_search" -> if (supportsVoiceInput()) {
                    DriveButton(
                        icon = Icons.Filled.Mic,
                        description = "Voice search",
                        onClick = {
                            haptic()
                            voicePicker()
                        },
                    )
                }

                "mute" -> {
                    val muted = volume < 0.02f
                    DriveButton(
                        icon = if (muted) Icons.AutoMirrored.Filled.VolumeOff
                               else Icons.AutoMirrored.Filled.VolumeUp,
                        description = if (muted) "Unmute" else "Mute",
                        onClick = {
                            haptic()
                            if (muted) {
                                val target = preMuteLevel.coerceAtLeast(0.1f)
                                stationPlayback.previewUserVolume(target)
                                stationPlayback.commitUserVolume(target)
                            } else {
                                onPreMuteLevelChange(volume)
                                stationPlayback.previewUserVolume(0f)
                                stationPlayback.commitUserVolume(0f)
                            }
                        },
                    )
                }
            }
        }
    }

    if (pickerOpen) {
        DriveStationPicker(onDismiss = { pickerOpen = false })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DriveButton(
    icon: ImageVector,
    description: String,
    emphasized: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val buttonSize = if (emphasized) DRIVE_EMPHASIZED_SIZE else DRIVE_BUTTON_SIZE
    val iconSize = if (emphasized) DRIVE_EMPHASIZED_ICON_SIZE else DRIVE_ICON_SIZE
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .size(buttonSize)
            .clip(RoundedCornerShape(buttonSize / 2))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics { contentDescription = description },
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

/**
 * Best station-name match for a voice transcript. Case-insensitive: an exact
 * (trimmed) name match wins, then a substring contains either direction, then
 * the first transcript word. Returns the matching station id or null.
 */
internal fun bestStationMatch(
    stations: List<Pair<Long, String>>,
    spoken: String,
): Long? {
    val query = spoken.trim().lowercase()
    if (query.isEmpty()) return null
    stations.firstOrNull { it.second.trim().lowercase() == query }?.let { return it.first }
    stations.firstOrNull {
        val name = it.second.lowercase()
        name.contains(query) || query.contains(name)
    }?.let { return it.first }
    val firstWord = query.substringBefore(' ')
    return stations.firstOrNull { it.second.lowercase().contains(firstWord) }?.first
}
