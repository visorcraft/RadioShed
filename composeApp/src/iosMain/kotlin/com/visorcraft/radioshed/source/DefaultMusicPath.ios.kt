package com.visorcraft.radioshed.source

/**
 * iOS exposes user folders only through the document picker; there's
 * no equivalent of a known absolute path to seed the field with.
 * Returns empty so the UI shows the placeholder hint instead.
 */
actual fun defaultMusicFolderPath(): String = ""
