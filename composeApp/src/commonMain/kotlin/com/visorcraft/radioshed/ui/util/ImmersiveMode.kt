package com.visorcraft.radioshed.ui.util

import androidx.compose.runtime.Composable

/** Hides system bars for an immersive surface and restores them on exit. */
@Composable
expect fun ImmersiveMode(active: Boolean)
