package com.visorcraft.radioshed.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

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
    useMaterialYou: Boolean = false,
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
    // Material You: ask the platform for the wallpaper-derived scheme.
    // When AMOLED is selected, force the dynamic background/surface to
    // pure black so the AMOLED preference still wins over the
    // wallpaper palette (the user explicitly opted into true black).
    val materialYou = if (useMaterialYou) dynamicColorScheme(isDark) else null
    val colors = when {
        materialYou != null && mode == ThemeMode.Amoled -> materialYou.copy(
            background = AmoledBackground,
            surface = AmoledSurface,
        )
        materialYou != null -> materialYou
        else -> brandColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
