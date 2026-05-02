package com.visorcraft.radioshed.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.audio.EqPreset
import com.visorcraft.radioshed.audio.EqualizerController
import org.koin.compose.koinInject

/**
 * Equalizer screen — toggle, preset row, vertical band sliders.
 *
 * Sliders are vertical-style (rotated horizontal Slider) so the EQ
 * reads like classic mixer-board hardware. The controller's
 * [isAvailable] StateFlow gates the UI: until ExoPlayer reports an
 * audio session id, sliders are present but show a "Start playback to
 * activate" hint — sliders themselves still mutate state, just have
 * no audible effect yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    onBack: () -> Unit,
    eq: EqualizerController = koinInject(),
) {
    val gains by eq.bandGains.collectAsState()
    val enabled by eq.enabled.collectAsState()
    val available by eq.isAvailable.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Equalizer") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (enabled) "On" else "Off",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (available) "Active on this session"
                        else "Start playback to activate",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                }
                Switch(checked = enabled, onCheckedChange = eq::setEnabled)
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "Presets",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                listOf(EqPreset.Flat, EqPreset.BassBoost, EqPreset.Vocal, EqPreset.Treble).forEach { preset ->
                    AssistChip(
                        onClick = { eq.applyPreset(preset) },
                        label = { Text(preset.label) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            BandSliders(
                gains = gains,
                frequencies = eq.bandFrequenciesHz,
                gainRangeDb = eq.gainRangeDb,
                onChange = eq::setBand,
            )
        }
    }
}

@Composable
private fun BandSliders(
    gains: FloatArray,
    frequencies: List<Int>,
    gainRangeDb: ClosedFloatingPointRange<Float>,
    onChange: (Int, Float) -> Unit,
) {
    if (gains.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Equalizer not available on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
        return
    }
    // Horizontal row of band sliders. Each slider is laid out vertically
    // by giving the container fixed height + putting the Slider in a
    // weighted Row child (Compose's Slider is horizontal-only — we
    // render it horizontally and stack vertically). Simpler than rotating.
    Column(modifier = Modifier.fillMaxWidth()) {
        gains.forEachIndexed { i, value ->
            BandSliderRow(
                index = i,
                authoritativeGain = value,
                centerHz = frequencies.getOrNull(i) ?: 0,
                gainRangeDb = gainRangeDb,
                onChange = onChange,
            )
        }
    }
}

/**
 * One band's row. Holds a local `pendingValue` state that the slider
 * binds to, calling `onChange` only on `onValueChangeFinished` —
 * dragging used to fire setBandLevel hundreds of times per second,
 * which produces audible level stuttering and burns the audiofx
 * dispatcher. The `pendingValue` resets to the authoritative gain
 * whenever the parent's gain changes (preset applied, restored from
 * cache, etc.).
 */
@Composable
private fun BandSliderRow(
    index: Int,
    authoritativeGain: Float,
    centerHz: Int,
    gainRangeDb: ClosedFloatingPointRange<Float>,
    onChange: (Int, Float) -> Unit,
) {
    var pendingValue by remember(authoritativeGain) {
        mutableStateOf(authoritativeGain)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatHz(centerHz),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier.width(56.dp),
        )
        Slider(
            value = pendingValue,
            onValueChange = { pendingValue = it },
            onValueChangeFinished = { onChange(index, pendingValue) },
            valueRange = gainRangeDb,
            steps = 0,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${if (pendingValue >= 0) "+" else ""}${"%.1f".format(pendingValue)} dB",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.width(64.dp),
        )
    }
}

private fun formatHz(hz: Int): String = when {
    hz <= 0 -> "—"
    hz >= 1000 -> "${hz / 1000}k"
    else -> "$hz"
}
