package com.visorcraft.radioshed.source

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager

@OptIn(ExperimentalForeignApi::class)
internal actual fun deleteFileIfExists(path: String): Boolean {
    val mgr = NSFileManager.defaultManager
    if (!mgr.fileExistsAtPath(path)) return false
    return mgr.removeItemAtPath(path, error = null)
}
