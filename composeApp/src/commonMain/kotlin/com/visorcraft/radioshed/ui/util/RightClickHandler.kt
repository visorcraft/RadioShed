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
 * consume both the press AND the paired release so any sibling
 * Compose ContextMenuArea or future shell-context-menu handler
 * (which fire on Release rather than Press) doesn't double-trigger
 * on the same physical right-click.
 *
 * Re-keys on [action] so the lambda captured by [pointerInput]
 * always points at the freshest closure — without this, an outer
 * Composable that recomposes with a new station id would keep the
 * stale tile-id callback wired.
 */
fun Modifier.onRightClick(action: () -> Unit): Modifier = pointerInput(action) {
    awaitEachGesture {
        val press = awaitPointerEvent()
        if (press.type == PointerEventType.Press && press.buttons.isSecondaryPressed) {
            press.changes.forEach { it.consume() }
            action()
            // Consume the matching Release too. Compose Desktop's own
            // ContextMenuArea (and some Skiko platform shims) install
            // handlers that fire on Release rather than Press; without
            // this second consume() they'd see the unhandled release
            // and pop a duplicate context menu over the one our
            // [action] just opened.
            while (true) {
                val next = awaitPointerEvent()
                if (next.type == PointerEventType.Release) {
                    next.changes.forEach { it.consume() }
                    break
                }
                // Other event types (Move, Enter, Exit) in between
                // press and release are ignored; the gesture isn't
                // complete until release.
            }
        }
    }
}
