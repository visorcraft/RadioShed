package com.visorcraft.radioshed.source

import android.os.Environment

/**
 * Android resolves the public Music directory via [Environment]. On
 * the typical phone this is `/storage/emulated/0/Music` (primary
 * external storage); on devices with relocated user storage it
 * tracks whatever the OS reports.
 */
actual fun defaultMusicFolderPath(): String =
    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath
