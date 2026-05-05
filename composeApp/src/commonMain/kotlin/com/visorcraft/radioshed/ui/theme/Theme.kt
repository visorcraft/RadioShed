package com.visorcraft.radioshed.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.materialkolor.rememberDynamicColorScheme
import com.visorcraft.radioshed.data.ThemeColorSource

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
)

private val AmoledColors = DarkColors.copy(
    background = AmoledBackground,
    surface = AmoledSurface,
)

enum class ThemeMode { System, Light, Dark, Amoled }

@Composable
fun RadioShedTheme(
    mode: ThemeMode = ThemeMode.System,
    colorSource: ThemeColorSource = ThemeColorSource.Brand,
    themeSeed: Color = Color(0xFFB04E00),
    accentSeed: Color = Color(0xFF765849),
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (mode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark, ThemeMode.Amoled -> true
    }
    val brandColors = when (mode) {
        ThemeMode.System -> if (systemDark) DarkColors else LightColors
        ThemeMode.Light -> LightColors
        ThemeMode.Dark -> DarkColors
        ThemeMode.Amoled -> AmoledColors
    }
    // Material You source: pull the platform's wallpaper-extracted
    // palette via the expect/actual `dynamicColorScheme` (Android 12+
    // returns non-null, everything else null → fall through to brand).
    val materialYou = if (colorSource == ThemeColorSource.MaterialYou) {
        dynamicColorScheme(isDark)
    } else {
        null
    }
    // Custom-seed source: derive a full Material 3 ColorScheme from
    // the user's primary seed via materialKolor (same algorithm as
    // wallpaper extraction), then derive a second scheme from the
    // accent seed and overlay only the secondary + tertiary roles.
    // The two-scheme overlay gives the user real "primary vs accent"
    // control without the contrast pitfalls of hand-picking each role.
    val custom = if (colorSource == ThemeColorSource.Custom) {
        val primaryScheme = rememberDynamicColorScheme(
            seedColor = themeSeed,
            isDark = isDark,
        )
        val accentScheme = rememberDynamicColorScheme(
            seedColor = accentSeed,
            isDark = isDark,
        )
        primaryScheme.copy(
            secondary = accentScheme.primary,
            onSecondary = accentScheme.onPrimary,
            secondaryContainer = accentScheme.primaryContainer,
            onSecondaryContainer = accentScheme.onPrimaryContainer,
            tertiary = accentScheme.tertiary,
            onTertiary = accentScheme.onTertiary,
            tertiaryContainer = accentScheme.tertiaryContainer,
            onTertiaryContainer = accentScheme.onTertiaryContainer,
        )
    } else {
        null
    }
    // AMOLED override: regardless of source, force background +
    // surface to pure black when the user picked AMOLED — the
    // explicit "true black" preference outranks the source palette's
    // typical near-black surface.
    val colors = when {
        materialYou != null && mode == ThemeMode.Amoled -> materialYou.copy(
            background = AmoledBackground,
            surface = AmoledSurface,
        )
        materialYou != null -> materialYou
        custom != null && mode == ThemeMode.Amoled -> custom.copy(
            background = AmoledBackground,
            surface = AmoledSurface,
        )
        custom != null -> custom
        else -> brandColors
    }
    // Apply the user's app-wide font scale on top of Material's
    // default Typography. Done by copying every text style with its
    // fontSize multiplied by the scale — Compose's `TextUnit *
    // Float` keeps unit semantics so sp values stay sp.
    val typography = remember(fontScale) {
        if (fontScale == 1f) Typography()
        else scaledTypography(Typography(), fontScale)
    }
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

/**
 * Multiply every Material text style's `fontSize` by [scale], leaving
 * weight / line-height / letter-spacing untouched. Re-uses the input
 * Typography as the base so future Material updates that add new
 * styles flow through automatically — we only override what's known.
 */
private fun scaledTypography(base: Typography, scale: Float): Typography = Typography(
    displayLarge = base.displayLarge.copy(fontSize = base.displayLarge.fontSize * scale),
    displayMedium = base.displayMedium.copy(fontSize = base.displayMedium.fontSize * scale),
    displaySmall = base.displaySmall.copy(fontSize = base.displaySmall.fontSize * scale),
    headlineLarge = base.headlineLarge.copy(fontSize = base.headlineLarge.fontSize * scale),
    headlineMedium = base.headlineMedium.copy(fontSize = base.headlineMedium.fontSize * scale),
    headlineSmall = base.headlineSmall.copy(fontSize = base.headlineSmall.fontSize * scale),
    titleLarge = base.titleLarge.copy(fontSize = base.titleLarge.fontSize * scale),
    titleMedium = base.titleMedium.copy(fontSize = base.titleMedium.fontSize * scale),
    titleSmall = base.titleSmall.copy(fontSize = base.titleSmall.fontSize * scale),
    bodyLarge = base.bodyLarge.copy(fontSize = base.bodyLarge.fontSize * scale),
    bodyMedium = base.bodyMedium.copy(fontSize = base.bodyMedium.fontSize * scale),
    bodySmall = base.bodySmall.copy(fontSize = base.bodySmall.fontSize * scale),
    labelLarge = base.labelLarge.copy(fontSize = base.labelLarge.fontSize * scale),
    labelMedium = base.labelMedium.copy(fontSize = base.labelMedium.fontSize * scale),
    labelSmall = base.labelSmall.copy(fontSize = base.labelSmall.fontSize * scale),
)
