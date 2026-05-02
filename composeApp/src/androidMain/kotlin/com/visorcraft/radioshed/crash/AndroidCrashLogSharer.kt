package com.visorcraft.radioshed.crash

import android.content.Context
import android.content.Intent
import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext

class AndroidCrashLogSharer(private val context: Context) : CrashLogSharer {

    override suspend fun share(subject: String, body: String): Boolean = withContext(IODispatcher) {
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
            val chooser = Intent.createChooser(intent, "Share crash log").apply {
                // Application context launch needs a new task.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }
}
