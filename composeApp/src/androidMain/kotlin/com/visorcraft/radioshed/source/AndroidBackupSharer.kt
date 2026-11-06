package com.visorcraft.radioshed.source

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Launches Android's share-sheet chooser with the backup file. The
 * file lives under app-private filesDir, so we hand other apps a
 * content:// URI minted by FileProvider rather than the raw path —
 * Android 7+ disallows file:// URIs in cross-app intents anyway
 * (FileUriExposedException).
 *
 * Authority must match the one declared in AndroidManifest.xml
 * (${applicationId}.fileprovider).
 */
class AndroidBackupSharer(private val context: Context) : BackupSharer {
    override suspend fun share(file: BackupFile): Boolean =
        withContext(IODispatcher) {
            runCatching {
                val path =
                    android.net.Uri.parse(file.uri).path
                        ?: return@runCatching false
                val onDisk =
                    File(path).takeIf { it.exists() && it.canRead() }
                        ?: return@runCatching false

                val authority = "${context.packageName}.fileprovider"
                val contentUri = FileProvider.getUriForFile(context, authority, onDisk)

                // MIME picks: `.rsbak` is a zip → `application/zip`, `.json`
                // → `application/json`. Wrong MIME doesn't break the share
                // sheet but it's surfaced in the receiving app's picker
                // (Gmail labels by MIME, Files-by-Google chooses default
                // openers by it), so it's worth getting right.
                val mime =
                    if (onDisk.extension == "rsbak") {
                        "application/zip"
                    } else {
                        "application/json"
                    }
                val intent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, contentUri)
                        putExtra(Intent.EXTRA_SUBJECT, "RadioShed backup")
                        putExtra(Intent.EXTRA_TITLE, file.displayName)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                val chooser =
                    Intent.createChooser(intent, "Share backup").apply {
                        // Required when launching from a non-Activity context
                        // (we use the application context here).
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                context.startActivity(chooser)
                true
            }.getOrDefault(false)
        }
}
