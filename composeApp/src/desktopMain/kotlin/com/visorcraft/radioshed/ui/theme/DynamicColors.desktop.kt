package com.visorcraft.radioshed.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/**
 * Desktop has no equivalent of Android's wallpaper-extracted dynamic
 * palette. The Settings UI hides the "Material You" chip when
 * [supportsMaterialYou] returns false; the user can still pick a
 * custom seed via the color picker, which is the cross-platform
 * answer to the same need.
 */
@Composable
actual fun dynamicColorScheme(dark: Boolean): ColorScheme? = null

actual fun supportsMaterialYou(): Boolean = false
