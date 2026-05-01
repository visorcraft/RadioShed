package com.visorcraft.radioshed.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

actual class StoragePermissions(private val context: Context) {

    actual fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    actual fun requestAllFilesAccess() {
        // Send the user straight to the per-app "All files access" toggle.
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
