package com.visorcraft.radioshed.ui.util

import android.app.Activity
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun ImmersiveMode(active: Boolean) {
    val context = LocalContext.current
    DisposableEffect(active) {
        var ctx = context
        while (ctx !is Activity && ctx is android.content.ContextWrapper) ctx = ctx.baseContext
        val activity = ctx as? Activity
        val controller = activity?.window?.decorView?.windowInsetsController
        if (active) {
            controller?.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsets.Type.systemBars())
        }
        onDispose {
            if (active) controller?.show(WindowInsets.Type.systemBars())
        }
    }
}
