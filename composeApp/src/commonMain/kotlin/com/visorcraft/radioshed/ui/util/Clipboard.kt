@file:Suppress("DEPRECATION")

package com.visorcraft.radioshed.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

/**
 * Compose-friendly clipboard write. Returns a `(String) -> Unit` that
 * the caller invokes from a click handler. Hides the deprecated
 * `LocalClipboardManager` API behind a single file-level suppress so
 * call sites stay readable; the suspend-aware `LocalClipboard` /
 * `setClipEntry` migration is deferred until Compose ships a
 * commonMain `ClipEntry` constructor (the current actual on Android
 * pulls in `android.content.ClipData`, which can't be referenced from
 * commonMain without new expect/actual infrastructure).
 */
@Composable
fun rememberCopyToClipboard(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    return { text -> clipboard.setText(AnnotatedString(text)) }
}
