package com.visorcraft.radioshed.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.runtime.collectAsState
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
    onOpenSyncthingWizard: () -> Unit = {},
    onOpenDbBrowser: () -> Unit = {},
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
    var backupRetention by remember { mutableStateOf(settings.getBackupRetention()) }
    var sleepStopOnExpire by remember { mutableStateOf(settings.getSleepStopOnExpire()) }
    var keepScreenOn by remember { mutableStateOf(settings.getKeepScreenOn()) }
    var autoFetchLyrics by remember { mutableStateOf(settings.getAutoFetchLyrics()) }
    var hapticsEnabled by remember { mutableStateOf(settings.getHapticsEnabled()) }
    var resetConfirmOpen by remember { mutableStateOf(false) }
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
        backupRetention = settings.getBackupRetention()
        sleepStopOnExpire = settings.getSleepStopOnExpire()
        keepScreenOn = settings.getKeepScreenOn()
        autoFetchLyrics = settings.getAutoFetchLyrics()
        hapticsEnabled = settings.getHapticsEnabled()
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
        // AppSettings is a deep, vertically-scrolling Column (not a
        // LazyColumn — most of its rows are unique composables). Hoist
        // the scrollState so a back-to-top FAB can drive it once the
        // user has paged past the visible window.
        val scrollState = rememberScrollState()
        // Threshold of ~1.5 viewports — `viewportSize` and
        // `maxValue` aren't available on `ScrollState` directly, so
        // we approximate with a fixed pixel cutoff. 1500 px is roughly
        // halfway down on a Galaxy Fold 7's main screen.
        val showScrollTop by remember {
            androidx.compose.runtime.derivedStateOf { scrollState.value > 1500 }
        }
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
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
            Spacer(Modifier.height(8.dp))
            // Now Playing layout selector. Reads via the mirror
            // StateFlow so the chip selection stays in sync with
            // any external write (e.g. backup-restore). Writing
            // through `setNowPlayingLayout` updates both the
            // underlying Settings and the flow, so NowPlayingScreen
            // (also subscribed) sees the change live.
            val npLayout by settings.nowPlayingLayoutFlow.collectAsState()
            NowPlayingLayoutRow(
                current = npLayout,
                onPick = { v -> settings.setNowPlayingLayout(v) },
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "Haptic feedback",
                subtitle = "Subtle vibration on long-press / mode-flip gestures. Off mutes " +
                    "every haptic across the app — useful if your phone's vibration motor " +
                    "is loud or you find the gestures distracting.",
                checked = hapticsEnabled,
                onCheckedChange = { v ->
                    hapticsEnabled = v
                    settings.setHapticsEnabled(v)
                },
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "Keep screen on while playing",
                subtitle = "Holds the display on while a track is actually playing. " +
                    "Pause / idle releases the lock so the screen times out normally " +
                    "between tracks. Off by default to save battery.",
                checked = keepScreenOn,
                onCheckedChange = { v ->
                    keepScreenOn = v
                    settings.setKeepScreenOn(v)
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
                    "and obey the retention cap below.",
                checked = autoBackup,
                onCheckedChange = { v ->
                    autoBackup = v
                    settings.setAutoBackupEnabled(v)
                },
            )
            Spacer(Modifier.height(8.dp))
            BackupRetentionSlider(
                value = backupRetention,
                onChange = { v ->
                    backupRetention = v
                    settings.setBackupRetention(v)
                },
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "Auto-fetch lyrics over network",
                subtitle = "Allows the lyrics view to query LRClib when local " +
                    "sidecar / embedded lyrics aren't found. Off keeps the app " +
                    "fully local for new lookups; existing cached lyrics stay.",
                checked = autoFetchLyrics,
                onCheckedChange = { v ->
                    autoFetchLyrics = v
                    settings.setAutoFetchLyrics(v)
                },
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "Sleep timer stops playback",
                subtitle = "When the timer expires, fully stop playback (clears " +
                    "the lock-screen tile) instead of pausing. Off keeps the " +
                    "queue ready to resume with one tap.",
                checked = sleepStopOnExpire,
                onCheckedChange = { v ->
                    sleepStopOnExpire = v
                    settings.setSleepStopOnExpire(v)
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
            Spacer(Modifier.height(8.dp))
            // Syncthing setup wizard. The wizard's a one-time flow but
            // we leave the entry point here so users can re-run it for
            // additional folders or after re-installing Syncthing.
            NavRow(
                title = "Set up Syncthing sync",
                subtitle = "Step-by-step wizard to mirror a music folder " +
                    "from your PC to this phone over Wi-Fi.",
                onClick = onOpenSyncthingWizard,
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
            // One-tap "create stations from your tag-genre histogram"
            // — closes SPEC §5 "Genre auto-stations" without forcing
            // users to create them by hand. Idempotent: stations
            // named `Genre: X` are skipped on re-run, so the button
            // can be tapped repeatedly after the library grows
            // without producing duplicates.
            GenerateGenreStationsRow()
            Spacer(Modifier.height(8.dp))
            // Same idempotent pattern for decade auto-stations.
            // Album.year drives the bucket; albums with NULL year
            // sit out (avoids padding a "60s" station with
            // year-less rips that may or may not belong).
            GenerateDecadeStationsRow()
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
            Spacer(Modifier.height(8.dp))
            // Three-step artwork cache reset: wipe the artwork_cache
            // table, delete every artwork blob on disk, then null out
            // every album.artwork_uri pointer so the UI stops trying
            // to load deleted files. The next CoverArt sweep / scan
            // refetches.
            val artworkCache: com.visorcraft.radioshed.data.ArtworkCacheRepository = koinInject()
            val artworkPersister: com.visorcraft.radioshed.source.ArtworkPersister = koinInject()
            val libraryRepoForCache: com.visorcraft.radioshed.data.LibraryRepository = koinInject()
            var artworkClearStatus by remember { mutableStateOf<String?>(null) }
            ClearArtworkCacheRow(
                status = artworkClearStatus,
                onClick = {
                    scope.launch {
                        artworkClearStatus = "Clearing artwork…"
                        val removed = artworkPersister.clearAll()
                        artworkCache.deleteAll()
                        libraryRepoForCache.clearAllAlbumArtwork()
                        artworkClearStatus =
                            "Cleared $removed artwork file" +
                                if (removed == 1) "." else "s."
                    }
                },
            )
            // DJ TTS cache. The blob paths each row points at live in
            // the app's writable area; we read the row list first so
            // we can attempt to delete the on-disk blobs, then wipe
            // the table. Best-effort — runCatching swallows individual
            // missing-file failures so a partially purged cache
            // (e.g. from a manual file-manager pass) still completes.
            val djCache: com.visorcraft.radioshed.data.DjCacheRepository = koinInject()
            var djClearStatus by remember { mutableStateOf<String?>(null) }
            ClearDjCacheRow(
                status = djClearStatus,
                onClick = {
                    scope.launch {
                        djClearStatus = "Clearing DJ cache…"
                        val rows = djCache.listAll()
                        var removed = 0
                        rows.forEach { row ->
                            runCatching {
                                val ok = com.visorcraft.radioshed.source
                                    .deleteFileIfExists(row.blobPath)
                                if (ok) removed += 1
                            }
                        }
                        djCache.deleteAll()
                        djClearStatus = "Cleared $removed DJ clip" +
                            if (removed == 1) "." else "s."
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            // One-shot for users who don't care which cache is which —
            // wipes lyrics + artwork + DJ in one tap and reports an
            // aggregate count. Useful as part of the "free up storage"
            // motion without making the user hunt through three rows.
            // Sequential rather than parallel because the on-disk
            // deletions trip on each other otherwise (the artwork
            // persister and DJ blob path both walk filesDir).
            var clearAllStatus by remember { mutableStateOf<String?>(null) }
            // Confirm gate. The granular clear rows act on a single
            // cache each (lyrics / artwork / DJ) where the user
            // typically knows what they're nuking; the all-in-one
            // path wipes ~hundreds of artwork files plus regenerable
            // TTS clips and earned a lot more "wait, did I mean to
            // do that?" feedback. Confirm dialog matches the
            // Reset-settings flow's pattern.
            var clearAllConfirm by remember { mutableStateOf(false) }
            ClearAllCachesRow(
                status = clearAllStatus,
                onClick = { clearAllConfirm = true },
            )
            if (clearAllConfirm) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { clearAllConfirm = false },
                    title = { Text("Clear all online caches?") },
                    text = {
                        Text(
                            "Wipes lyrics, downloaded album art, and DJ TTS " +
                                "clips. All three regenerate on demand from " +
                                "their original sources. This is safe — " +
                                "your library, ratings, playlists, and " +
                                "stations stay intact.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            clearAllConfirm = false
                            scope.launch {
                                clearAllStatus = "Clearing all caches…"
                                var artworkRemoved = 0
                                runCatching {
                                    artworkRemoved = artworkPersister.clearAll()
                                    artworkCache.deleteAll()
                                    libraryRepoForCache.clearAllAlbumArtwork()
                                }
                                var djRemoved = 0
                                runCatching {
                                    val rows = djCache.listAll()
                                    rows.forEach { row ->
                                        runCatching {
                                            val ok = com.visorcraft.radioshed.source
                                                .deleteFileIfExists(row.blobPath)
                                            if (ok) djRemoved += 1
                                        }
                                    }
                                    djCache.deleteAll()
                                }
                                runCatching { lyricsCache.deleteAll() }
                                clearAllStatus = "Cleared lyrics, $artworkRemoved " +
                                    (if (artworkRemoved == 1) "artwork file, " else "artwork files, ") +
                                    "and $djRemoved DJ clip" +
                                    (if (djRemoved == 1) "." else "s.")
                            }
                        }) {
                            Text(
                                "Clear all",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { clearAllConfirm = false }) { Text("Cancel") }
                    },
                )
            }

            Spacer(Modifier.height(24.dp))

            // ---- About ----
            SectionHeader("About")
            // The version row doubles as a hidden affordance: long-press
            // to open the read-only DB-stats screen. Lets a developer
            // (or a user filing a bug) inspect row counts without an
            // adb pull, but keeps the surface invisible to casual users.
            Text(
                "RadioShed v${BuildInfo.VERSION_NAME} · built ${BuildInfo.BUILD_DATE}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = onOpenDbBrowser,
                ),
            )
            Text(
                "Your music turned into stations. No subscriptions, no telemetry, internet optional.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            )
            Spacer(Modifier.height(12.dp))
            LibrarySummaryCard()
            Spacer(Modifier.height(12.dp))
            CreditsCard()
            Spacer(Modifier.height(12.dp))
            // Source-URL + copy-build-info pair. Browser opens via the
            // openWebUrl expect/actual, clipboard write goes through
            // rememberCopyToClipboard for parity with the rest of
            // the app's copy actions.
            val aboutCopy = com.visorcraft.radioshed.ui.util.rememberCopyToClipboard()
            AboutLinkRow(
                title = "Source",
                subtitle = "github.com/anthropics/radioshed",
                onClick = {
                    com.visorcraft.radioshed.source.openWebUrl(
                        "https://github.com/anthropics/radioshed",
                    )
                },
            )
            Spacer(Modifier.height(8.dp))
            AboutLinkRow(
                title = "Copy build info",
                subtitle = "Useful for bug reports.",
                onClick = {
                    aboutCopy(
                        "RadioShed v${BuildInfo.VERSION_NAME} " +
                            "(code ${BuildInfo.VERSION_CODE}) " +
                            "built ${BuildInfo.BUILD_DATE}",
                    )
                },
            )
            Spacer(Modifier.height(8.dp))
            // Issue tracker link — pairs with Copy-build-info above so
            // a user filing a bug can grab the build string and jump
            // straight to GitHub Issues without leaving Settings.
            AboutLinkRow(
                title = "Report a bug",
                subtitle = "github.com/anthropics/radioshed/issues",
                onClick = {
                    com.visorcraft.radioshed.source.openWebUrl(
                        "https://github.com/anthropics/radioshed/issues",
                    )
                },
            )
            Spacer(Modifier.height(8.dp))
            // License link rounds out the About section. The repo's
            // LICENSE file is the canonical source of truth (the
            // CreditsCard above lists OSS dependencies; this one
            // links to the proprietary terms governing RadioShed
            // itself).
            AboutLinkRow(
                title = "License",
                subtitle = "Proprietary — see LICENSE in the repo.",
                onClick = {
                    com.visorcraft.radioshed.source.openWebUrl(
                        "https://github.com/anthropics/radioshed/blob/master/LICENSE",
                    )
                },
            )
            Spacer(Modifier.height(16.dp))
            // Reset to defaults — wipes the user-portable preference
            // keys but leaves library / playlists / ratings / station
            // definitions / EQ presets intact. Confirmed via dialog
            // because there's no undo and a misclick here would be
            // genuinely annoying to recover from.
            ResetSettingsRow(onClick = { resetConfirmOpen = true })

            Spacer(Modifier.height(16.dp))
        }
        if (showScrollTop) {
            androidx.compose.material3.SmallFloatingActionButton(
                onClick = {
                    scope.launch { scrollState.animateScrollTo(0) }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = "Scroll to top",
                )
            }
        }
        }
    }

    if (resetConfirmOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { resetConfirmOpen = false },
            title = { Text("Reset settings to defaults?") },
            text = {
                Text(
                    "Clears every preference toggled in this screen — theme, font scale, " +
                        "audio prefs, sleep behavior, sort orders, etc. Your library, " +
                        "stations, playlists, ratings, listening history, and saved EQ " +
                        "presets are kept exactly as they are."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    resetConfirmOpen = false
                    settings.resetAllUserSettings()
                    // Re-pull every observable so the UI reflects defaults
                    // immediately — without this the toggles look
                    // unchanged until the user re-enters the screen.
                    reduceMotion = settings.getReduceMotion()
                    replayGain = settings.getReplayGainEnabled()
                    preampDb = settings.getReplayGainPreampDb()
                    autoResume = settings.getAutoResumeLastStation()
                    driveMode = settings.getDriveMode()
                    autoBackup = settings.getAutoBackupEnabled()
                    autoRescan = settings.getAutoRescanOnLaunch()
                    endFadeSec = settings.getEndFadeSec()
                    connectivityPolicy = settings.getConnectivityPolicy()
                    backupRetention = settings.getBackupRetention()
                    sleepStopOnExpire = settings.getSleepStopOnExpire()
                    keepScreenOn = settings.getKeepScreenOn()
                    autoFetchLyrics = settings.getAutoFetchLyrics()
                    hapticsEnabled = settings.getHapticsEnabled()
                    onThemeModeChange(settings.getThemeMode())
                    onMaterialYouChange(settings.getMaterialYouEnabled())
                    onFontScaleChange(settings.getFontScale())
                }) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { resetConfirmOpen = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AboutLinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun ResetSettingsRow(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "Reset settings to defaults",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "Wipes app preferences only. Library, ratings, stations, and playlists are untouched.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * "Stations summary" card from §7.5 About — track / album / artist
 * counts plus total library runtime. Reactive: subscribes to the
 * track-count Flow and re-fetches the rest whenever it ticks, so a
 * scan that finishes while AppSettings is still on the back stack
 * surfaces fresh counts as soon as the user navigates back here.
 */
@Composable
private fun LibrarySummaryCard(
    libraryRepo: com.visorcraft.radioshed.data.LibraryRepository = koinInject(),
    stationRepo: com.visorcraft.radioshed.data.StationRepository = koinInject(),
) {
    val trackCount by libraryRepo.observeTrackCount()
        .collectAsState(initial = null)
    var albumCount by remember { mutableStateOf<Long?>(null) }
    var artistCount by remember { mutableStateOf<Long?>(null) }
    var stationCount by remember { mutableStateOf<Long?>(null) }
    var totalRuntimeMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(trackCount) {
        // trackCount Flow emits on every track-row insert / update /
        // delete, which is the scanner's primary write path. Use it
        // as the "library changed" trigger to refresh the other
        // counters that don't have their own Flow yet.
        if (trackCount != null) {
            albumCount = libraryRepo.countAlbums()
            artistCount = libraryRepo.countArtists()
            stationCount = stationRepo.list().size.toLong()
            totalRuntimeMs = libraryRepo.totalLibraryDurationMs()
        }
    }
    val copyToClipboard = com.visorcraft.radioshed.ui.util.rememberCopyToClipboard()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            // Tap copies a paste-friendly multi-line summary —
            // useful for "what's in your library?" chats and bug
            // reports. No long-press behavior because the row is
            // purely informational.
            .clickable {
                val tc = trackCount?.toString() ?: "…"
                val ac = albumCount?.toString() ?: "…"
                val arc = artistCount?.toString() ?: "…"
                val sc = stationCount?.toString() ?: "…"
                val rt = totalRuntimeMs?.let { formatLibraryRuntime(it) } ?: "…"
                copyToClipboard(
                    "Library: $tc tracks · $ac albums · $arc artists · " +
                        "$sc stations · $rt total runtime",
                )
            },
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

/**
 * Three-step artwork reset row: deletes on-disk blobs, clears the
 * cache table, nulls every album.artwork_uri pointer. Status text
 * mirrors lyrics-cache for consistency. Re-runnable — pressing again
 * just confirms zero files this time.
 */
@Composable
private fun ClearDjCacheRow(status: String?, onClick: () -> Unit) {
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
                    "Clear DJ cache",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = status ?: "Wipes generated DJ TTS audio clips. " +
                        "Future stings regenerate from the TTS pipeline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
            TextButton(onClick = onClick) { Text("Clear") }
        }
    }
}

/**
 * One-tap "wipe every cache the app generated" row. Sits below the
 * granular Clear-X rows so users who already know which cache they
 * want still find that path first; this row exists for the "I just
 * want to free up storage" motion. Status string echoes the
 * aggregate counts back so the user reads a meaningful confirmation
 * (e.g. "Cleared lyrics, 124 artwork files, and 8 DJ clips.").
 */
@Composable
private fun ClearAllCachesRow(status: String?, onClick: () -> Unit) {
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
                    "Clear all online caches",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = status ?: "Wipes lyrics, artwork, and DJ clips in " +
                        "one tap. All three regenerate on demand.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
            TextButton(onClick = onClick) { Text("Clear all") }
        }
    }
}

@Composable
private fun ClearArtworkCacheRow(status: String?, onClick: () -> Unit) {
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
                    "Clear artwork cache",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = status ?: "Wipes downloaded album art. The next " +
                        "Cover Art sweep or library scan refetches.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
            TextButton(onClick = onClick) { Text("Clear") }
        }
    }
}

/**
 * Expandable Credits / OSS-licenses card. Lists the major direct
 * dependencies that ship in the APK so a curious user (or a Play-
 * Store reviewer once we get there) can see RadioShed's lineage.
 * Collapsed by default — most users don't care, and the section is
 * non-essential.
 */
@Composable
private fun CreditsCard() {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Credits & open-source",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = if (expanded) "Tap to hide"
                               else "Tap to see the libraries shipping with RadioShed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess
                                  else Icons.Filled.ExpandMore,
                    contentDescription = null,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                CREDIT_ENTRIES.forEach { (name, license) ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = license,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Lyrics from LRClib (CC0). Album art via the " +
                        "Cover Art Archive (CC0 / public domain). Genre + " +
                        "rating data from local file tags only — no online " +
                        "fingerprinting, no telemetry, internet usage is " +
                        "opt-in via the Connectivity policy above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/** Hand-curated list of direct OSS dependencies shipped with the
 *  APK. Order is rough relative-importance to playback. Updated
 *  as deps shift — this is a UI string list, not a build-time
 *  generated manifest, so versions are intentionally omitted. */
private val CREDIT_ENTRIES: List<Pair<String, String>> = listOf(
    "Kotlin Multiplatform" to "Apache-2.0",
    "Compose Multiplatform" to "Apache-2.0",
    "Material 3" to "Apache-2.0",
    "Media3 ExoPlayer" to "Apache-2.0",
    "SQLDelight" to "Apache-2.0",
    "Koin" to "Apache-2.0",
    "Coil 3" to "Apache-2.0",
    "kotlinx-serialization" to "Apache-2.0",
    "kotlinx-coroutines" to "Apache-2.0",
    "multiplatform-settings" to "Apache-2.0",
)

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
            // Three-dot color preview — surface / primary / accent
            // pulled from the static palette for the option's mode.
            // Gives users a visual hint of the theme's vibe without
            // having to flip into it. System mode shows mixed swatches
            // since it adapts at runtime.
            Spacer(Modifier.size(8.dp))
            ThemeSwatchTrio(option.mode)
        }
    }
}

/**
 * Static three-dot palette preview for a [ThemeMode]. Reads the
 * baked-in color tokens from `RadioShedColors` so the swatches stay
 * accurate without needing to instantiate a full ColorScheme — this
 * is a settings-list affordance, not a real theme preview canvas.
 */
@Composable
private fun ThemeSwatchTrio(mode: ThemeMode) {
    val triplet = remember(mode) { com.visorcraft.radioshed.ui.theme.previewSwatchesFor(mode) }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        triplet.forEach { color ->
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(color, CircleShape)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        shape = CircleShape,
                    ),
            )
        }
    }
}

/**
 * Slider for the end-of-track fade duration. 0 disables.
 * Honest naming — this is a track-end fade-out, not real
 * crossfade (no overlap into the next track).
 */
@Composable
private fun BackupRetentionSlider(value: Int, onChange: (Int) -> Unit) {
    // Mirror EndFadeSlider's lift-up-state pattern so prefs writes
    // happen on release, not every drag frame. Range 3..30 — past
    // 30 the user is better off archiving snapshots manually.
    var dragging by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(value) }
    val display = if (dragging) pending else value
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Auto-backup retention",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$display backup${if (display == 1) "" else "s"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
        Text(
            text = "Older backups are pruned once you exceed this count. Applies to manual exports too.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
        androidx.compose.material3.Slider(
            value = display.toFloat(),
            onValueChange = {
                dragging = true
                pending = it.toInt().coerceIn(3, 30)
            },
            onValueChangeFinished = {
                onChange(pending)
                dragging = false
            },
            valueRange = 3f..30f,
            steps = 26,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

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
    object Blocked : ArtworkSyncState
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
    is MissingArtworkSync.Progress.PolicyBlocked -> ArtworkSyncState.Blocked
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
                is ArtworkSyncState.Blocked -> {
                    Text(
                        "Connectivity policy blocks the sweep. Set Settings → " +
                            "Connectivity to Always or Wi-Fi only and try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
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
    val cycle = remember {
        listOf(
            com.visorcraft.radioshed.domain.ConnectivityPolicy.Always,
            com.visorcraft.radioshed.domain.ConnectivityPolicy.WiFiOnly,
            com.visorcraft.radioshed.domain.ConnectivityPolicy.Manual,
            com.visorcraft.radioshed.domain.ConnectivityPolicy.Never,
        )
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    val nextIdx = (cycle.indexOf(current) + 1).mod(cycle.size)
                    onPick(cycle[nextIdx])
                },
            ),
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

/**
 * Layout picker for the Now Playing screen. Four choices:
 * Auto (width-based: Compact <600dp, TwoPane 600-1100dp,
 * TriplePane >=1100dp), or three explicit overrides for users
 * who want a specific shape regardless of the breakpoint —
 * dashboard mounts, foldables, etc.
 *
 * The TriplePane choice is the SiriusXM-style three-column UX:
 * stations list on the left, transport in the middle, artwork
 * + metadata on the right. It's the user's specific feature
 * request — built as an in-app option rather than CarPlay /
 * Android Auto integration because both projection platforms
 * are template-locked and won't render arbitrary layouts.
 */
@Composable
private fun NowPlayingLayoutRow(
    current: String,
    onPick: (String) -> Unit,
) {
    val options = listOf(
        "Auto" to "Match the screen width",
        "Compact" to "Single column — phone portrait",
        "TwoPane" to "Art + transport — phone landscape",
        "TriplePane" to "Stations | transport | art — dashboard / foldable / tablet",
    )
    val explanation = options.firstOrNull { it.first == current }?.second
        ?: options.first().second
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "Now Playing layout",
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
            // FlowRow because four labels — including "TriplePane"
            // and "Compact" — overflow a single-row Row on narrow
            // phones. FlowRow wraps to a second row instead of
            // clipping the trailing chips silently.
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                options.forEach { (key, _) ->
                    val active = key == current
                    androidx.compose.material3.FilterChip(
                        selected = active,
                        onClick = { if (!active) onPick(key) },
                        label = {
                            Text(
                                when (key) {
                                    "Auto" -> "Auto"
                                    "Compact" -> "Compact"
                                    "TwoPane" -> "Two-pane"
                                    "TriplePane" -> "Three-pane (SXM-style)"
                                    else -> key
                                },
                            )
                        },
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

/**
 * One-tap "Generate genre stations" — picks the top-N genre tags
 * by track count, creates a `Genre: X` station per genre seeded
 * with `TagFilter("GENRE", listOf(genreName))` and Smart Shuffle
 * playback. Idempotent — stations whose name already exists are
 * skipped, so the user can hit the button again after their
 * library grows without producing duplicates. Threshold of 20
 * tracks per genre keeps the resulting list tidy.
 */
@Composable
private fun GenerateGenreStationsRow(
    libraryRepo: com.visorcraft.radioshed.data.LibraryRepository = koinInject(),
    stationRepo: com.visorcraft.radioshed.data.StationRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "Generate genre stations",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Creates one station per top genre tag, named \"Genre: X\". " +
                    "Skips genres with fewer than 20 tracks and skips " +
                    "stations that already exist — safe to tap again as " +
                    "the library grows.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
            Spacer(Modifier.height(8.dp))
            status?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
            }
            androidx.compose.material3.OutlinedButton(
                onClick = {
                    if (working) return@OutlinedButton
                    working = true
                    status = "Counting genres…"
                    scope.launch {
                        try {
                            // Single COUNT(*) GROUP BY query replaces the
                            // previous N-fetches-per-genre pattern that
                            // stalled cold-start on libraries with many
                            // distinct genres.
                            val counts = libraryRepo.tagValueTrackCounts("GENRE")
                            val topGenres = counts.entries
                                .filter { it.value >= 20L }
                                .sortedByDescending { it.value }
                                .take(10)
                                .map { it.key }
                            if (topGenres.isEmpty()) {
                                status = "No genres with at least 20 tracks. " +
                                    "Tag your tracks with a `GENRE` field, then re-run."
                                return@launch
                            }
                            val existing = stationRepo.list()
                            // Idempotency by source rule, not just name:
                            // matches whether the station has the exact
                            // TagFilter(GENRE, [name]) source so a user
                            // renaming "Genre: Rock" to "Rock Mix"
                            // doesn't cause a re-run to spawn a duplicate.
                            val existingGenres = existing.mapNotNullTo(mutableSetOf()) { st ->
                                (st.source as? com.visorcraft.radioshed.domain
                                    .SourceRule.TagFilter)
                                    ?.takeIf { it.key.equals("GENRE", ignoreCase = true) }
                                    ?.values
                                    ?.singleOrNull()
                                    ?.lowercase()
                            }
                            val maxSort = existing.maxOfOrNull { it.sortPosition } ?: 0
                            var inserted = 0
                            var skipped = 0
                            topGenres.forEachIndexed { idx, genre ->
                                if (genre.lowercase() in existingGenres) {
                                    skipped += 1
                                    return@forEachIndexed
                                }
                                val name = "Genre: $genre"
                                stationRepo.insert(
                                    name = name,
                                    source = com.visorcraft.radioshed.domain
                                        .SourceRule.TagFilter(
                                            key = "GENRE",
                                            values = listOf(genre),
                                        ),
                                    playback = com.visorcraft.radioshed.domain
                                        .PlaybackRule(
                                            order = com.visorcraft.radioshed.domain
                                                .PlayOrder.SmartShuffle,
                                        ),
                                    sortPosition = maxSort + 1 + idx,
                                    customSubtitle = "Tag-driven · ${counts[genre] ?: 0} tracks",
                                )
                                inserted += 1
                            }
                            status = buildString {
                                append("Created $inserted station")
                                if (inserted != 1) append("s")
                                if (skipped > 0) append(" ($skipped already existed)")
                                append(".")
                            }
                        } finally {
                            working = false
                        }
                    }
                },
                enabled = !working,
            ) { Text(if (working) "Working…" else "Generate") }
        }
    }
}

/**
 * One-tap "Generate decade stations" — picks decades that have at
 * least 50 albums (per SPEC §5: "only created for decades with
 * ≥50 tracks", upgraded to ≥50 albums here since we group by
 * Album.year). Each decade gets a `60s` / `70s` / `2000s`-style
 * station seeded with `AlbumSelector` over the in-decade album
 * IDs. Idempotent: skips stations whose name already exists.
 *
 * Decade-of-1990s = albums with `1990 ≤ year ≤ 1999`. Pre-1950s
 * gets a single "Pre-50s" bucket so a couple of 1948 jazz albums
 * don't go missing.
 */
@Composable
private fun GenerateDecadeStationsRow(
    libraryRepo: com.visorcraft.radioshed.data.LibraryRepository = koinInject(),
    stationRepo: com.visorcraft.radioshed.data.StationRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "Generate decade stations",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Buckets albums by Album.year and creates one " +
                    "station per decade with ≥3 albums. Skips " +
                    "albums with no year tag and skips stations " +
                    "that already exist.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
            Spacer(Modifier.height(8.dp))
            status?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
            }
            androidx.compose.material3.OutlinedButton(
                onClick = {
                    if (working) return@OutlinedButton
                    working = true
                    status = "Bucketing albums by decade…"
                    scope.launch {
                        try {
                            // Use the runtime current year (capped at the
                            // end of the current decade) so a 2031 album
                            // released after this code shipped still
                            // lands in the current decade bucket. Was
                            // previously hardcoded to 2030 — wrong from
                            // the moment a 2031 album appeared.
                            val nowYear = com.visorcraft.radioshed.data.currentYear()
                            val currentDecadeEnd = ((nowYear / 10) * 10) + 9
                            val buckets = buildList {
                                add("Pre-50s" to (1900 to 1949))
                                add("50s" to (1950 to 1959))
                                add("60s" to (1960 to 1969))
                                add("70s" to (1970 to 1979))
                                add("80s" to (1980 to 1989))
                                add("90s" to (1990 to 1999))
                                add("2000s" to (2000 to 2009))
                                add("2010s" to (2010 to 2019))
                                add("2020s" to (2020 to maxOf(2029, currentDecadeEnd)))
                                if (currentDecadeEnd >= 2039) {
                                    add("2030s" to (2030 to maxOf(2039, currentDecadeEnd)))
                                }
                            }
                            val existing = stationRepo.list()
                            // Idempotency by source rule: if a station's
                            // AlbumSelector already covers a decade's
                            // album-id set, treat it as already-existing
                            // even if the user has renamed it. Compare on
                            // the (min, max) year range encoded in the
                            // station's customSubtitle would be flaky;
                            // instead match by overlap of selected
                            // album IDs vs the decade's album IDs.
                            val existingAlbumSelectors = existing
                                .mapNotNull { st ->
                                    (st.source as? com.visorcraft.radioshed.domain
                                        .SourceRule.AlbumSelector)?.albumIds?.toSet()
                                }
                            val maxSort = existing.maxOfOrNull { it.sortPosition } ?: 0
                            var inserted = 0
                            var skipped = 0
                            buckets.forEachIndexed { idx, (label, range) ->
                                val (minYr, maxYr) = range
                                val albumIds = libraryRepo.albumIdsByYearRange(minYr, maxYr)
                                if (albumIds.size < 3) return@forEachIndexed
                                val albumSet = albumIds.toSet()
                                // Skip if any existing AlbumSelector
                                // station already covers ≥80% of this
                                // decade — that's almost certainly
                                // a renamed prior auto-generated one.
                                val isDuplicate = existingAlbumSelectors.any { existingSet ->
                                    val overlap = existingSet.count { it in albumSet }
                                    overlap >= (albumSet.size * 0.8)
                                }
                                if (isDuplicate) {
                                    skipped += 1
                                    return@forEachIndexed
                                }
                                val name = "Decade: $label"
                                stationRepo.insert(
                                    name = name,
                                    source = com.visorcraft.radioshed.domain
                                        .SourceRule.AlbumSelector(albumIds = albumIds),
                                    playback = com.visorcraft.radioshed.domain
                                        .PlaybackRule(
                                            order = com.visorcraft.radioshed.domain
                                                .PlayOrder.SmartShuffle,
                                        ),
                                    sortPosition = maxSort + 1 + idx,
                                    customSubtitle = "${albumIds.size} albums",
                                )
                                inserted += 1
                            }
                            status = if (inserted == 0 && skipped == 0) {
                                "No decades with at least 3 albums. Tag your " +
                                    "albums' year fields and re-run."
                            } else {
                                buildString {
                                    append("Created $inserted station")
                                    if (inserted != 1) append("s")
                                    if (skipped > 0) append(" ($skipped already existed)")
                                    append(".")
                                }
                            }
                        } finally {
                            working = false
                        }
                    }
                },
                enabled = !working,
            ) { Text(if (working) "Working…" else "Generate") }
        }
    }
}
