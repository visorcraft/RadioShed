package com.visorcraft.radioshed.ui.util

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Desktop right-click handler. Fires [action] on a single primary
 * press of the right mouse button. No-op on touch devices: Android
 * touch input never sets `isSecondaryPressed`, so the gesture loop
 * sees the press but the inner guard skips.
 *
 * Chains cleanly after [combinedClickable] / [clickable] — the
 * primary-button click logic those modifiers install doesn't
 * compete for the secondary-button event, and we explicitly
 * consume the pointer change so any sibling primary handler
 * downstream doesn't double-fire on the same press.
 *
 * Re-keys on [action] so the lambda captured by [pointerInput]
 * always points at the freshest closure — without this, an outer
 * Composable that recomposes with a new station id would keep the
 * stale tile-id callback wired.
 */
fun Modifier.onRightClick(action: () -> Unit): Modifier = pointerInput(action) {
    awaitEachGesture {
        val event = awaitPointerEvent()
        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
            event.changes.forEach { it.consume() }
            action()
        }
    }
}
