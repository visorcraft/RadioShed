package com.visorcraft.radioshed.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** iOS has no system-wallpaper palette equivalent; always null. */
@Composable
actual fun dynamicColorScheme(dark: Boolean): ColorScheme? = null

actual fun supportsMaterialYou(): Boolean = false
