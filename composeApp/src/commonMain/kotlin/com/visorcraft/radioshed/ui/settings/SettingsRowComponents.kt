package com.visorcraft.radioshed.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shared settings-row primitives used by both [AppSettingsScreen] and the DJ
 * settings screen. Previously each screen carried its own private copies that
 * had drifted apart; consolidating them here keeps the look consistent and
 * gives the DJ screen the [enabled] gating it needs (master toggle stays live
 * while dependent rows grey out) without forking the components again.
 */

/** A tappable settings card with a title, subtitle, and trailing [Switch]. */
@Composable
internal fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Spacer(Modifier.size(12.dp))
            Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        }
    }
}

/** Small uppercase-weight header that introduces a group of settings rows. */
@Composable
internal fun SettingsSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
}

/**
 * Labelled minute-of-day slider (0..1439) with a live `h:mm` readout. Used by
 * both the DJ quiet-hours editor and the Drive Mode schedule-window editor.
 *
 * Lifts the drag value into local state so the readout tracks the finger but
 * the [onChange] commit only fires on release — mirrors the AppSettings slider
 * idiom and keeps prefs writes off the per-frame path.
 */
@Composable
internal fun MinutesOfDaySlider(
    label: String,
    minutes: Int,
    onChange: (Int) -> Unit,
    enabledControls: Boolean = true,
) {
    var dragging by remember { mutableStateOf(false) }
    // Initial value seeds the drag buffer only; display reads [minutes] when not dragging.
    var pending by remember { mutableStateOf(minutes) }
    val display = if (dragging) pending else minutes
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface.copy(
                alpha = if (enabledControls) 1f else 0.4f,
            ),
        )
        Text(
            formatHhMm(display),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(
                alpha = if (enabledControls) 0.8f else 0.4f,
            ),
        )
    }
    Slider(
        value = display.toFloat(),
        enabled = enabledControls,
        onValueChange = {
            dragging = true
            pending = it.toInt().coerceIn(0, 1439)
        },
        onValueChangeFinished = {
            onChange(pending)
            dragging = false
        },
        valueRange = 0f..1439f,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Formats a minute-of-day (0..1439) as a 24-hour h:mm label. */
internal fun formatHhMm(minuteOfDay: Int): String {
    val hours = (minuteOfDay / 60).coerceIn(0, 23)
    val minutes = (minuteOfDay % 60).coerceIn(0, 59)
    val mm = if (minutes < 10) "0$minutes" else "$minutes"
    return "$hours:$mm"
}
