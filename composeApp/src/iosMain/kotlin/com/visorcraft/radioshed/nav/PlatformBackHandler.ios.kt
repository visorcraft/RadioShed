package com.visorcraft.radioshed.nav

import androidx.compose.runtime.Composable

@Composable
@Suppress("UNUSED_PARAMETER")
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    // iOS doesn't surface a system back event to Compose. Apps either rely
    // on the swipe-from-edge gesture (handled by the parent navigation
    // controller in SwiftUI/UIKit) or render an explicit back chevron.
    // Our screens already render an explicit "Back" button in their TopAppBar.
}
