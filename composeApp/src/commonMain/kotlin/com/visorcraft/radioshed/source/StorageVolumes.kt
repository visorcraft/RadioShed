package com.visorcraft.radioshed.source

/**
 * Cross-platform discovery of mounted storage volumes (internal storage,
 * SD card, OTG drives) so users don't have to memorize mount paths like
 * `/storage/1A2B-3C4D` to add a folder.
 *
 * Returns `DiscoveredVolume`s keyed by absolute path; the UI in Library
 * Settings turns these into one-tap chips inside the "Add folder" dialog.
 *
 * On iOS the concept doesn't apply — folder access is granted per-bookmark
 * via the document picker — so the iOS impl returns an empty list.
 */
expect class StorageVolumes {
    fun discover(): List<DiscoveredVolume>
}

/**
 * One mounted storage volume the user could plausibly point a Folder at.
 *
 * @property path Absolute filesystem path of the mount point's root.
 * @property label Human-readable label ("Internal Storage", "microSD card",
 *                 "USB drive", or whatever the OS reports for this volume).
 * @property isPrimary True if this is the device's primary external storage
 *                 (`/storage/emulated/0` on Android). Always at most one.
 * @property isRemovable True if the volume is removable (SD card, USB).
 *                 The UI uses this to flag SD-card paths visually.
 */
data class DiscoveredVolume(
    val path: String,
    val label: String,
    val isPrimary: Boolean,
    val isRemovable: Boolean,
)
