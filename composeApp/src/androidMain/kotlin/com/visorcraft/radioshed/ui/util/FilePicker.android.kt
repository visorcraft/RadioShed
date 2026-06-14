package com.visorcraft.radioshed.ui.util

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberFilePicker(onPicked: (ByteArray) -> Unit): (mimeType: String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent(),
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    val bytes =
                        withContext(IODispatcher) {
                            runCatching {
                                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            }.getOrNull()
                        }
                    if (bytes != null && bytes.isNotEmpty()) onPicked(bytes)
                }
            }
        }
    return { mime -> launcher.launch(mime) }
}
