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
import com.visorcraft.radioshed.ui.theme.supportsMaterialYou
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
    currentMaterialYou: Boolean = false,
    onMaterialYouChange: (Boolean) -> Unit = {},
    currentFontScale: Float = 1f,
    onFontScaleChange: (Float) -> Unit = {},
    onOpenEqualizer: () -> Unit = {},
    onOpenPlaylists: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenCrashLog: () -> Unit = {},
    settings: SettingsStore = koinInject(),
    artworkSync: MissingArtworkSync = koinInject(),
    lyricsCache: com.visorcraft.radioshed.data.LyricsCacheRepository = koinInject(),
) {
    // Hold each toggle as compose state. The previous comment said
    // "Re-read settings on every recomposition" via `remember(settings)`,
    // but settings is a Koin singleton — its reference never changes,
    // so the state was actually frozen at first composition.
    // Navigating to Backup, toggling auto-backup there, then back to
    // here would show the OLD value. Now we explicitly re-load on
    // every entry via the LaunchedEffect below.
    var reduceMotion by remember { mutableStateOf(settings.getReduceMotion()) }
    var replayGain by remember { mutableStateOf(settings.getReplayGainEnabled()) }
    var preampDb by remember { mutableStateOf(settings.getReplayGainPreampDb()) }
    var autoResume by remember { mutableStateOf(settings.getAutoResumeLastStation()) }
    var driveMode by remember { mutableStateOf(settings.getDriveMode()) }
    var autoBackup by remember { mutableStateOf(settings.getAutoBackupEnabled()) }
    var autoRescan by remember { mutableStateOf(settings.getAutoRescanOnLaunch()) }
    var endFadeSec by remember { mutableStateOf(settings.getEndFadeSec()) }
    var connectivityPolicy by remember { mutableStateOf(settings.getConnectivityPolicy()) }
    LaunchedEffect(Unit) {
        // Re-read on each entry so values written from sibling
        // screens (Backup, Equalizer, Library Manage, etc.) reflect
        // here without requiring a manual toggle to "wake up".
        reduceMotion = settings.getReduceMotion()
        replayGain = settings.getReplayGainEnabled()
        preampDb = settings.getReplayGainPreampDb()
        autoResume = settings.getAutoResumeLastStation()
        driveMode = settings.getDriveMode()
        autoBackup = settings.getAutoBackupEnabled()
        autoRescan = settings.getAutoRescanOnLaunch()
        endFadeSec = settings.getEndFadeSec()
        connectivityPolicy = settings.getConnectivityPolicy()
    }
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
            if (supportsMaterialYou()) {
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    title = "Material You",
                    subtitle = "Tint the app with colours derived from your wallpaper " +
                        "(Android 12+). Off uses the brand amber palette. AMOLED " +
                        "still wins for surface/background when paired.",
                    checked = currentMaterialYou,
                    onCheckedChange = { v ->
                        onMaterialYouChange(v)
                        settings.setMaterialYouEnabled(v)
                    },
                )
            }

            Spacer(Modifier.height(16.dp))

            // ---- Accessibility ----
            SectionHeader("Accessibility")
            FontScaleRow(
                value = currentFontScale,
                onChange = { v ->
                    onFontScaleChange(v)
                    settings.setFontScale(v)
                },
            )
            Spacer(Modifier.height(8.dp))
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
            EndFadeSlider(
                seconds = endFadeSec,
                onChange = { v ->
                    endFadeSec = v
                    settings.setEndFadeSec(v)
                },
            )
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
            Spacer(Modifier.height(8.dp))
            NavRow(
                title = "Crash log",
                subtitle = "Recent crashes / ANRs reported by Android. Local-only — " +
                    "share via the system share sheet if you want to send a report.",
                onClick = onOpenCrashLog,
            )

            Spacer(Modifier.height(24.dp))

            // ---- Online & Sync ----
            // Single global gate for online lookups (Cover Art Archive,
            // LRClib). Per-service toggles are still deferred per
            // SPEC §7.5 — the four-way enum here covers the broad
            // user intents in the meantime.
            SectionHeader("Online")
            ConnectivityPolicyRow(
                current = connectivityPolicy,
                onPick = { p ->
                    connectivityPolicy = p
                    settings.setConnectivityPolicy(p)
                },
            )

            Spacer(Modifier.height(24.dp))

            // ---- Library maintenance ----
            SectionHeader("Library")
            ToggleRow(
                title = "Rescan folders on launch",
                subtitle = "Walks every registered folder every time RadioShed " +
                    "starts cold, picking up files added externally (USB " +
                    "drag-and-drop, ripping a CD, etc). Scanner is idempotent " +
                    "so this is safe to leave on, but it adds a few seconds " +
                    "to the first-frame on libraries that change often.",
                checked = autoRescan,
                onCheckedChange = { v ->
                    autoRescan = v
                    settings.setAutoRescanOnLaunch(v)
                },
            )
            Spacer(Modifier.height(8.dp))
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

            Spacer(Modifier.height(8.dp))
            // Clear lyrics cache. Used when LRClib improves their
            // database — the user gets a re-fetch on next track plays.
            // Keeps user-supplied artist/title overrides intact since
            // those represent manual corrections.
            var lyricsCleared by remember { mutableStateOf(false) }
            ClearLyricsCacheRow(
                cleared = lyricsCleared,
                onClick = {
                    scope.launch {
                        lyricsCache.deleteAll()
                        lyricsCleared = true
                    }
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
            Spacer(Modifier.height(12.dp))
            LibrarySummaryCard()

            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * "Stations summary" card from §7.5 About — track / album / artist
 * counts plus total library runtime. Loaded once per composition;
 * updates when the user navigates back to Settings after a rescan.
 */
@Composable
private fun LibrarySummaryCard(
    libraryRepo: com.visorcraft.radioshed.data.LibraryRepository = koinInject(),
    stationRepo: com.visorcraft.radioshed.data.StationRepository = koinInject(),
) {
    var trackCount by remember { mutableStateOf<Long?>(null) }
    var albumCount by remember { mutableStateOf<Long?>(null) }
    var artistCount by remember { mutableStateOf<Long?>(null) }
    var stationCount by remember { mutableStateOf<Long?>(null) }
    var totalRuntimeMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        trackCount = libraryRepo.countTracks()
        albumCount = libraryRepo.countAlbums()
        artistCount = libraryRepo.countArtists()
        stationCount = stationRepo.list().size.toLong()
        totalRuntimeMs = libraryRepo.totalLibraryDurationMs()
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "Library summary",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            SummaryRow("Tracks", trackCount?.toString() ?: "…")
            SummaryRow("Albums", albumCount?.toString() ?: "…")
            SummaryRow("Artists", artistCount?.toString() ?: "…")
            SummaryRow("Stations", stationCount?.toString() ?: "…")
            SummaryRow(
                "Total runtime",
                totalRuntimeMs?.let { formatLibraryRuntime(it) } ?: "…",
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Library-scope runtime formatter. Always switches to days at 24h+
 * because a real library can be 100s of hours and a `1234h 56m`
 * format gets unwieldy. "47 min" / "12h 34m" / "8d 14h".
 */
private fun formatLibraryRuntime(ms: Long): String {
    val totalMin = ms / 60_000L
    if (totalMin < 60L) return "$totalMin min"
    val totalHr = totalMin / 60L
    if (totalHr < 24L) {
        val mins = totalMin % 60L
        return if (mins == 0L) "${totalHr}h" else "${totalHr}h ${mins}m"
    }
    val days = totalHr / 24L
    val hrs = totalHr % 24L
    return if (hrs == 0L) "${days}d" else "${days}d ${hrs}h"
}

@Composable
private fun ClearLyricsCacheRow(cleared: Boolean, onClick: () -> Unit) {
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
                    "Clear lyrics cache",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (cleared) "Cache cleared. Next plays will re-fetch."
                           else "Re-fetches lyrics for every track on next play.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
            TextButton(onClick = onClick, enabled = !cleared) {
                Text(if (cleared) "Done" else "Clear")
            }
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

/**
 * Slider for the end-of-track fade duration. 0 disables.
 * Honest naming — this is a track-end fade-out, not real
 * crossfade (no overlap into the next track).
 */
@Composable
private fun EndFadeSlider(seconds: Int, onChange: (Int) -> Unit) {
    // Same lift-up-state pattern as PreampSlider so the readout
    // tracks the drag without burning a SharedPreferences write
    // every frame. Discrete steps mean fewer onValueChange events
    // per drag than a continuous slider, but the cost is the same.
    var dragging by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(seconds) }
    val display = if (dragging) pending else seconds
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "End-of-track fade",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (display == 0) "Off" else "${display}s",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
        Text(
            text = "Ramps the volume down over the last few seconds of " +
                "each track to soften the cut. Doesn't overlap into the " +
                "next track — true crossfade needs deeper plumbing and " +
                "is on the roadmap.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
        androidx.compose.material3.Slider(
            value = display.toFloat(),
            onValueChange = {
                dragging = true
                pending = it.toInt()
            },
            onValueChangeFinished = {
                onChange(pending)
                dragging = false
            },
            valueRange = 0f..10f,
            steps = 9,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PreampSlider(valueDb: Float, onChange: (Float) -> Unit) {
    // Local pending state so the dB readout AND slider track the
    // drag in real time, but the SharedPreferences write (which
    // onChange routes through) only fires on onValueChangeFinished.
    // Without this each drag frame persists, thrashing prefs 30-60×
    // per gesture.
    var dragging by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(valueDb) }
    val display = if (dragging) pending else valueDb
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
                    "${if (display >= 0f) "+" else ""}${"%.1f".format(display)} dB",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            androidx.compose.material3.Slider(
                value = display,
                onValueChange = {
                    dragging = true
                    pending = it
                },
                onValueChangeFinished = {
                    onChange(pending)
                    dragging = false
                },
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

/**
 * Six discrete typography stops (XS / S / M / L / XL / XXL) mapped to
 * 0.85 / 0.92 / 1.0 / 1.10 / 1.20 / 1.30. Discrete stops are friendlier
 * than a continuous slider — users have a shared vocabulary, snapshots
 * are reproducible, and the slider's drag-to-write debounce isn't
 * needed because each tap is one discrete write.
 */
private val FONT_SCALE_STOPS = listOf(0.85f, 0.92f, 1.0f, 1.10f, 1.20f, 1.30f)
private val FONT_SCALE_LABELS = listOf("XS", "S", "M", "L", "XL", "XXL")

/**
 * Picker for the four `ConnectivityPolicy` modes. Renders as a card
 * with one FilterChip per mode plus a one-line subtitle explaining
 * what's currently gated. The four modes — Always / Wi-Fi only /
 * Never / Manual — match the SPEC §7.5 vocabulary; Manual is the
 * current default and asks the user before each online fetch.
 */
@Composable
private fun ConnectivityPolicyRow(
    current: com.visorcraft.radioshed.domain.ConnectivityPolicy,
    onPick: (com.visorcraft.radioshed.domain.ConnectivityPolicy) -> Unit,
) {
    val options = listOf(
        com.visorcraft.radioshed.domain.ConnectivityPolicy.Always to "Always",
        com.visorcraft.radioshed.domain.ConnectivityPolicy.WiFiOnly to "Wi-Fi only",
        com.visorcraft.radioshed.domain.ConnectivityPolicy.Manual to "Ask each time",
        com.visorcraft.radioshed.domain.ConnectivityPolicy.Never to "Never",
    )
    val explanation = when (current) {
        com.visorcraft.radioshed.domain.ConnectivityPolicy.Always ->
            "Cover Art Archive + LRClib lyrics fetch on demand whenever the app needs them."
        com.visorcraft.radioshed.domain.ConnectivityPolicy.WiFiOnly ->
            "Online fetches only happen on Wi-Fi — keeps mobile data quiet."
        com.visorcraft.radioshed.domain.ConnectivityPolicy.Manual ->
            "The app asks before every online fetch. Slower but in your hands."
        com.visorcraft.radioshed.domain.ConnectivityPolicy.Never ->
            "All online lookups disabled — only embedded artwork + sidecar lyrics."
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "Online lookups",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(10.dp))
            // Two rows of two chips each — one row would clip on
            // narrow screens since "Ask each time" is wide. Wrap
            // sensibly via a flow layout instead of a single Row.
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                options.forEach { (policy, label) ->
                    val active = policy == current
                    androidx.compose.material3.FilterChip(
                        selected = active,
                        onClick = { if (!active) onPick(policy) },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FontScaleRow(value: Float, onChange: (Float) -> Unit) {
    val activeIdx = remember(value) {
        // Snap whatever's persisted to the nearest stop so the chip
        // row reads cleanly even if a future slider write produced
        // an intermediate value.
        FONT_SCALE_STOPS.indices.minByOrNull {
            kotlin.math.abs(FONT_SCALE_STOPS[it] - value)
        } ?: 2
    }
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "Text size",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Scale every screen's typography. Affects buttons, titles, body text, " +
                    "and lists. Lyrics has its own A−/A+ on top of this.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FONT_SCALE_STOPS.forEachIndexed { i, stop ->
                    val active = i == activeIdx
                    androidx.compose.material3.FilterChip(
                        selected = active,
                        onClick = { if (!active) onChange(stop) },
                        label = { Text(FONT_SCALE_LABELS[i]) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
