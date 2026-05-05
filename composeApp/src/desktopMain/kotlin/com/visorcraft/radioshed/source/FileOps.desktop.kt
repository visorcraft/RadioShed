package com.visorcraft.radioshed.source

import java.io.File

internal actual fun deleteFileIfExists(path: String): Boolean {
    val f = File(path)
    return if (f.exists()) f.delete() else false
}
