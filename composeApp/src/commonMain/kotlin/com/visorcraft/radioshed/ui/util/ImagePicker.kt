package com.visorcraft.radioshed.ui.util

import androidx.compose.runtime.Composable

/**
 * Cross-platform image picker hook. Returns a launcher function that,
 * when called, opens the platform image picker (Android Photo Picker
 * on API 33+; ACTION_GET_CONTENT with an image MIME type on older).
 * The supplied callback receives the picked file's raw bytes ready
 * to feed into [com.visorcraft.radioshed.source.ArtworkPersister].
 *
 * iOS is a no-op stub for now — UIDocumentPicker / PHPickerViewController
 * wiring needs Mac-side work.
 */
@Composable
expect fun rememberImagePicker(onPicked: (ByteArray) -> Unit): () -> Unit

/** Whether the platform actually supports image picking. UI hides
 *  the upload button when this returns false. */
expect fun supportsImagePicker(): Boolean
