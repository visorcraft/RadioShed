package com.visorcraft.radioshed.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Desktop image picker — uses AWT's native [FileDialog] so the
 * dialog matches the host OS's file-open chrome. Returns the chosen
 * file's bytes via [onPicked]. Cancels resolve as a no-op.
 *
 * A generic image-mime filter (`*.png;*.jpg;*.jpeg;*.gif;*.webp`)
 * is set so the user doesn't accidentally pick a PDF.
 */
@Composable
actual fun rememberImagePicker(onPicked: (ByteArray) -> Unit): () -> Unit {
    val cb = remember(onPicked) { onPicked }
    return remember(cb) {
        {
            val dialog = FileDialog(null as Frame?, "Pick image", FileDialog.LOAD)
            dialog.file = "*.png;*.jpg;*.jpeg;*.gif;*.webp"
            dialog.isVisible = true
            val name = dialog.file
            val dir = dialog.directory
            if (name != null && dir != null) {
                val bytes = File(dir, name).readBytes()
                cb(bytes)
            }
        }
    }
}

actual fun supportsImagePicker(): Boolean = true
