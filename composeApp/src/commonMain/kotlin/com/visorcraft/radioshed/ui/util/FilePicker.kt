package com.visorcraft.radioshed.ui.util

import androidx.compose.runtime.Composable

/**
 * Cross-platform file-picker hook for opening arbitrary files (not
 * just images). Returns a launcher function — call it with the MIME
 * type filter (e.g. "application/json") to open the system picker;
 * the supplied callback receives the picked file's raw bytes once
 * the user makes a selection.
 *
 * iOS is a no-op stub today.
 */
@Composable
expect fun rememberFilePicker(onPicked: (ByteArray) -> Unit): (mimeType: String) -> Unit
