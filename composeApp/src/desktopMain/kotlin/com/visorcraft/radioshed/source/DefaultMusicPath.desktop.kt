package com.visorcraft.radioshed.source

import java.nio.file.Paths

/**
 * Desktop default → `~/Music`. Resolves under the user's home
 * directory rather than a hard-coded absolute path so it works on
 * Windows (`C:\Users\<name>\Music`), macOS (`/Users/<name>/Music`),
 * and Linux (`/home/<name>/Music`) without further branching.
 */
actual fun defaultMusicFolderPath(): String =
    Paths.get(System.getProperty("user.home").orEmpty(), "Music").toString()
