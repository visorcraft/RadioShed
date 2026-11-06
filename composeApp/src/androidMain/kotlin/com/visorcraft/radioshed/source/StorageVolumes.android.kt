package com.visorcraft.radioshed.source

import android.content.Context
import android.os.storage.StorageManager
import android.os.storage.StorageVolume

/**
 * Android impl uses [StorageManager.storageVolumes] (API 24+) and
 * [StorageVolume.directory] (API 30+, our minSdk). The directory API
 * resolves to absolute paths like:
 *   - `/storage/emulated/0` for primary internal storage
 *   - `/storage/<UUID>` for SD cards / OTG drives (UUID is the volume's
 *     filesystem UUID — e.g. `1A2B-3C4D` for FAT/exFAT)
 *
 * Volumes whose `directory` is null (unmounted, ejected, secured) are
 * filtered out — there's nothing the user can point a Folder at for those.
 */
actual class StorageVolumes(private val context: Context) {
    actual fun discover(): List<DiscoveredVolume> {
        val sm =
            context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
                ?: return emptyList()
        return sm.storageVolumes.mapNotNull { sv ->
            val dir = sv.directory ?: return@mapNotNull null
            DiscoveredVolume(
                path = dir.absolutePath,
                label =
                    sv.getDescription(context)
                        ?: if (sv.isPrimary) "Internal Storage" else dir.name,
                isPrimary = sv.isPrimary,
                isRemovable = sv.isRemovable,
            )
        }
    }
}
