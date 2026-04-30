package com.visorcraft.radioshed

import androidx.compose.runtime.Composable
import com.visorcraft.radioshed.ui.landing.LandingScreen
import com.visorcraft.radioshed.ui.theme.RadioShedTheme
import androidx.compose.ui.tooling.preview.Preview

@Composable
@Preview
fun App() {
    RadioShedTheme {
        LandingScreen(
            onGetStarted = {
                // TODO: navigate to onboarding when navigation graph lands
            },
        )
    }
}
