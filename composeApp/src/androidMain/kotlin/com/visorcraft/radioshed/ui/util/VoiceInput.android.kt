package com.visorcraft.radioshed.ui.util

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

@Composable
actual fun rememberVoiceInput(onResult: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // RecognizerIntent stuffs the n-best list under
            // EXTRA_RESULTS; first entry is the highest-confidence
            // transcription. We use only that — the alternates are
            // useful for disambiguation UIs but our search treats
            // any text as a query and re-runs anyway.
            val matches = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                .orEmpty()
            val text = matches.firstOrNull()?.takeIf { it.isNotBlank() }
            if (text != null) onResult(text)
        }
    }
    return {
        runCatching {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Search RadioShed")
            }
            launcher.launch(intent)
        }
    }
}

// Always advertise support on Android. SpeechRecognizer.isRecognitionAvailable
// needs a Context which is awkward to thread through here, and the rare
// device without a recognizer just gets a swallowed launch — the mic
// button does nothing instead of showing a hint. Acceptable tradeoff.
actual fun supportsVoiceInput(): Boolean = true
