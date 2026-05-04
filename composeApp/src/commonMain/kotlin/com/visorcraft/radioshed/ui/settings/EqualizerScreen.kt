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
import androidx.compose.foundation.clickable
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import com.visorcraft.radioshed.audio.EqPreset
import com.visorcraft.radioshed.audio.EqualizerController
import com.visorcraft.radioshed.data.CustomEqPreset
import com.visorcraft.radioshed.data.SettingsStore
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EqualizerScreen(
    onBack: () -> Unit,
    eq: EqualizerController = koinInject(),
    settings: SettingsStore = koinInject(),
    sharer: com.visorcraft.radioshed.crash.CrashLogSharer = koinInject(),
) {
    val gains by eq.bandGains.collectAsState()
    val enabled by eq.enabled.collectAsState()
    val available by eq.isAvailable.collectAsState()
    var customPresets by remember { mutableStateOf(settings.getCustomEqPresets()) }
    var saveDialogOpen by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<CustomEqPreset?>(null) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    // A/B compare state — `abSnapshot` stashes the user's
    // pre-comparison band gains (the "A" side); `abPresetGains`
    // captures the just-applied preset gains (the "B" side) so the
    // banner can swap between them indefinitely. Tapping the banner
    // toggles A ↔ B; "Done" releases comparison and leaves whichever
    // side is currently audible. Picking a different preset chip,
    // applying a custom preset, or moving a band slider clears
    // comparison automatically. Reset on screen exit by remember
    // semantics — comparison is intentionally session-local.
    var abSnapshot by remember { mutableStateOf<List<Float>?>(null) }
    var abPresetGains by remember { mutableStateOf<List<Float>?>(null) }
    var abLabel by remember { mutableStateOf<String?>(null) }
    var abShowingB by remember { mutableStateOf(true) }
    val applyGains: (List<Float>) -> Unit = { gainsToApply ->
        val n = minOf(gainsToApply.size, eq.numBands)
        for (i in 0 until n) eq.setBand(i, gainsToApply[i])
    }
    val clearAB: () -> Unit = {
        abSnapshot = null
        abPresetGains = null
        abLabel = null
        abShowingB = true
    }
    val startAB: (label: String, presetGains: List<Float>) -> Unit = { label, presetGains ->
        abSnapshot = gains.toList()
        abPresetGains = presetGains
        abLabel = label
        abShowingB = true
        applyGains(presetGains)
    }
    val scope = rememberCoroutineScope()
    val pickFile = com.visorcraft.radioshed.ui.util.rememberFilePicker { bytes ->
        scope.launch {
            val parsed = com.visorcraft.radioshed.data.EqPresetIO.fromJson(bytes.decodeToString())
            if (parsed == null) {
                importStatus = "Couldn't parse preset file."
                return@launch
            }
            // Merge — overwrites by name, preserves other custom presets.
            parsed.presets.forEach { settings.saveCustomEqPreset(it) }
            customPresets = settings.getCustomEqPresets()
            importStatus = "Imported ${parsed.presets.size} preset" +
                if (parsed.presets.size == 1) "." else "s."
        }
    }

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
                        text = buildString {
                            append(if (available) "Active on this session" else "Start playback to activate")
                            if (available) {
                                append(" · ${eq.numBands} bands")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                }
                Switch(checked = enabled, onCheckedChange = eq::setEnabled)
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Presets",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                )
                Row {
                    TextButton(onClick = { pickFile("application/json") }) { Text("Import…") }
                    TextButton(
                        onClick = {
                            scope.launch {
                                val json = com.visorcraft.radioshed.data.EqPresetIO.toJson(customPresets)
                                val ok = sharer.share("RadioShed EQ presets", json)
                                if (!ok) importStatus = "Couldn't share presets."
                            }
                        },
                        enabled = customPresets.isNotEmpty(),
                    ) { Text("Export") }
                    TextButton(
                        onClick = { saveDialogOpen = true },
                        enabled = gains.isNotEmpty(),
                    ) { Text("Save current…") }
                }
            }
            importStatus?.let { msg ->
                Spacer(Modifier.height(4.dp))
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            // A/B compare banner — only when the user has armed a
            // comparison via long-press on a preset chip. Tapping the
            // banner swaps A ↔ B; the X dismisses comparison and
            // restores whichever side is currently showing.
            abLabel?.let { label ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable {
                            val snap = abSnapshot ?: return@clickable
                            val bGains = abPresetGains ?: return@clickable
                            if (abShowingB) {
                                applyGains(snap)
                                abShowingB = false
                            } else {
                                applyGains(bGains)
                                abShowingB = true
                            }
                        },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (abShowingB)
                                "Comparing $label · tap to hear original"
                            else
                                "Original tones · tap to hear $label",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = clearAB) { Text("Done") }
                    }
                }
            }
            // Horizontal-scroll chip row holds both built-in + custom
            // presets; long-press a custom one to delete, long-press a
            // built-in to A/B-compare it against the current bands.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                listOf(EqPreset.Flat, EqPreset.BassBoost, EqPreset.Vocal, EqPreset.Treble).forEach { preset ->
                    val presetGains = remember(preset) { preset.gainsDb.toList() }
                    // The chip's `onClick` is unreachable because the
                    // outer `combinedClickable` consumes the gesture
                    // first; we keep AssistChip purely for its label
                    // + color affordance and drive both tap and
                    // long-press through the modifier.
                    AssistChip(
                        onClick = {},
                        label = { Text(preset.label) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                clearAB()
                                eq.applyPreset(preset)
                            },
                            onLongClick = { startAB(preset.label, presetGains) },
                        ),
                    )
                }
                customPresets.forEach { preset ->
                    AssistChip(
                        onClick = {},
                        label = { Text(preset.name) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                // Apply: write each saved gain back to
                                // the controller. Lengths mismatch when
                                // the device's band count differs from
                                // when the preset was saved — we
                                // tolerate that by applying min-length
                                // and leaving extras at their current
                                // value.
                                clearAB()
                                applyGains(preset.gainsDb)
                            },
                            onLongClick = { deleteCandidate = preset },
                        ),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            BandSliders(
                gains = gains,
                frequencies = eq.bandFrequenciesHz,
                gainRangeDb = eq.gainRangeDb,
                // A manual band edit ends the A/B comparison — the
                // user has just made the bands their own; we don't
                // want a subsequent banner tap to overwrite their
                // tweak with a stale snapshot. clearAB() resets the
                // comparison state and hides the banner; the actual
                // bands stay where the user moved them.
                onChange = { idx, gain ->
                    clearAB()
                    eq.setBand(idx, gain)
                },
            )
        }
    }

    if (saveDialogOpen) {
        var nameText by remember { mutableStateOf("") }
        val confirmSave: () -> Unit = {
            val n = nameText.trim()
            if (n.isNotBlank()) {
                settings.saveCustomEqPreset(
                    CustomEqPreset(name = n, gainsDb = gains.toList()),
                )
                customPresets = settings.getCustomEqPresets()
                saveDialogOpen = false
            }
        }
        AlertDialog(
            onDismissRequest = { saveDialogOpen = false },
            title = { Text("Save EQ preset") },
            text = {
                Column {
                    Text(
                        "Snapshots the current band gains as a named " +
                            "preset. Tap the chip to recall it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = nameText,
                        onValueChange = { nameText = it },
                        label = { Text("Name") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onDone = { confirmSave() },
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = confirmSave,
                    enabled = nameText.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { saveDialogOpen = false }) { Text("Cancel") }
            },
        )
    }

    deleteCandidate?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete preset?") },
            text = { Text("\"${preset.name}\" will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    settings.deleteCustomEqPreset(preset.name)
                    customPresets = settings.getCustomEqPresets()
                    deleteCandidate = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") }
            },
        )
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
