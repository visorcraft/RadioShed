package com.visorcraft.radioshed.source

/** No-op iOS stub. Files app on iOS is sandboxed per-app and doesn't
 *  expose a programmatic "show this folder" entry-point we can drive
 *  from outside its sandbox. */
class IosFolderRevealer : FolderRevealer {
    override suspend fun revealParent(absolutePath: String): Boolean = false
}
