package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

/**
 * Desktop [BackupSharer]. Opens an AWT save dialog on the EDT,
 * then runs the byte copy on the IO dispatcher. The split matters
 * because `FileDialog.isVisible = true` deadlocks on Windows when
 * called from a non-EDT thread.
 */
class DesktopBackupSharer : BackupSharer {
    override suspend fun share(file: BackupFile): Boolean =
        runCatching {
            val source = File(URI(file.uri))
            if (!source.exists()) return@runCatching false

            val (dir, name) = showSaveDialog("Save backup as…", file.displayName)
            if (dir == null || name == null) return@runCatching false

            withContext(IODispatcher) {
                val target = File(dir, name)
                // FileDialog already prompts on overwrite; we trust the
                // user-confirmed path. writeBytes is a single atomic-ish
                // copy via NIO under the hood — fine for typical backup
                // sizes (single-MB to low-tens-of-MB).
                source.copyTo(target, overwrite = true)
            }
            true
        }.getOrDefault(false)
}
