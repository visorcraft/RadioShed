package com.visorcraft.radioshed.ui.theme

import androidx.compose.ui.graphics.Color

// RadioShed default palette: Material 3 baseline Blue. Cobalt-leaning,
// confident in light mode, sky-blue in dark mode, contrast-checked
// across the full role set. The Settings → Theme color → Custom path
// lets the user override with any seed via materialKolor; this is the
// out-of-box look when source = Default.
//
// Token values are the official Material 3 reference scheme for a
// 0xFF005AC1 seed — see the Material 3 dynamic-color sample app.

// ---- Light scheme tokens ----
internal val LightPrimary = Color(0xFF0061A4)
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFFD1E4FF)
internal val LightOnPrimaryContainer = Color(0xFF001D36)

internal val LightSecondary = Color(0xFF535F70)
internal val LightOnSecondary = Color(0xFFFFFFFF)
internal val LightSecondaryContainer = Color(0xFFD7E3F7)
internal val LightOnSecondaryContainer = Color(0xFF101C2B)

internal val LightTertiary = Color(0xFF6B5778)
internal val LightOnTertiary = Color(0xFFFFFFFF)

internal val LightBackground = Color(0xFFFDFCFF)
internal val LightOnBackground = Color(0xFF1A1C1E)
internal val LightSurface = Color(0xFFFDFCFF)
internal val LightOnSurface = Color(0xFF1A1C1E)

// ---- Dark scheme tokens ----
internal val DarkPrimary = Color(0xFF9ECAFF)
internal val DarkOnPrimary = Color(0xFF003258)
internal val DarkPrimaryContainer = Color(0xFF00497D)
internal val DarkOnPrimaryContainer = Color(0xFFD1E4FF)

internal val DarkSecondary = Color(0xFFBBC7DB)
internal val DarkOnSecondary = Color(0xFF253140)
internal val DarkSecondaryContainer = Color(0xFF3B4858)
internal val DarkOnSecondaryContainer = Color(0xFFD7E3F7)

internal val DarkTertiary = Color(0xFFD6BEE4)
internal val DarkOnTertiary = Color(0xFF3B2948)

internal val DarkBackground = Color(0xFF1A1C1E)
internal val DarkOnBackground = Color(0xFFE2E2E6)
internal val DarkSurface = Color(0xFF1A1C1E)
internal val DarkOnSurface = Color(0xFFE2E2E6)

// ---- AMOLED variant (true black) — for the AMOLED theme option in Settings ----
internal val AmoledBackground = Color(0xFF000000)
internal val AmoledSurface = Color(0xFF000000)

/**
 * Static three-color preview swatch list for a given theme mode.
 * Order is (background, surface-tint, primary-accent) so the trio
 * reads left-to-right from "page" to "accent" in the picker. System
 * mode interleaves a light and a dark cue since the theme will
 * follow the OS at runtime — we can't know ahead of time.
 */
fun previewSwatchesFor(mode: ThemeMode): List<Color> = when (mode) {
    ThemeMode.System -> listOf(LightBackground, DarkBackground, LightPrimary)
    ThemeMode.Light -> listOf(LightBackground, LightSecondaryContainer, LightPrimary)
    ThemeMode.Dark -> listOf(DarkBackground, DarkSecondaryContainer, DarkPrimary)
    ThemeMode.Amoled -> listOf(AmoledBackground, DarkSecondaryContainer, DarkPrimary)
}
