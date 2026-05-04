package com.visorcraft.radioshed.ui.util

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun KeepScreenOn(active: Boolean) {
    val context = LocalContext.current
    DisposableEffect(active) {
        // Walk up the context chain to find the Activity. ComposeView
        // wrapped in a custom ContextWrapper (e.g. ContextThemeWrapper
        // when Compose runs inside a fragment) would otherwise return
        // a non-Activity reference.
        var ctx = context
        while (ctx !is Activity && ctx is android.content.ContextWrapper) {
            ctx = ctx.baseContext
        }
        val activity = ctx as? Activity
        if (activity != null && active) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (activity != null) {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}
