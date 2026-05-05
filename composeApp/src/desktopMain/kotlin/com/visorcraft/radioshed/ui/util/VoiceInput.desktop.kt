package com.visorcraft.radioshed.ui.util

import androidx.compose.runtime.Composable

/**
 * Desktop has no system speech-to-text picker. Users dictate with
 * platform-level features (Windows Speech Recognition / macOS
 * Dictation) into the focused text field directly, so an in-app
 * voice button isn't a separate path. [supportsVoiceInput] returns
 * false → the search field's mic icon is hidden.
 */
@Composable
actual fun rememberVoiceInput(onResult: (String) -> Unit): () -> Unit = { /* no-op */ }

actual fun supportsVoiceInput(): Boolean = false
