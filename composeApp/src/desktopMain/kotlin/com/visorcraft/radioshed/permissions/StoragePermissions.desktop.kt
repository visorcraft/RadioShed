package com.visorcraft.radioshed.permissions

/**
 * Desktop has no runtime storage permissions — the JVM process can
 * read anything the OS user can read. [hasAllFilesAccess] returns
 * true unconditionally and [requestAllFilesAccess] is a no-op.
 */
actual class StoragePermissions {
    actual fun hasAllFilesAccess(): Boolean = true

    actual fun requestAllFilesAccess() { /* no-op on desktop */ }
}
