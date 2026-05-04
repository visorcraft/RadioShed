package com.visorcraft.radioshed.source

import java.io.File

internal actual fun deleteFileIfExists(path: String): Boolean {
    return runCatching {
        val f = File(path)
        if (!f.exists() || f.isDirectory) return@runCatching false
        f.delete()
    }.getOrDefault(false)
}
