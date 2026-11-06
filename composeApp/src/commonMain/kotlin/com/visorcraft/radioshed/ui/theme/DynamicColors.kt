package com.visorcraft.radioshed.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/**
 * Platform-specific Material You dynamic color resolver. Android 12+
 * extracts a 5-tone palette from the user's wallpaper and exposes it
 * via `dynamicLightColorScheme(context)` / `dynamicDarkColorScheme`.
 * Earlier Android versions and iOS return null — the caller falls
 * back to the brand colour scheme defined in [Theme.kt].
 *
 * iOS has no equivalent system-wallpaper palette; we keep the brand
 * scheme there. (UIKit's tinting is per-control, not a system palette.)
 */
@Composable
expect fun dynamicColorScheme(dark: Boolean): ColorScheme?

/** Whether Material You is even possible on this build. UI hides the
 *  toggle when this returns false. */
expect fun supportsMaterialYou(): Boolean
