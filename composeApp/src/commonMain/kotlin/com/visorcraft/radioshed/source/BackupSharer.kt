package com.visorcraft.radioshed.source

/**
 * Platform-abstracted "share this backup file with another app" hook.
 * On Android this is an `Intent.ACTION_SEND` chooser routed through a
 * FileProvider so other apps can read our private filesDir file. iOS
 * gets a no-op stub until the platform's share-sheet is wired up.
 *
 * Returns true on success (chooser launched), false on failure
 * (e.g. file not found, no app to share with).
 */
interface BackupSharer {
    suspend fun share(file: BackupFile): Boolean
}

class NullBackupSharer : BackupSharer {
    override suspend fun share(file: BackupFile): Boolean = false
}
