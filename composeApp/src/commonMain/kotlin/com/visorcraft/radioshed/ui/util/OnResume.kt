package com.visorcraft.radioshed.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Run [action] every time the host activity / scene comes back to RESUMED.
 *
 * Use case: re-check a permission after the user returns from the system
 * Settings screen. The OS doesn't notify us when the user toggles
 * MANAGE_EXTERNAL_STORAGE; the only signal we have is "the activity got
 * resumed," which fires both for first-launch and for return-from-Settings.
 *
 * [action] is wrapped in [rememberUpdatedState] so closures over composable
 * state stay current without forcing the observer to re-register on every
 * recomposition.
 */
@Composable
fun OnResume(action: () -> Unit) {
    val current by rememberUpdatedState(action)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) current()
            }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
