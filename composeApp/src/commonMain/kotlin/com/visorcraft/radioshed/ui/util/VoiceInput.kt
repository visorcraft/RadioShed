package com.visorcraft.radioshed.ui.util

import androidx.compose.runtime.Composable

/**
 * Cross-platform speech-to-text hook. Returns a launcher function;
 * call it to start listening. The supplied callback receives the
 * recognized text once the user finishes speaking.
 *
 * Android: backed by `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`,
 * which delegates to whatever speech engine the user has installed
 * (typically Google Assistant). No model bundled by us.
 *
 * iOS: stub. iOS speech recognition needs `SFSpeechRecognizer`
 * which is a multi-step async API requiring a Mac to test.
 */
@Composable
expect fun rememberVoiceInput(onResult: (String) -> Unit): () -> Unit

/** Whether the platform exposes a speech recognizer. UI hides the
 *  mic button entirely when this returns false — a button that
 *  always failed would just confuse users. */
expect fun supportsVoiceInput(): Boolean
