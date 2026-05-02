package com.visorcraft.radioshed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.nav.AppScreen
import com.visorcraft.radioshed.stations.StationPlayback
import com.visorcraft.radioshed.nav.PlatformBackHandler
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
import com.visorcraft.radioshed.ui.theme.ThemeMode
import org.koin.compose.koinInject

@Composable
@Preview
fun App(
    settings: SettingsStore = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
) {
    // Live-mutable theme mode: read once from the persisted setting, then
    // hold in state so the AppSettingsScreen picker can re-theme the whole
    // app instantly when the user changes it (no app restart needed).
    var themeMode by remember { mutableStateOf(settings.getThemeMode()) }
    RadioShedTheme(mode = themeMode) {
        // Determine the initial screen exactly once per app launch.
        val startScreen = remember {
            if (settings.isOnboardingComplete()) AppScreen.Home else AppScreen.Onboarding
        }
        var current by remember { mutableStateOf<AppScreen>(startScreen) }
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

        // Onboarding owns the full screen — no MiniPlayer overlay until the
        // user has actually finished setup.
        if (current == AppScreen.Onboarding) {
            OnboardingScreen(
                onComplete = {
                    settings.setOnboardingComplete(true)
                    current = AppScreen.Home
                },
            )
            return@RadioShedTheme
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
            )
            return@RadioShedTheme
        }

        // Lyrics — full-bleed, opened from Now Playing.
        if (current == AppScreen.Lyrics) {
            PlatformBackHandler { current = AppScreen.NowPlaying }
            LyricsScreen(onBack = { current = AppScreen.NowPlaying })
            return@RadioShedTheme
        }

        // Station Editor is also full-bleed — full-form scrolling page,
        // no point keeping the MiniPlayer below clipping the save row.
        val currentRoute = current
        if (currentRoute is AppScreen.NewStation || currentRoute is AppScreen.EditStation) {
            val editingId: Long? = when (currentRoute) {
                is AppScreen.EditStation -> currentRoute.stationId
                else -> null
            }
            PlatformBackHandler { current = AppScreen.Stations }
            StationEditorScreen(
                stationId = editingId,
                onClose = { current = AppScreen.Stations },
            )
            return@RadioShedTheme
        }

        // Post-onboarding screens are stacked in a Column so the MiniPlayer
        // sits below the active screen content. The active screen takes the
        // remaining space; the MiniPlayer hides itself when nothing is loaded.
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                when (current) {
                    AppScreen.Home -> HomeScreen(
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
                        LibrarySettingsScreen(onBack = { current = AppScreen.Library })
                    }

                    AppScreen.AppSettings -> {
                        PlatformBackHandler { current = AppScreen.Home }
                        AppSettingsScreen(
                            onBack = { current = AppScreen.Home },
                            currentThemeMode = themeMode,
                            onThemeModeChange = { themeMode = it },
                            onOpenEqualizer = { current = AppScreen.Equalizer },
                            onOpenPlaylists = { current = AppScreen.Playlists },
                            onOpenBackup = { current = AppScreen.BackupRestore },
                            onOpenStats = { current = AppScreen.Stats },
                            onOpenHistory = { current = AppScreen.History },
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

                    AppScreen.Equalizer -> {
                        PlatformBackHandler { current = AppScreen.AppSettings }
                        EqualizerScreen(onBack = { current = AppScreen.AppSettings })
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

                    AppScreen.Search -> {
                        PlatformBackHandler { current = AppScreen.Home }
                        SearchScreen(onBack = { current = AppScreen.Home })
                    }

                    AppScreen.Stations -> {
                        PlatformBackHandler { current = AppScreen.Home }
                        StationsHomeScreen(
                            onBack = { current = AppScreen.Home },
                            onOpenNowPlaying = { navTo(AppScreen.NowPlaying) },
                            onAddStation = { current = AppScreen.NewStation },
                            onEditStation = { id -> current = AppScreen.EditStation(id) },
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
                }
            }
            MiniPlayer(onExpand = { navTo(AppScreen.NowPlaying) })
        }
    }
}
