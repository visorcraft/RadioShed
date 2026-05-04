package com.visorcraft.radioshed.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.visorcraft.radioshed.data.SettingsStore
import org.koin.compose.koinInject

/**
 * App-wide haptic confirmation gated by the user's master haptics
 * pref. Returns a `() -> Unit` lambda — call sites invoke it for
 * each long-press / mode-flip / "important toggle" gesture and the
 * helper handles the on/off check, so individual screens don't have
 * to plumb the setting themselves.
 *
 * Wraps Compose's `LocalHapticFeedback`, which on Android maps to
 * `View.performHapticFeedback` and on iOS is currently a no-op
 * (Compose Multiplatform stub) — that's fine; the UX falls back to
 * "no buzz" which is exactly what most iOS apps do anyway.
 */
@Composable
fun rememberHapticTick(
    type: HapticFeedbackType = HapticFeedbackType.LongPress,
): () -> Unit {
    val haptics = LocalHapticFeedback.current
    val settings: SettingsStore = koinInject()
    return remember(haptics, type) {
        {
            if (settings.getHapticsEnabled()) {
                haptics.performHapticFeedback(type)
            }
        }
    }
}
