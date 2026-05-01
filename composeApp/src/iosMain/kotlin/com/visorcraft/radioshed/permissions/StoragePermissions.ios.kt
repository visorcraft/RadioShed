package com.visorcraft.radioshed.permissions

actual class StoragePermissions {

    /** Always true on iOS — file access is granted per-bookmark, not globally. */
    actual fun hasAllFilesAccess(): Boolean = true

    /** No-op on iOS. */
    actual fun requestAllFilesAccess() = Unit
}
