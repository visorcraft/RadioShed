package com.visorcraft.radioshed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.PlayerState
import com.visorcraft.radioshed.audio.isMediaPlaybackAvailable
import com.visorcraft.radioshed.car.DriveModeController
import com.visorcraft.radioshed.data.FtsSearch
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.data.nowMillis
import com.visorcraft.radioshed.nav.AppScreen
import com.visorcraft.radioshed.nav.PlatformBackHandler
import com.visorcraft.radioshed.source.BackupService
import com.visorcraft.radioshed.stations.PresetSeeder
import com.visorcraft.radioshed.stations.StationPlayback
import com.visorcraft.radioshed.ui.home.HomeScreen
import com.visorcraft.radioshed.ui.library.AlbumDetailScreen
import com.visorcraft.radioshed.ui.library.ArtistDetailScreen
import com.visorcraft.radioshed.ui.library.GenreDetailScreen
import com.visorcraft.radioshed.ui.library.LibraryBrowserScreen
import com.visorcraft.radioshed.ui.library.LibrarySettingsScreen
import com.visorcraft.radioshed.ui.onboarding.OnboardingScreen
import com.visorcraft.radioshed.ui.player.LyricsScreen
import com.visorcraft.radioshed.ui.player.MiniPlayer
import com.visorcraft.radioshed.ui.player.NowPlayingScreen
import com.visorcraft.radioshed.ui.playlists.PlaylistDetailScreen
import com.visorcraft.radioshed.ui.playlists.PlaylistsScreen
import com.visorcraft.radioshed.ui.search.SearchScreen
import com.visorcraft.radioshed.ui.settings.AppSettingsScreen
import com.visorcraft.radioshed.ui.settings.BackupRestoreScreen
import com.visorcraft.radioshed.ui.settings.EqualizerScreen
import com.visorcraft.radioshed.ui.settings.HistoryScreen
import com.visorcraft.radioshed.ui.settings.StatsScreen
import com.visorcraft.radioshed.ui.stations.StationEditorScreen
import com.visorcraft.radioshed.ui.stations.StationsHomeScreen
import com.visorcraft.radioshed.ui.theme.RadioShedTheme
import com.visorcraft.radioshed.ui.util.VlcRequiredDialog
import com.visorcraft.radioshed.ui.util.openGetVlcPage
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
@Preview
fun App(
    settings: SettingsStore = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    libraryRepo: LibraryRepository = koinInject(),
    backupService: BackupService = koinInject(),
    presetSeeder: PresetSeeder = koinInject(),
    audioEngine: AudioEngine = koinInject(),
    driveModeController: DriveModeController = koinInject(),
    scrobbleSync: com.visorcraft.radioshed.source.ScrobbleSync = koinInject(),
    mediaSourceRegistry: com.visorcraft.radioshed.source.MediaSourceRegistry = koinInject(),
    subsonicSource: com.visorcraft.radioshed.source.SubsonicSource = koinInject(),
) {
    // Live-mutable theme inputs: read once from the persisted settings,
    // then hold in state so the AppSettingsScreen picker can re-theme
    // the whole app instantly when the user changes anything (no app
    // restart needed). All four values are passed both into
    // RadioShedTheme below and into AppSettingsScreen far below for
    // round-tripping.
    var themeMode by remember { mutableStateOf(settings.getThemeMode()) }
    var colorSource by remember {
        mutableStateOf(settings.getThemeColorSource())
    }
    var themeSeed by remember {
        mutableStateOf(androidx.compose.ui.graphics.Color(settings.getThemeSeedArgb()))
    }
    var accentSeed by remember {
        mutableStateOf(androidx.compose.ui.graphics.Color(settings.getAccentSeedArgb()))
    }
    var fontScale by remember { mutableStateOf(settings.getFontScale()) }
    RadioShedTheme(
        mode = themeMode,
        colorSource = colorSource,
        themeSeed = themeSeed,
        accentSeed = accentSeed,
        fontScale = fontScale,
    ) {
        // Determine the initial screen exactly once per app launch.
        val startScreen =
            remember {
                if (settings.isOnboardingComplete()) AppScreen.Home else AppScreen.Onboarding
            }
        var current by remember { mutableStateOf<AppScreen>(startScreen) }

        // "VLC required" modal (desktop only — Android/iOS always report
        // media playback available). Triggers at startup if libvlc is
        // absent, and re-shows on a play attempt that errors while libvlc
        // is still missing. Dismissible.
        //
        // Extracted into its own composable so audioEngine.state is
        // collected BELOW the App() root — collecting it at the root
        // forced the entire App body (every screen, every lambda) to
        // recompose on every Idle → Buffering → Playing → Paused → Ended
        // transition, i.e. on every play/pause tap and track change.
        VlcStateWatcher(audioEngine)

        // Composition-local nav handles for deep widgets (Track context
        // sheet, MiniPlayer overflow, etc.) that don't have a clean
        // ancestor route to plumb explicit callbacks through. The
        // Album / Artist / NowPlaying jumps work from anywhere because
        // these are single-target navigations; complex flows still go
        // through the explicit `current = ...` assignments below.
        val rootScope = rememberCoroutineScope()
        androidx.compose.runtime.CompositionLocalProvider(
            com.visorcraft.radioshed.nav.LocalNavigateToAlbum provides { id ->
                current = AppScreen.AlbumDetail(id)
            },
            com.visorcraft.radioshed.nav.LocalNavigateToArtist provides { id ->
                current = AppScreen.ArtistDetail(id)
            },
            // View-lyrics nav: play the track first, then jump to
            // Lyrics. Without the playback step Lyrics would render
            // the previously-playing track (it follows
            // audioEngine.nowPlaying), which would feel broken.
            com.visorcraft.radioshed.nav.LocalNavigateToLyrics provides { id ->
                rootScope.launch {
                    val track = libraryRepo.getTrackById(id) ?: return@launch
                    stationPlayback.playTracks(listOf(track), startIndex = 0)
                    current = AppScreen.Lyrics
                }
            },
            com.visorcraft.radioshed.nav.LocalNavInstalled provides true,
        ) {
            // Track the screen the user came from when they opened
            // NowPlaying so Close / Back can return there instead of
            // unconditionally collapsing to Stations. Reset to null any
            // time we leave NowPlaying — we only need it for that one
            // round-trip.
            var nowPlayingReturnTo by remember { mutableStateOf<AppScreen?>(null) }
            // Wrap navigation so any transition INTO NowPlaying records
            // the source screen. Other screens use plain `current = ...`.
            val navTo: (AppScreen) -> Unit = { target ->
                if (target == AppScreen.NowPlaying && current != AppScreen.NowPlaying) {
                    nowPlayingReturnTo = current
                }
                current = target
            }

            // Auto Drive Mode: start the trigger watcher once per process
            // lifetime. Idempotent start(); the controller runs on its own
            // app-lifetime scope and only writes Drive Mode on a trigger edge,
            // so a manual toggle is never stomped between edges. No-op on
            // desktop/iOS (NullBluetoothWatcher + no schedule ticks unless the
            // user configured a Schedule trigger).
            LaunchedEffect(Unit) { driveModeController.start() }

            // Flush any scrobbles that failed to submit while offline, once
            // per process lifetime. ScrobbleSync's gate check no-ops this
            // when the connectivity policy denies, scrobbling is off, or no
            // token is set; runCatching keeps a flush failure from surfacing
            // as a playback error banner.
            LaunchedEffect(Unit) {
                runCatching { scrobbleSync.drain() }
            }

            // Register the Subsonic MediaSource at launch when a server is
            // already configured, so station MediaSourceSelector rules can
            // enumerate the cloud library from the first composition. The
            // in-session path (the user saving credentials in Settings) is
            // handled directly by the "Test & save" callback in
            // SubsonicConfigRow, which registers the same singleton source
            // immediately. The source reads credentials live and registers
            // under the fixed id "subsonic" (SubsonicSource.id), so editing
            // the URL or credentials — even to a different host — needs no
            // re-registration and can't strand a stale entry: re-registering
            // is an idempotent map-put on that one key.
            LaunchedEffect(Unit) {
                if (settings.hasSubsonicConfig()) {
                    mediaSourceRegistry.register(subsonicSource)
                }
            }

            // Auto-resume the last station once per process lifetime — gated
            // by an opt-in setting (off by default). LaunchedEffect(Unit)
            // means this fires on first composition only; it doesn't re-trigger
            // when the user navigates back to Home.
            LaunchedEffect(Unit) {
                if (
                    settings.isOnboardingComplete() &&
                    settings.getAutoResumeLastStation()
                ) {
                    settings.getLastStationId()?.let { stationPlayback.playStation(it) }
                }
            }

            // One-shot genre case-canonicalization for libraries indexed
            // before normalize-on-scan was added. Idempotent + cheap (no-op
            // when distinct genre values are all already canonical), but
            // we still gate behind a settings flag so we don't run it on
            // every launch. Runs in the background; UI surfaces the change
            // on the next Library Browser entry via observeTrackCount.
            LaunchedEffect(Unit) {
                if (!settings.getGenresCanonicalized()) {
                    runCatching { libraryRepo.canonicalizeGenreTags() }
                    settings.setGenresCanonicalized(true)
                }
            }

            val ftsSearch: FtsSearch = koinInject()
            LaunchedEffect(Unit) {
                runCatching { ftsSearch.rebuildTrackIndex() }
            }

            // Prune append-only history tables that grow without bound
            // and progressively slow every Stats / Top-N / Forgotten-Gems
            // query (all of which GROUP-BY-scan the full table). The trim
            // methods already existed but were only callable from the
            // manual "Clear history" UI; without this auto-prune a user
            // with months of play history would see the app get slower
            // over time as the tables accumulated tens of thousands of
            // rows. 365 days retains a full year of stats; skips are kept
            // for 180 days (only powers "most skipped" lists). Runs on
            // launch and then roughly every 24 hours while the process is
            // alive, so long-running desktop sessions don't accumulate
            // unbounded history.
            val stationRepo: com.visorcraft.radioshed.data.StationRepository = koinInject()
            val ratingRepo: com.visorcraft.radioshed.data.RatingRepository = koinInject()
            LaunchedEffect(Unit) {
                while (isActive) {
                    if (settings.isOnboardingComplete()) {
                        runCatching {
                            val cutoff = nowMillis() - 365L * 24L * 60L * 60L * 1_000L
                            stationRepo.trimHistoryOlderThan(cutoff)
                        }
                        runCatching {
                            val cutoff = nowMillis() - 180L * 24L * 60L * 60L * 1_000L
                            ratingRepo.trimSkipsOlderThan(cutoff)
                        }
                    }
                    delay(86_400_000L)
                }
            }

            // Auto-rescan-on-launch safety net. Opt-in; for users who
            // edit their Music folder outside RadioShed (added new
            // albums via USB, ripped a CD, etc.) and don't want to
            // remember to rescan manually. Walks every registered folder
            // unconditionally — Scanner is idempotent (upsertTrack by
            // path) so re-scanning unchanged folders is harmless. A
            // future optimisation would compare folder mtime against
            // `lastScannedAt` to skip unchanged folders, but that needs
            // platform-specific filesystem access we don't have a clean
            // abstraction for in commonMain yet.
            val folderRepo: com.visorcraft.radioshed.data.FolderRepository = koinInject()
            val scanner: com.visorcraft.radioshed.scanner.Scanner = koinInject()
            LaunchedEffect(Unit) {
                if (settings.isOnboardingComplete() && settings.getAutoRescanOnLaunch()) {
                    runCatching {
                        folderRepo.list().forEach { folder ->
                            runCatching { scanner.scanFolder(folder.id).collect { } }
                        }
                    }
                }
            }

            // Auto-backup safety net. Opt-in; when enabled, fires a
            // BackupService.export() at most once per 7 days. Failures
            // are silent — the user already has a manual export path in
            // Settings → Backup, this is just a safety net for people
            // who forget. Successful runs persist their timestamp via
            // the same setting so the next launch checks the elapsed
            // window correctly.
            LaunchedEffect(Unit) {
                if (
                    settings.isOnboardingComplete() &&
                    settings.getAutoBackupEnabled()
                ) {
                    val intervalMs = 7L * 24L * 60L * 60L * 1_000L
                    val elapsed = nowMillis() - settings.getLastAutoBackupAt()
                    if (elapsed >= intervalMs) {
                        runCatching {
                            val r = backupService.export()
                            if (r is BackupService.ExportResult.Saved) {
                                settings.setLastAutoBackupAt(nowMillis())
                            }
                        }
                    }
                }
            }

            // Onboarding owns the full screen — no MiniPlayer overlay until the
            // user has actually finished setup.
            if (current == AppScreen.Onboarding) {
                OnboardingScreen(
                    onComplete = {
                        settings.setOnboardingComplete(true)
                        // Skip-tour from Welcome (and Skip from Scan)
                        // bypass the Folder→Scan→seedIfEmpty path, so the
                        // user lands on Stations Home with zero stations
                        // and no way to add any. Seed defensively here —
                        // PresetSeeder is idempotent (`seedIfEmpty` no-ops
                        // when stations already exist), so a redundant
                        // call from the normal onboarding path is free.
                        rootScope.launch { runCatching { presetSeeder.seedIfEmpty() } }
                        current = AppScreen.Home
                    },
                )
                return@CompositionLocalProvider
            }

            // NowPlaying is full-bleed — it intentionally bypasses the MiniPlayer
            // strip below since it IS the expanded player.
            if (current == AppScreen.NowPlaying) {
                // Return to wherever the user opened NowPlaying from, falling
                // back to Stations if no source was tracked (e.g. opened via
                // an external intent or quick-tile in the future).
                val back = nowPlayingReturnTo ?: AppScreen.Stations
                PlatformBackHandler {
                    current = back
                    nowPlayingReturnTo = null
                }
                NowPlayingScreen(
                    onClose = {
                        current = back
                        nowPlayingReturnTo = null
                    },
                    onOpenLyrics = { current = AppScreen.Lyrics },
                    onOpenAlbum = { id -> current = AppScreen.AlbumDetail(id) },
                    onOpenArtist = { id -> current = AppScreen.ArtistDetail(id) },
                    onOpenStation = { id ->
                        current = AppScreen.StationDetail(id)
                        nowPlayingReturnTo = null
                    },
                    onOpenFolder = { path ->
                        current = AppScreen.FolderBrowser(initialPath = path)
                    },
                )
                return@CompositionLocalProvider
            }

            // Lyrics — full-bleed, opened from Now Playing.
            if (current == AppScreen.Lyrics) {
                PlatformBackHandler { current = AppScreen.NowPlaying }
                LyricsScreen(onBack = { current = AppScreen.NowPlaying })
                return@CompositionLocalProvider
            }

            // Station Editor is also full-bleed — full-form scrolling page,
            // no point keeping the MiniPlayer below clipping the save row.
            val currentRoute = current
            if (currentRoute is AppScreen.NewStation || currentRoute is AppScreen.EditStation) {
                val editingId: Long? =
                    when (currentRoute) {
                        is AppScreen.EditStation -> currentRoute.stationId
                        else -> null
                    }
                PlatformBackHandler { current = AppScreen.Stations }
                StationEditorScreen(
                    stationId = editingId,
                    onClose = { current = AppScreen.Stations },
                )
                return@CompositionLocalProvider
            }

            // Post-onboarding screens are stacked in a Column so the MiniPlayer
            // sits below the active screen content. The active screen takes the
            // remaining space; the MiniPlayer hides itself when nothing is loaded.
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    when (current) {
                        AppScreen.Home ->
                            HomeScreen(
                                onLibrary = { current = AppScreen.Library },
                                onSearch = { current = AppScreen.Search },
                                onStations = { current = AppScreen.Stations },
                                onSettings = { current = AppScreen.AppSettings },
                            )

                        AppScreen.Library -> {
                            PlatformBackHandler { current = AppScreen.Home }
                            LibraryBrowserScreen(
                                onBack = { current = AppScreen.Home },
                                onManage = { current = AppScreen.LibrarySettings },
                                onArtistTap = { id -> current = AppScreen.ArtistDetail(id) },
                                onAlbumTap = { id -> current = AppScreen.AlbumDetail(id) },
                                onGenreTap = { g -> current = AppScreen.GenreDetail(g) },
                                onEditStation = { id -> current = AppScreen.EditStation(id) },
                            )
                        }

                        is AppScreen.AlbumDetail -> {
                            val albumId = (current as AppScreen.AlbumDetail).albumId
                            PlatformBackHandler { current = AppScreen.Library }
                            AlbumDetailScreen(
                                albumId = albumId,
                                onBack = { current = AppScreen.Library },
                                onArtistTap = { id -> current = AppScreen.ArtistDetail(id) },
                            )
                        }

                        is AppScreen.ArtistDetail -> {
                            val artistId = (current as AppScreen.ArtistDetail).artistId
                            PlatformBackHandler { current = AppScreen.Library }
                            ArtistDetailScreen(
                                artistId = artistId,
                                onBack = { current = AppScreen.Library },
                                onAlbumTap = { id -> current = AppScreen.AlbumDetail(id) },
                            )
                        }

                        is AppScreen.GenreDetail -> {
                            val g = (current as AppScreen.GenreDetail).genre
                            PlatformBackHandler { current = AppScreen.Library }
                            GenreDetailScreen(
                                genre = g,
                                onBack = { current = AppScreen.Library },
                            )
                        }

                        AppScreen.LibrarySettings -> {
                            PlatformBackHandler { current = AppScreen.Library }
                            LibrarySettingsScreen(
                                onBack = { current = AppScreen.Library },
                                onOpenDuplicates = { current = AppScreen.LibraryDuplicates },
                            )
                        }

                        AppScreen.LibraryDuplicates -> {
                            PlatformBackHandler { current = AppScreen.LibrarySettings }
                            com.visorcraft.radioshed.ui.library.LibraryDuplicatesScreen(
                                onBack = { current = AppScreen.LibrarySettings },
                                onTrackClick = { track ->
                                    // Open the file's parent folder in the
                                    // built-in browser so the user can
                                    // inspect / move / delete it manually.
                                    val parent =
                                        com.visorcraft.radioshed.source
                                            .parentPath(track.path)
                                            .ifBlank { track.path }
                                    current = AppScreen.FolderBrowser(parent)
                                },
                            )
                        }

                        AppScreen.DbBrowser -> {
                            PlatformBackHandler { current = AppScreen.AppSettings }
                            com.visorcraft.radioshed.ui.settings.DbBrowserScreen(
                                onBack = { current = AppScreen.AppSettings },
                            )
                        }

                        AppScreen.Licenses -> {
                            // No outer PlatformBackHandler — LicensesScreen
                            // installs its own so back collapses detail -> list
                            // before popping the screen (FolderBrowser precedent).
                            com.visorcraft.radioshed.ui.settings.LicensesScreen(
                                onBack = { current = AppScreen.AppSettings },
                            )
                        }

                        AppScreen.SubsonicBrowse -> {
                            PlatformBackHandler { current = AppScreen.AppSettings }
                            com.visorcraft.radioshed.ui.cloud.SubsonicBrowseScreen(
                                onBack = { current = AppScreen.AppSettings },
                            )
                        }

                        AppScreen.AppSettings -> {
                            PlatformBackHandler { current = AppScreen.Home }
                            AppSettingsScreen(
                                onBack = { current = AppScreen.Home },
                                currentThemeMode = themeMode,
                                onThemeModeChange = { themeMode = it },
                                currentColorSource = colorSource,
                                onColorSourceChange = { colorSource = it },
                                currentThemeSeed = themeSeed,
                                onThemeSeedChange = { themeSeed = it },
                                currentAccentSeed = accentSeed,
                                onAccentSeedChange = { accentSeed = it },
                                currentFontScale = fontScale,
                                onFontScaleChange = { fontScale = it },
                                onOpenEqualizer = { current = AppScreen.Equalizer },
                                onOpenDjSettings = { current = AppScreen.DjSettings },
                                onOpenDriveMode = { current = AppScreen.DriveModeSettings },
                                onOpenPlaylists = { current = AppScreen.Playlists },
                                onOpenBackup = { current = AppScreen.BackupRestore },
                                onOpenStats = { current = AppScreen.Stats },
                                onOpenHistory = { current = AppScreen.History },
                                onOpenCrashLog = { current = AppScreen.CrashLog },
                                onOpenSyncthingWizard = { current = AppScreen.SyncthingWizard },
                                onOpenDbBrowser = { current = AppScreen.DbBrowser },
                                onOpenLicenses = { current = AppScreen.Licenses },
                                onOpenSubsonic = { current = AppScreen.SubsonicBrowse },
                            )
                        }

                        AppScreen.Stats -> {
                            PlatformBackHandler { current = AppScreen.AppSettings }
                            StatsScreen(
                                onBack = { current = AppScreen.AppSettings },
                                onArtistTap = { id -> current = AppScreen.ArtistDetail(id) },
                                onAlbumTap = { id -> current = AppScreen.AlbumDetail(id) },
                            )
                        }

                        AppScreen.History -> {
                            PlatformBackHandler { current = AppScreen.AppSettings }
                            HistoryScreen(onBack = { current = AppScreen.AppSettings })
                        }

                        AppScreen.CrashLog -> {
                            PlatformBackHandler { current = AppScreen.AppSettings }
                            com.visorcraft.radioshed.ui.settings.CrashLogScreen(
                                onBack = { current = AppScreen.AppSettings },
                            )
                        }

                        AppScreen.SyncthingWizard -> {
                            PlatformBackHandler { current = AppScreen.AppSettings }
                            com.visorcraft.radioshed.ui.onboarding.SyncthingWizardScreen(
                                onBack = { current = AppScreen.AppSettings },
                                onComplete = { current = AppScreen.AppSettings },
                            )
                        }

                        AppScreen.Equalizer -> {
                            PlatformBackHandler { current = AppScreen.AppSettings }
                            EqualizerScreen(onBack = { current = AppScreen.AppSettings })
                        }

                        AppScreen.DjSettings -> {
                            PlatformBackHandler { current = AppScreen.AppSettings }
                            com.visorcraft.radioshed.ui.settings.DjSettingsScreen(
                                onBack = { current = AppScreen.AppSettings },
                            )
                        }

                        AppScreen.DriveModeSettings -> {
                            PlatformBackHandler { current = AppScreen.AppSettings }
                            com.visorcraft.radioshed.ui.car.DriveModeSettingsScreen(
                                onBack = { current = AppScreen.AppSettings },
                            )
                        }

                        AppScreen.BackupRestore -> {
                            PlatformBackHandler { current = AppScreen.AppSettings }
                            BackupRestoreScreen(onBack = { current = AppScreen.AppSettings })
                        }

                        AppScreen.Playlists -> {
                            PlatformBackHandler { current = AppScreen.AppSettings }
                            PlaylistsScreen(
                                onBack = { current = AppScreen.AppSettings },
                                onOpenPlaylist = { id -> current = AppScreen.PlaylistDetail(id) },
                                onOpenLikedSongs = { current = AppScreen.LikedSongs },
                            )
                        }

                        AppScreen.LikedSongs -> {
                            PlatformBackHandler { current = AppScreen.Playlists }
                            com.visorcraft.radioshed.ui.playlists.LikedSongsScreen(
                                onBack = { current = AppScreen.Playlists },
                            )
                        }

                        is AppScreen.PlaylistDetail -> {
                            val pid = (current as AppScreen.PlaylistDetail).playlistId
                            PlatformBackHandler { current = AppScreen.Playlists }
                            PlaylistDetailScreen(
                                playlistId = pid,
                                onBack = { current = AppScreen.Playlists },
                            )
                        }

                        is AppScreen.FolderBrowser -> {
                            // No PlatformBackHandler here — the screen
                            // installs its own so back-press walks up the
                            // directory tree before falling through to
                            // close. Today's only entry point is "Open
                            // folder" from Track Info, so close returns
                            // to NowPlaying.
                            val initial = (current as AppScreen.FolderBrowser).initialPath
                            com.visorcraft.radioshed.ui.library.FolderBrowserScreen(
                                initialPath = initial,
                                onClose = { current = AppScreen.NowPlaying },
                            )
                        }

                        AppScreen.Search -> {
                            PlatformBackHandler { current = AppScreen.Home }
                            SearchScreen(
                                onBack = { current = AppScreen.Home },
                                onArtistTap = { id -> current = AppScreen.ArtistDetail(id) },
                                onAlbumTap = { id -> current = AppScreen.AlbumDetail(id) },
                            )
                        }

                        AppScreen.Stations -> {
                            PlatformBackHandler { current = AppScreen.Home }
                            StationsHomeScreen(
                                onBack = { current = AppScreen.Home },
                                onOpenNowPlaying = { navTo(AppScreen.NowPlaying) },
                                onAddStation = { current = AppScreen.NewStation },
                                onEditStation = { id -> current = AppScreen.EditStation(id) },
                                onOpenStationDetail = { id -> current = AppScreen.StationDetail(id) },
                                onReorderStations = { current = AppScreen.ReorderStations },
                            )
                        }

                        is AppScreen.StationDetail -> {
                            val sid = (current as AppScreen.StationDetail).stationId
                            PlatformBackHandler { current = AppScreen.Stations }
                            com.visorcraft.radioshed.ui.stations.StationDetailScreen(
                                stationId = sid,
                                onBack = { current = AppScreen.Stations },
                                onPlay = { navTo(AppScreen.NowPlaying) },
                            )
                        }

                        AppScreen.ReorderStations -> {
                            PlatformBackHandler { current = AppScreen.Stations }
                            com.visorcraft.radioshed.ui.stations.ReorderStationsScreen(
                                onDone = { current = AppScreen.Stations },
                            )
                        }

                        // The following branches are handled at the outer
                        // level before this `when` is even reached (see the
                        // `if (current == ...)` checks above the Column);
                        // we still need the exhaustive cases so the sealed-
                        // class check passes.
                        AppScreen.Onboarding,
                        AppScreen.NowPlaying,
                        AppScreen.Lyrics,
                        AppScreen.NewStation,
                        is AppScreen.EditStation,
                        -> Unit
                        // StationDetail handled above explicitly; no else
                        // branch needed because the sealed list is exhaustive.
                    }
                }
                // Transient playback-error banner. Surfaces the latest
                // failure from StationPlayback (rule eval crash, zero
                // matches, etc.) so the user gets a clear hint instead of
                // a silent no-op. Auto-dismisses after a short window so a
                // stale message doesn't linger on Home. Tap-to-dismiss
                // also clears it via clearPlaybackError.
                val playbackError by stationPlayback.playbackError.collectAsStateWithLifecycle()
                playbackError?.let { msg ->
                    androidx.compose.runtime.LaunchedEffect(msg) {
                        kotlinx.coroutines.delay(5_000)
                        stationPlayback.clearPlaybackError()
                    }
                    androidx.compose.material3.Surface(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                // Role.Button + a content description tells
                                // TalkBack / Narrator that this is interactive
                                // (tap-to-dismiss) and reads the error text
                                // itself rather than a generic "Surface". A
                                // plain clickable Surface gets announced as a
                                // non-interactive container, which hides the
                                // dismiss affordance from non-visual users.
                                .clickable(
                                    onClickLabel = "Dismiss error",
                                    role = androidx.compose.ui.semantics.Role.Button,
                                ) { stationPlayback.clearPlaybackError() },
                        color = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer,
                    ) {
                        androidx.compose.material3.Text(
                            text = msg,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                MiniPlayer(onExpand = { navTo(AppScreen.NowPlaying) })
            }
        } // CompositionLocalProvider
    }
}

/**
 * Collects [AudioEngine.state] in an isolated leaf so the App root
 * doesn't recompose on every player-state transition. See the call
 * site in [App] for the full rationale.
 */
@Composable
private fun VlcStateWatcher(audioEngine: AudioEngine) {
    var showVlcDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!isMediaPlaybackAvailable()) showVlcDialog = true
    }
    val playerState by audioEngine.state.collectAsStateWithLifecycle()
    LaunchedEffect(playerState) {
        if (playerState is PlayerState.Error && !isMediaPlaybackAvailable()) {
            showVlcDialog = true
        }
    }
    if (showVlcDialog) {
        VlcRequiredDialog(
            onDismiss = { showVlcDialog = false },
            onGetVlc = { openGetVlcPage() },
        )
    }
}
