package com.visorcraft.radioshed.source

/**
 * iOS doesn't expose "storage volumes" in any cross-app sense — file
 * access is per-bookmark via UIDocumentPickerViewController. The picker
 * presents the user with iCloud Drive / On My Device / external folders
 * as appropriate, so we don't need to enumerate ourselves. Stub returns
 * an empty list; the iOS Library Settings UI shows the document picker
 * directly instead of suggested-folder chips.
 */
actual class StorageVolumes {
    actual fun discover(): List<DiscoveredVolume> = emptyList()
}
