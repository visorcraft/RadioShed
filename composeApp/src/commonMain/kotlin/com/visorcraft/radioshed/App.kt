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
import com.visorcraft.radioshed.ui.stations.StationsHomeScreen
import com.visorcraft.radioshed.ui.theme.RadioShedTheme
import org.koin.compose.koinInject

@Composable
@Preview
fun App(settings: SettingsStore = koinInject()) {
    RadioShedTheme {
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
                    )

                    AppScreen.LibrarySettings -> {
                        PlatformBackHandler { current = AppScreen.Home }
                        LibrarySettingsScreen(onBack = { current = AppScreen.Home })
                    }

                    AppScreen.Search -> {
                        PlatformBackHandler { current = AppScreen.Home }
                        SearchScreen(onBack = { current = AppScreen.Home })
                    }

                    AppScreen.Stations -> {
                        PlatformBackHandler { current = AppScreen.Home }
                        StationsHomeScreen(onBack = { current = AppScreen.Home })
                    }

                    AppScreen.Onboarding,
                    AppScreen.NowPlaying -> Unit  // handled above
                }
            }
            MiniPlayer(onExpand = { current = AppScreen.NowPlaying })
        }
    }
}
