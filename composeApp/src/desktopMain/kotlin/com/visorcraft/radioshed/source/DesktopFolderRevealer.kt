package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/**
 * Desktop folder revealer — opens the parent directory of a file in
 * the OS file manager (Explorer / Finder / nautilus). Falls back to
 * `Desktop.getDesktop().open(parent)` which is supported on every
 * desktop OS that JavaFX/AWT covers.
 *
 * On Windows there's no clean cross-OS API for "highlight the
 * file" inside Explorer (you'd need `explorer.exe /select,...`).
 * For the spike we just open the parent folder; selecting the
 * specific file is a future polish.
 */
class DesktopFolderRevealer : FolderRevealer {
    override suspend fun revealParent(absolutePath: String): Boolean =
        withContext(IODispatcher) {
            runCatching {
                val parent = File(absolutePath).parentFile ?: return@runCatching false
                if (!Desktop.isDesktopSupported()) return@runCatching false
                val desktop = Desktop.getDesktop()
                if (!desktop.isSupported(Desktop.Action.OPEN)) return@runCatching false
                desktop.open(parent)
                true
            }.getOrDefault(false)
        }
}
