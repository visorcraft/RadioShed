package com.visorcraft.radioshed.source

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.DocumentsContract
import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android impl of [FolderRevealer]. Uses the documents-tree URI for
 * primary external storage so the stock Files app opens at the
 * track's parent directory rather than at root. SD cards / removable
 * volumes have a different storage UUID; we only support primary
 * storage in v1 — falls back to false on non-primary paths.
 *
 * Intent.ACTION_VIEW with `vnd.android.document/directory` is the
 * widely-supported "open this folder" intent on Android 10+. We
 * also pass the document URI as `EXTRA_INITIAL_URI` for newer
 * file pickers that respect that hint.
 */
class AndroidFolderRevealer(private val context: Context) : FolderRevealer {

    override suspend fun revealParent(absolutePath: String): Boolean = withContext(IODispatcher) {
        runCatching {
            val file = File(absolutePath)
            val parent = file.parentFile ?: return@runCatching false
            val externalRoot = Environment.getExternalStorageDirectory().absolutePath
            val parentPath = parent.absolutePath
            // Only primary external storage handles the `primary:`
            // doc-id convention. /storage/<uuid>/ paths would need a
            // different docId (volume-uuid:relative/path) which the
            // stock Files app doesn't always honour — keep it simple
            // and report false so the caller can fall back to a
            // copy-path action.
            if (!parentPath.startsWith(externalRoot)) return@runCatching false
            val relative = parentPath.removePrefix(externalRoot).removePrefix("/")
            val docId = if (relative.isEmpty()) "primary:" else "primary:$relative"
            val uri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return@runCatching false
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}
