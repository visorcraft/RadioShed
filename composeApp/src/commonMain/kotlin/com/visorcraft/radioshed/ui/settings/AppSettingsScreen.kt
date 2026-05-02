package com.visorcraft.radioshed.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.BuildInfo
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.source.MissingArtworkSync
import com.visorcraft.radioshed.ui.theme.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Global app settings: theme, reduce-motion, version info.
 *
 * Distinct from `LibrarySettingsScreen` which manages folders + scans.
 * This screen owns the cross-cutting prefs that don't belong anywhere
 * else — colors / motion / accessibility / app info.
 *
 * Theme changes are LIVE: [onThemeModeChange] is invoked the moment the
 * user picks a row, and the parent (App.kt) propagates the new value to
 * `RadioShedTheme` so the whole app re-themes without a relaunch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    onBack: () -> Unit,
    currentThemeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onOpenEqualizer: () -> Unit = {},
    onOpenPlaylists: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    settings: SettingsStore = koinInject(),
    artworkSync: MissingArtworkSync = koinInject(),
) {
    // Re-read settings on every recomposition into local state so they
    // re-render correctly when this screen is reopened (the user might
    // have changed a value in another tab via a quick-tile or backup-
    // restore). `remember(Unit)` would freeze them at first composition.
    var reduceMotion by remember(settings) { mutableStateOf(settings.getReduceMotion()) }
    var replayGain by remember(settings) { mutableStateOf(settings.getReplayGainEnabled()) }
    var preampDb by remember(settings) { mutableStateOf(settings.getReplayGainPreampDb()) }
    var autoResume by remember(settings) { mutableStateOf(settings.getAutoResumeLastStation()) }
    var driveMode by remember(settings) { mutableStateOf(settings.getDriveMode()) }
    var autoBackup by remember(settings) { mutableStateOf(settings.getAutoBackupEnabled()) }
    var artworkState by remember { mutableStateOf<ArtworkSyncState>(ArtworkSyncState.Idle) }
    var artworkJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            // ---- Appearance ----
            SectionHeader("Appearance")
            ThemeChoiceList(
                current = currentThemeMode,
                onPick = { mode ->
                    onThemeModeChange(mode)
                    settings.setThemeMode(mode)
                },
            )

            Spacer(Modifier.height(16.dp))

            // ---- Accessibility ----
            SectionHeader("Accessibility")
            ToggleRow(
                title = "Reduce motion",
                subtitle = "Minimize animations across the app. Useful for motion-sensitivity, also " +
                    "helps perceived performance on lower-end hardware.",
                checked = reduceMotion,
                onCheckedChange = { v ->
                    reduceMotion = v
                    settings.setReduceMotion(v)
                },
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "Drive Mode",
                subtitle = "Larger transport buttons on Now Playing — easier to hit at a glance " +
                    "while driving. Takes effect the next time you open Now Playing.",
                checked = driveMode,
                onCheckedChange = { v ->
                    driveMode = v
                    settings.setDriveMode(v)
                },
            )

            Spacer(Modifier.height(24.dp))

            // ---- Audio ----
            SectionHeader("Audio")
            ToggleRow(
                title = "Resume last station on launch",
                subtitle = "Start playing whatever station was last active when the " +
                    "app reopens. Off by default — silent launches are usually what " +
                    "you want.",
                checked = autoResume,
                onCheckedChange = { v ->
                    autoResume = v
                    settings.setAutoResumeLastStation(v)
                },
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "ReplayGain",
                subtitle = "Normalize per-track loudness using embedded ReplayGain " +
                    "tags. Loud tracks are attenuated toward a common reference; " +
                    "tracks without tags play unchanged.",
                checked = replayGain,
                onCheckedChange = { v ->
                    replayGain = v
                    settings.setReplayGainEnabled(v)
                },
            )
            if (replayGain) {
                Spacer(Modifier.height(8.dp))
                PreampSlider(
                    valueDb = preampDb,
                    onChange = { v ->
                        preampDb = v
                        settings.setReplayGainPreampDb(v)
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            NavRow(
                title = "Equalizer",
                subtitle = "5-band EQ with Bass / Vocal / Treble presets",
                onClick = onOpenEqualizer,
            )
            Spacer(Modifier.height(8.dp))
            NavRow(
                title = "Playlists",
                subtitle = "Create, edit, and play your custom collections",
                onClick = onOpenPlaylists,
            )
            Spacer(Modifier.height(8.dp))
            NavRow(
                title = "Backup & restore",
                subtitle = "Export stations, playlists, and ratings to a JSON file",
                onClick = onOpenBackup,
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "Auto-backup weekly",
                subtitle = "Silently writes a fresh backup once per week so you " +
                    "don't have to remember. Files live alongside manual backups " +
                    "and obey the same 10-file retention cap.",
                checked = autoBackup,
                onCheckedChange = { v ->
                    autoBackup = v
                    settings.setAutoBackupEnabled(v)
                },
            )
            Spacer(Modifier.height(8.dp))
            NavRow(
                title = "Listening stats",
                subtitle = "Total time, play count, top tracks",
                onClick = onOpenStats,
            )
            Spacer(Modifier.height(8.dp))
            NavRow(
                title = "Listening history",
                subtitle = "Chronological log of every track you've played",
                onClick = onOpenHistory,
            )

            Spacer(Modifier.height(24.dp))

            // ---- Library maintenance ----
            SectionHeader("Library")
            ArtworkSyncCard(
                state = artworkState,
                onStart = {
                    artworkJob = scope.launch {
                        artworkSync.sweep().collect { p -> artworkState = artworkState.advance(p) }
                    }
                },
                onCancel = {
                    artworkJob?.cancel()
                    artworkJob = null
                    artworkState = ArtworkSyncState.Idle
                },
            )

            Spacer(Modifier.height(24.dp))

            // ---- About ----
            SectionHeader("About")
            Text(
                "RadioShed v${BuildInfo.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            )
            Text(
                "Your music turned into stations. No subscriptions, no telemetry, internet optional.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
}

/**
 * Visual radio group for theme choices. Each option shows an icon + label
 * + brief description; the currently-selected one gets the primary border.
 */
@Composable
private fun ThemeChoiceList(
    current: ThemeMode,
    onPick: (ThemeMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeOption.entries.forEach { option ->
            ThemeChoiceRow(
                option = option,
                selected = option.mode == current,
                onClick = { onPick(option.mode) },
            )
        }
    }
}

@Composable
private fun ThemeChoiceRow(
    option: ThemeOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
            ),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surface,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = option.icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary
                           else MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    option.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    option.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun PreampSlider(valueDb: Float, onChange: (Float) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "ReplayGain pre-amp",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Pre-amp boost added to per-track gain (clamped to ≤0 dB " +
                            "output, so loud tracks still attenuate). Untagged tracks " +
                            "ignore this.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Spacer(Modifier.size(12.dp))
                Text(
                    "${if (valueDb >= 0f) "+" else ""}${"%.1f".format(valueDb)} dB",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            androidx.compose.material3.Slider(
                value = valueDb,
                onValueChange = onChange,
                valueRange = -6f..12f,
                steps = 0,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun NavRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
            Text(
                "›",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
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
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/**
 * Lightweight UI-side projection of the [MissingArtworkSync] flow.
 * Kept here (not in source/) because rendering is the only caller —
 * MissingArtworkSync emits a stream of granular events and the UI just
 * needs "what should I show right now" + final tally.
 */
private sealed interface ArtworkSyncState {
    object Idle : ArtworkSyncState
    data class Running(val index: Int, val total: Int, val found: Int) : ArtworkSyncState
    data class Done(val found: Int, val missing: Int, val failed: Int) : ArtworkSyncState
}

private fun ArtworkSyncState.advance(p: MissingArtworkSync.Progress): ArtworkSyncState = when (p) {
    is MissingArtworkSync.Progress.Started -> ArtworkSyncState.Running(0, p.total, 0)
    is MissingArtworkSync.Progress.Fetching -> {
        val cur = (this as? ArtworkSyncState.Running) ?: ArtworkSyncState.Running(0, p.total, 0)
        cur.copy(index = p.index, total = p.total)
    }
    is MissingArtworkSync.Progress.Found -> {
        val cur = (this as? ArtworkSyncState.Running) ?: return this
        cur.copy(found = cur.found + 1)
    }
    is MissingArtworkSync.Progress.Missing,
    is MissingArtworkSync.Progress.Failed -> this
    is MissingArtworkSync.Progress.Completed ->
        ArtworkSyncState.Done(found = p.found, missing = p.missing, failed = p.failed)
}

@Composable
private fun ArtworkSyncCard(
    state: ArtworkSyncState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Fetch missing album art",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Downloads cover art from the Cover Art Archive for albums " +
                            "with a MusicBrainz ID but no embedded artwork. Uses the network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            when (state) {
                is ArtworkSyncState.Idle -> {
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                        Text("Start sweep")
                    }
                }
                is ArtworkSyncState.Running -> {
                    val pct = if (state.total > 0) state.index.toFloat() / state.total else 0f
                    LinearProgressIndicator(
                        progress = { pct },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${state.index + 1} of ${state.total} — found ${state.found}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                is ArtworkSyncState.Done -> {
                    Text(
                        "Found ${state.found}, missing ${state.missing}" +
                            (if (state.failed > 0) ", failed ${state.failed}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                        Text("Sweep again")
                    }
                }
            }
        }
    }
}

private enum class ThemeOption(
    val mode: ThemeMode,
    val label: String,
    val description: String,
    val icon: ImageVector,
) {
    System(
        mode = ThemeMode.System,
        label = "System default",
        description = "Match the system's light/dark setting",
        icon = Icons.Filled.Brightness6,
    ),
    Light(
        mode = ThemeMode.Light,
        label = "Light",
        description = "Bright background, dark text — best for daylight use",
        icon = Icons.Filled.Brightness7,
    ),
    Dark(
        mode = ThemeMode.Dark,
        label = "Dark",
        description = "Material 3 dark surfaces — easier on the eyes at night",
        icon = Icons.Filled.Brightness4,
    ),
    Amoled(
        mode = ThemeMode.Amoled,
        label = "AMOLED black",
        description = "True-black background — saves battery on OLED displays",
        icon = Icons.Filled.NightsStay,
    ),
}
