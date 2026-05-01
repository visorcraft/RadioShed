package com.visorcraft.radioshed.permissions

/**
 * Cross-platform storage-permission shim.
 *
 * On Android this maps to `MANAGE_EXTERNAL_STORAGE` (all-files access),
 * required for fast 50k-file scans of arbitrary user folders. On iOS the
 * concept doesn't apply — folder access is granted per-bookmark by the
 * document picker — so iOS impls just return true and no-op the request.
 */
expect class StoragePermissions {

    /** True if the platform's "broad file access" permission is granted. */
    fun hasAllFilesAccess(): Boolean

    /**
     * Send the user to platform settings to grant the permission. Caller
     * should re-check [hasAllFilesAccess] after the user returns.
     */
    fun requestAllFilesAccess()
}
