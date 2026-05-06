package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI

/**
 * Desktop [BackupSharer]. Opens an AWT save dialog so the user
 * picks where to drop a copy of the backup bundle (USB stick,
 * Dropbox folder, network share, etc.) — the OS-native pattern
 * for "share this file" on a head-unit-less platform. On a head
 * unit there isn't really a concept of share intents.
 *
 * If the user cancels the dialog, returns false — same convention
 * as the Android sharer. Errors during the byte copy also return
 * false so the UI can show a generic "couldn't share" toast.
 */
class DesktopBackupSharer : BackupSharer {

    override suspend fun share(file: BackupFile): Boolean = withContext(IODispatcher) {
        runCatching {
            val source = File(URI(file.uri))
            if (!source.exists()) return@runCatching false

            val dialog = FileDialog(null as Frame?, "Save backup as…", FileDialog.SAVE)
            dialog.file = file.displayName
            dialog.isVisible = true
            val name = dialog.file ?: return@runCatching false
            val dir = dialog.directory ?: return@runCatching false
            val target = File(dir, name)
            // FileDialog already prompts on overwrite; we trust the
            // user-confirmed path. writeBytes is a single atomic-ish
            // copy via NIO under the hood — fine for typical backup
            // sizes (single-MB to low-tens-of-MB).
            source.copyTo(target, overwrite = true)
            true
        }.getOrDefault(false)
    }
}
