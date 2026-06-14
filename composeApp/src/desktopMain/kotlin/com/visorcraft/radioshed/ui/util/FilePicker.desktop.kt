package com.visorcraft.radioshed.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Desktop generic file picker. The MIME-type argument from
 * commonMain is advisory; AWT's FileDialog only accepts a glob
 * pattern in [FileDialog.file], so we do a coarse mapping —
 * `application/json` → `*.json`, anything else → `*.*` — and let
 * the user override.
 */
@Composable
actual fun rememberFilePicker(onPicked: (ByteArray) -> Unit): (mimeType: String) -> Unit {
    val cb = remember(onPicked) { onPicked }
    return remember(cb) {
        { mimeType ->
            val dialog = FileDialog(null as Frame?, "Pick file", FileDialog.LOAD)
            dialog.file = mimeToGlob(mimeType)
            dialog.isVisible = true
            val name = dialog.file
            val dir = dialog.directory
            if (name != null && dir != null) {
                cb(File(dir, name).readBytes())
            }
        }
    }
}

private fun mimeToGlob(mimeType: String): String =
    when {
        mimeType.contains("json") -> "*.json"
        mimeType.contains("zip") -> "*.zip"
        mimeType.contains("text") -> "*.txt;*.md;*.lrc"
        else -> "*.*"
    }
