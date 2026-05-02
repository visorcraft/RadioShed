package com.visorcraft.radioshed.ui.util

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberImagePicker(onPicked: (ByteArray) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // GetContent with image/* covers both the Android 13+ Photo Picker
    // and pre-13 ACTION_GET_CONTENT fallback transparently — the
    // platform routes the right UI based on SDK level.
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(IODispatcher) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                }
                if (bytes != null && bytes.isNotEmpty()) onPicked(bytes)
            }
        }
    }
    return { launcher.launch("image/*") }
}

actual fun supportsImagePicker(): Boolean = true
