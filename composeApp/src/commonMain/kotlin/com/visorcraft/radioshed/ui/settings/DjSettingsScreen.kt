package com.visorcraft.radioshed.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.ClipPlayer
import com.visorcraft.radioshed.audio.TtsEngine
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.stations.DEFAULT_DJ_TEMPLATES
import com.visorcraft.radioshed.stations.DjSlots
import com.visorcraft.radioshed.stations.DjTemplate
import com.visorcraft.radioshed.stations.DjTemplateVars
import com.visorcraft.radioshed.stations.encodeTemplatesJson
import com.visorcraft.radioshed.stations.parseTemplatesJson
import com.visorcraft.radioshed.ui.util.rememberHapticTick
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * AI DJ configuration: master toggle, announcement cadence, quiet hours,
 * ducking level, voice preview, and the per-slot template editor.
 *
 * State is hoisted via [SettingsStore] reads held in compose state; every
 * control writes straight through to its setter so the change survives
 * process death (and is picked up by the running [com.visorcraft.radioshed
 * .stations.DjAnnouncer] on the next transition). All controls except the
 * master toggle are disabled while the DJ is off.
 *
 * The template editor parses the persisted JSON blob (or [DEFAULT_DJ_TEMPLATES]
 * when blank/corrupt) into a mutable slot → variants map, edits it in place,
 * and re-serializes the full effective map back through [SettingsStore
 * .setDjTemplates] on every commit. A "Reset to defaults" action clears the
 * blob (empty string ↔ use-defaults contract).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DjSettingsScreen(
    onBack: () -> Unit,
    settings: SettingsStore = koinInject(),
    tts: TtsEngine = koinInject(),
    clipPlayer: ClipPlayer = koinInject(),
    audioEngine: AudioEngine = koinInject(),
) {
    var enabled by remember { mutableStateOf(settings.getDjEnabled()) }
    var frequency by remember { mutableStateOf(settings.getDjFrequency()) }
    var frequencyN by remember { mutableStateOf(settings.getDjFrequencyN()) }
    var frequencyMinutes by remember { mutableStateOf(settings.getDjFrequencyMinutes()) }
    var quietStart by remember { mutableStateOf(settings.getDjQuietStartMin()) }
    var quietEnd by remember { mutableStateOf(settings.getDjQuietEndMin()) }
    var duckFraction by remember { mutableStateOf(settings.getDjDuckFraction()) }

    // Effective template map: persisted blob → parsed, else defaults. Held as
    // compose state so edits re-render; persisted on every commit.
    var templates by remember {
        mutableStateOf(parseTemplatesJson(settings.getDjTemplates()) ?: DEFAULT_DJ_TEMPLATES)
    }
    val persistTemplates: (Map<String, List<String>>) -> Unit = { next ->
        templates = next
        settings.setDjTemplates(encodeTemplatesJson(next))
    }

    var previewing by remember { mutableStateOf(false) }
    val ttsAvailable = remember { tts.isAvailable() }
    val scope = rememberCoroutineScope()
    val haptic = rememberHapticTick()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI DJ") },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SettingsSectionHeader("AI DJ")
            SettingsToggleRow(
                title = "AI DJ",
                subtitle = "An automated voice introduces tracks, recaps what just played, " +
                    "and reads station IDs between songs. Purely decorative — never " +
                    "interrupts or breaks playback.",
                checked = enabled,
                enabled = true,
                onCheckedChange = { v ->
                    haptic()
                    enabled = v
                    settings.setDjEnabled(v)
                },
            )

            Spacer(Modifier.height(8.dp))
            FrequencySection(
                enabledControls = enabled,
                frequency = frequency,
                onFrequencyChange = { v ->
                    frequency = v
                    settings.setDjFrequency(v)
                },
                frequencyN = frequencyN,
                onFrequencyNChange = { v ->
                    frequencyN = v
                    settings.setDjFrequencyN(v)
                },
                frequencyMinutes = frequencyMinutes,
                onFrequencyMinutesChange = { v ->
                    frequencyMinutes = v
                    settings.setDjFrequencyMinutes(v)
                },
            )

            Spacer(Modifier.height(8.dp))
            QuietHoursSection(
                enabledControls = enabled,
                startMin = quietStart,
                onStartChange = { v ->
                    quietStart = v
                    settings.setDjQuietStartMin(v)
                },
                endMin = quietEnd,
                onEndChange = { v ->
                    quietEnd = v
                    settings.setDjQuietEndMin(v)
                },
            )

            Spacer(Modifier.height(8.dp))
            DuckSlider(
                enabledControls = enabled,
                fraction = duckFraction,
                onChange = { v ->
                    duckFraction = v
                    settings.setDjDuckFraction(v)
                },
            )

            Spacer(Modifier.height(8.dp))
            PreviewVoiceRow(
                // Preview needs a real TTS engine AND the DJ on; disabled while a
                // clip is already in flight so taps don't stack ducks.
                enabledControls = enabled && ttsAvailable && !previewing,
                ttsAvailable = ttsAvailable,
                previewing = previewing,
                onPreview = {
                    if (previewing) return@PreviewVoiceRow
                    previewing = true
                    scope.launch {
                        // Mirror DjAnnouncer's duck → play → restore in miniature.
                        // Any failure is a silent no-op; the finally always
                        // restores and clears the in-flight flag.
                        val prior = audioEngine.volume.value
                        try {
                            runCatching {
                                val sample = DjTemplate.render(
                                    DjTemplate.pickVariant(
                                        DEFAULT_DJ_TEMPLATES[DjSlots.STATION_ID].orEmpty(),
                                        seed = 0L,
                                    ),
                                    SAMPLE_PREVIEW_VARS,
                                )
                                val dir = tts.cacheDir() ?: return@runCatching
                                val outPath = "$dir/dj_preview.wav"
                                val ok = tts.synthesizeToFile(sample, voice = null, outPath = outPath)
                                if (!ok) return@runCatching
                                audioEngine.setVolume(settings.getDjDuckFraction())
                                clipPlayer.playFile(outPath)
                            }
                        } finally {
                            // Cancellation-proof: restore must run even if the
                            // screen is disposed mid-clip. runCatching does NOT
                            // catch CancellationException, so without this the
                            // ducked volume would be stranded.
                            withContext(NonCancellable) {
                                audioEngine.setVolume(prior)
                                previewing = false
                            }
                        }
                    }
                },
            )

            Spacer(Modifier.height(24.dp))

            SettingsSectionHeader("Templates")
            Text(
                "Each line is one phrasing the DJ may use for that slot. Tokens in " +
                    "curly braces are filled in live; a line whose token has no value " +
                    "(e.g. {weather} with no forecast) is skipped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))

            SLOT_ORDER.forEach { (slot, label) ->
                TemplateSlotCard(
                    label = label,
                    enabled = enabled,
                    variants = templates[slot] ?: DEFAULT_DJ_TEMPLATES[slot].orEmpty(),
                    onVariantsChange = { next ->
                        persistTemplates(templates.toMutableMap().apply { put(slot, next) })
                    },
                )
                Spacer(Modifier.height(8.dp))
            }

            ResetTemplatesRow(
                enabled = enabled,
                onReset = {
                    // Empty blob ↔ "use defaults" (SettingsStore.getDjTemplates
                    // contract). Mirror that in-memory so the cards re-render
                    // the built-in set immediately.
                    settings.setDjTemplates("")
                    templates = DEFAULT_DJ_TEMPLATES
                },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Fixed sample bag for the voice-preview sentence — no live data needed. */
private val SAMPLE_PREVIEW_VARS = DjTemplateVars(
    station = "RadioShed",
    track = "Midnight Drive",
    artist = "The Wavelengths",
    nextTrack = "City Lights",
    time = "3:45 PM",
    weather = "clear skies",
)

/** Canonical slot order + human labels for the template editor cards. */
private val SLOT_ORDER = listOf(
    DjSlots.STATION_ID to "Station ID",
    DjSlots.UP_NEXT to "Up next",
    DjSlots.RECAP to "Recap",
    DjSlots.TIME to "Time of day",
)

/** Placeholder tokens the edit dialog can insert at the cursor. */
private val TEMPLATE_TOKENS = listOf(
    "{station}",
    "{track}",
    "{artist}",
    "{next_track}",
    "{time}",
    "{weather}",
)

@Composable
private fun FrequencySection(
    enabledControls: Boolean,
    frequency: String,
    onFrequencyChange: (String) -> Unit,
    frequencyN: Int,
    onFrequencyNChange: (Int) -> Unit,
    frequencyMinutes: Int,
    onFrequencyMinutesChange: (Int) -> Unit,
) {
    val options = listOf(
        "off" to "Off",
        "every" to "Every track",
        "every_n" to "Every N tracks",
        "minutes" to "Every N minutes",
        "station_change" to "On station change",
    )
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "How often the DJ speaks",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (enabledControls) 1f else 0.4f,
                ),
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                options.forEach { (value, label) ->
                    val active = value == frequency
                    FilterChip(
                        selected = active,
                        enabled = enabledControls,
                        onClick = { if (!active) onFrequencyChange(value) },
                        label = { Text(label) },
                    )
                }
            }
            if (frequency == "every_n") {
                Spacer(Modifier.height(12.dp))
                StepperRow(
                    label = "Speak every",
                    valueLabel = "$frequencyN tracks",
                    enabledControls = enabledControls,
                    onDecrement = { onFrequencyNChange((frequencyN - 1).coerceIn(2, 20)) },
                    onIncrement = { onFrequencyNChange((frequencyN + 1).coerceIn(2, 20)) },
                    canDecrement = frequencyN > 2,
                    canIncrement = frequencyN < 20,
                )
            }
            if (frequency == "minutes") {
                Spacer(Modifier.height(12.dp))
                StepperRow(
                    label = "Speak every",
                    valueLabel = "$frequencyMinutes min",
                    enabledControls = enabledControls,
                    onDecrement = {
                        onFrequencyMinutesChange((frequencyMinutes - 5).coerceIn(5, 120))
                    },
                    onIncrement = {
                        onFrequencyMinutesChange((frequencyMinutes + 5).coerceIn(5, 120))
                    },
                    canDecrement = frequencyMinutes > 5,
                    canIncrement = frequencyMinutes < 120,
                )
            }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    valueLabel: String,
    enabledControls: Boolean,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    canDecrement: Boolean,
    canIncrement: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface.copy(
                alpha = if (enabledControls) 1f else 0.4f,
            ),
        )
        OutlinedButton(
            onClick = onDecrement,
            enabled = enabledControls && canDecrement,
        ) { Text("−") }
        Spacer(Modifier.size(8.dp))
        Text(
            valueLabel,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(
                alpha = if (enabledControls) 1f else 0.4f,
            ),
        )
        Spacer(Modifier.size(8.dp))
        OutlinedButton(
            onClick = onIncrement,
            enabled = enabledControls && canIncrement,
        ) { Text("+") }
    }
}

@Composable
private fun QuietHoursSection(
    enabledControls: Boolean,
    startMin: Int,
    onStartChange: (Int) -> Unit,
    endMin: Int,
    onEndChange: (Int) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "Quiet hours",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (enabledControls) 1f else 0.4f,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "The DJ stays silent inside this window. If the start is later than " +
                    "the end, the window wraps across midnight (e.g. 22:00 → 07:00).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(10.dp))
            MinutesOfDaySlider(
                label = "Start",
                minutes = startMin,
                enabledControls = enabledControls,
                onChange = onStartChange,
            )
            Spacer(Modifier.height(8.dp))
            MinutesOfDaySlider(
                label = "End",
                minutes = endMin,
                enabledControls = enabledControls,
                onChange = onEndChange,
            )
        }
    }
}

@Composable
private fun MinutesOfDaySlider(
    label: String,
    minutes: Int,
    enabledControls: Boolean,
    onChange: (Int) -> Unit,
) {
    // Lift-up-state so the readout tracks the drag but the prefs write only
    // fires on release — mirrors the AppSettings slider idiom.
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

@Composable
private fun DuckSlider(
    enabledControls: Boolean,
    fraction: Float,
    onChange: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    // Initial value seeds the drag buffer only; display reads [fraction] when not dragging.
    var pending by remember { mutableStateOf(fraction) }
    val display = if (dragging) pending else fraction
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Music duck level",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (enabledControls) 1f else 0.4f,
                        ),
                    )
                    Text(
                        "How far the music drops while the DJ speaks. Lower means " +
                            "quieter music under the voice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Spacer(Modifier.size(12.dp))
                Text(
                    "${(display * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (enabledControls) 1f else 0.4f,
                    ),
                )
            }
            Slider(
                value = display,
                enabled = enabledControls,
                onValueChange = {
                    dragging = true
                    pending = it.coerceIn(0f, 0.6f)
                },
                onValueChangeFinished = {
                    onChange(pending)
                    dragging = false
                },
                valueRange = 0f..0.6f,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PreviewVoiceRow(
    enabledControls: Boolean,
    ttsAvailable: Boolean,
    previewing: Boolean,
    onPreview: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Preview voice",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (ttsAvailable) {
                        "Speaks a sample station ID, ducking the music just like a " +
                            "real announcement."
                    } else {
                        "No text-to-speech voice is available on this device."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Spacer(Modifier.size(12.dp))
            Button(onClick = onPreview, enabled = enabledControls) {
                Text(if (previewing) "Playing…" else "Preview")
            }
        }
    }
}

@Composable
private fun TemplateSlotCard(
    label: String,
    enabled: Boolean,
    variants: List<String>,
    onVariantsChange: (List<String>) -> Unit,
) {
    // index being edited, -1 = adding a new variant, null = dialog closed.
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (enabled) 1f else 0.4f,
                ),
            )
            Spacer(Modifier.height(6.dp))
            variants.forEachIndexed { index, variant ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        variant,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (enabled) 0.85f else 0.4f,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { editingIndex = index },
                        enabled = enabled,
                    ) { Text("Edit") }
                    // A slot must keep at least one variant: disable delete on
                    // the last remaining line.
                    IconButton(
                        onClick = {
                            onVariantsChange(
                                variants.toMutableList().apply { removeAt(index) },
                            )
                        },
                        enabled = enabled && variants.size > 1,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete variant",
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = { editingIndex = -1 },
                enabled = enabled,
            ) { Text("+ Add variant") }
        }
    }

    editingIndex?.let { index ->
        val initial = if (index >= 0) variants[index] else ""
        VariantEditDialog(
            slotLabel = label,
            initialText = initial,
            onDismiss = { editingIndex = null },
            onConfirm = { text ->
                val trimmed = text.trim()
                if (trimmed.isNotEmpty()) {
                    val next = variants.toMutableList()
                    if (index >= 0) next[index] = trimmed else next.add(trimmed)
                    onVariantsChange(next)
                }
                editingIndex = null
            },
        )
    }
}

@Composable
private fun VariantEditDialog(
    slotLabel: String,
    initialText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var field by remember {
        mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(field.text) },
                enabled = field.text.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text(slotLabel) },
        text = {
            Column {
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Insert a token",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TEMPLATE_TOKENS.forEach { token ->
                        FilterChip(
                            selected = false,
                            onClick = { field = field.insertAtCursor(token) },
                            label = { Text(token) },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun ResetTemplatesRow(enabled: Boolean, onReset: () -> Unit) {
    var confirmOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = enabled) { confirmOpen = true },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "Reset templates to defaults",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error.copy(
                    alpha = if (enabled) 1f else 0.4f,
                ),
                fontWeight = FontWeight.Medium,
            )
            Text(
                "Discards every custom phrasing above and restores the built-in set.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(
                    alpha = if (enabled) 0.6f else 0.3f,
                ),
            )
        }
    }
    if (confirmOpen) {
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            title = { Text("Reset DJ templates?") },
            text = {
                Text(
                    "Restores the built-in phrasings for every slot. Your custom " +
                        "lines are discarded. Other DJ settings are untouched.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmOpen = false
                    onReset()
                }) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmOpen = false }) { Text("Cancel") }
            },
        )
    }
}

/** Inserts [token] at the current selection, replacing any selected range. */
private fun TextFieldValue.insertAtCursor(token: String): TextFieldValue {
    val start = selection.start
    val end = selection.end
    val newText = text.replaceRange(start, end, token)
    val caret = start + token.length
    return TextFieldValue(text = newText, selection = TextRange(caret))
}

/** Formats a minute-of-day (0..1439) as a 24-hour h:mm label. */
private fun formatHhMm(minuteOfDay: Int): String {
    val hours = (minuteOfDay / 60).coerceIn(0, 23)
    val minutes = (minuteOfDay % 60).coerceIn(0, 59)
    val mm = if (minutes < 10) "0$minutes" else "$minutes"
    return "$hours:$mm"
}
