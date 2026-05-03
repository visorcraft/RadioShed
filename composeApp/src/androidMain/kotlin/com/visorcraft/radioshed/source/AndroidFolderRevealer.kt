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
        val file = File(absolutePath)
        val parent = file.parentFile ?: return@withContext false
        val parentPath = parent.absolutePath

        // Try several intents in order — Android folder-open semantics
        // are inconsistent across OEMs. AOSP DocumentsUI handles the
        // documents-tree URI cleanly; Samsung's My Files responds to
        // a different content URI; older devices accept a plain
        // file:// URI. We chooser-wrap the implicit intents so the
        // user can pick a file manager when multiple are installed.
        val intents = buildList {
            // 1. DocumentsContract URI on primary external storage —
            //    AOSP DocumentsUI, Files by Google, etc.
            val externalRoot = Environment.getExternalStorageDirectory().absolutePath
            if (parentPath.startsWith(externalRoot)) {
                val relative = parentPath.removePrefix(externalRoot).removePrefix("/")
                val docId = if (relative.isEmpty()) "primary:" else "primary:$relative"
                val docUri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)
                add(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(docUri, "vnd.android.document/directory")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(DocumentsContract.EXTRA_INITIAL_URI, docUri)
                    },
                )
            }
            // 2. ACTION_OPEN_DOCUMENT_TREE — universally supported;
            //    drops the user into the SAF picker at the parent
            //    directory. Not as elegant as DocumentsUI's direct
            //    open, but reliable on devices where the documents
            //    URI ACTION_VIEW resolves to nothing (some Samsung,
            //    some Xiaomi).
            val externalRoot2 = Environment.getExternalStorageDirectory().absolutePath
            if (parentPath.startsWith(externalRoot2)) {
                val relative = parentPath.removePrefix(externalRoot2).removePrefix("/")
                val docId = if (relative.isEmpty()) "primary:" else "primary:$relative"
                val treeUri = DocumentsContract.buildTreeDocumentUri(
                    EXTERNAL_STORAGE_AUTHORITY,
                    docId,
                )
                add(
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri)
                    },
                )
            }
            // 3. Last-resort plain file:// + folder MIME. Restricted
            //    on Android 7+ via StrictMode FileUriExposedException,
            //    but worth trying as a final fallback for devices
            //    with custom file managers.
            add(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(android.net.Uri.fromFile(parent), "resource/folder")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }

        for (intent in intents) {
            val launched = runCatching {
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
            if (launched) return@withContext true
        }
        false
    }

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}
