package com.visorcraft.radioshed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.nav.AppScreen
import com.visorcraft.radioshed.nav.PlatformBackHandler
import com.visorcraft.radioshed.ui.home.HomeScreen
import com.visorcraft.radioshed.ui.library.LibrarySettingsScreen
import com.visorcraft.radioshed.ui.onboarding.OnboardingScreen
import com.visorcraft.radioshed.ui.search.SearchScreen
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

        when (current) {
            AppScreen.Onboarding -> OnboardingScreen(
                onComplete = {
                    settings.setOnboardingComplete(true)
                    current = AppScreen.Home
                },
            )

            AppScreen.Home -> HomeScreen(
                onLibrary = { current = AppScreen.LibrarySettings },
                onSearch = { current = AppScreen.Search },
                onStations = {
                    // Stations Home lands in Phase 6; for now just go to Library
                    // so the click isn't completely dead.
                    current = AppScreen.LibrarySettings
                },
            )

            AppScreen.LibrarySettings -> {
                PlatformBackHandler { current = AppScreen.Home }
                LibrarySettingsScreen(onBack = { current = AppScreen.Home })
            }

            AppScreen.Search -> {
                PlatformBackHandler { current = AppScreen.Home }
                SearchScreen(onBack = { current = AppScreen.Home })
            }
        }
    }
}
