package com.visorcraft.radioshed.source

import platform.Foundation.NSFileManager

internal actual fun deleteFileIfExists(path: String): Boolean {
    val mgr = NSFileManager.defaultManager
    if (!mgr.fileExistsAtPath(path)) return false
    return mgr.removeItemAtPath(path, error = null)
}
