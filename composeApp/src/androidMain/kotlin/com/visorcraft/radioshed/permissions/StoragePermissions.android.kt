package com.visorcraft.radioshed.permissions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

actual class StoragePermissions(private val context: Context) {
    actual fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    actual fun requestAllFilesAccess() {
        // Preferred: per-app "All files access" deep-link. Some OEM ROMs
        // (heavily-skinned Samsung/Xiaomi/Vivo builds, plus a handful of
        // car-launcher variants) ship without an Activity registered for
        // the per-app variant — `startActivity` then throws
        // ActivityNotFoundException and crashes the app. Fall back to
        // the system-wide list so the user can scroll to RadioShed
        // manually; if even that's missing, drop into the generic app
        // settings page so the user can at least find the toggle.
        val perApp =
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val systemWide =
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val appDetails =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        try {
            context.startActivity(perApp)
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(systemWide)
            } catch (_: ActivityNotFoundException) {
                runCatching { context.startActivity(appDetails) }
            }
        }
    }
}
