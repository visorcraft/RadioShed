package com.visorcraft.radioshed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.nav.AppScreen
import com.visorcraft.radioshed.nav.PlatformBackHandler
import com.visorcraft.radioshed.ui.home.HomeScreen
import com.visorcraft.radioshed.ui.library.LibrarySettingsScreen
import com.visorcraft.radioshed.ui.onboarding.OnboardingScreen
import com.visorcraft.radioshed.ui.player.MiniPlayer
import com.visorcraft.radioshed.ui.player.NowPlayingScreen
import com.visorcraft.radioshed.ui.search.SearchScreen
import com.visorcraft.radioshed.ui.settings.AppSettingsScreen
import com.visorcraft.radioshed.ui.stations.StationEditorScreen
import com.visorcraft.radioshed.ui.stations.StationsHomeScreen
import com.visorcraft.radioshed.ui.theme.RadioShedTheme
import com.visorcraft.radioshed.ui.theme.ThemeMode
import org.koin.compose.koinInject

@Composable
@Preview
fun App(settings: SettingsStore = koinInject()) {
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
            PlatformBackHandler { current = AppScreen.Stations }
            NowPlayingScreen(onClose = { current = AppScreen.Stations })
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
                        onLibrary = { current = AppScreen.LibrarySettings },
                        onSearch = { current = AppScreen.Search },
                        onStations = { current = AppScreen.Stations },
                        onSettings = { current = AppScreen.AppSettings },
                    )

                    AppScreen.LibrarySettings -> {
                        PlatformBackHandler { current = AppScreen.Home }
                        LibrarySettingsScreen(onBack = { current = AppScreen.Home })
                    }

                    AppScreen.AppSettings -> {
                        PlatformBackHandler { current = AppScreen.Home }
                        AppSettingsScreen(
                            onBack = { current = AppScreen.Home },
                            currentThemeMode = themeMode,
                            onThemeModeChange = { themeMode = it },
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
                            onOpenNowPlaying = { current = AppScreen.NowPlaying },
                            onAddStation = { current = AppScreen.NewStation },
                            onEditStation = { id -> current = AppScreen.EditStation(id) },
                        )
                    }

                    AppScreen.Onboarding,
                    AppScreen.NowPlaying,
                    AppScreen.NewStation,
                    is AppScreen.EditStation,
                    -> Unit  // handled above
                }
            }
            MiniPlayer(onExpand = { current = AppScreen.NowPlaying })
        }
    }
}
