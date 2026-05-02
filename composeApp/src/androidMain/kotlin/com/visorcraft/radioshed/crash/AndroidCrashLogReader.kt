package com.visorcraft.radioshed.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Android impl of [CrashLogReader] backed by [ApplicationExitInfo]
 * (API 30+ — matches our minSdk). The OS keeps roughly the last
 * dozen or so abnormal exits; we filter to crash / ANR / native crash
 * categories so the screen only shows entries the user actually wants
 * to see when triaging "did the app crash on me?".
 */
class AndroidCrashLogReader(private val context: Context) : CrashLogReader {

    override suspend fun recentEntries(limit: Int): List<CrashLogEntry> = withContext(IODispatcher) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return@withContext emptyList()
        val raw = runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, /* pid = */ 0, limit)
        }.getOrDefault(emptyList())
        raw.asSequence()
            .filter { it.reason in INTERESTING_REASONS }
            .map { it.toEntry() }
            .toList()
    }

    private fun ApplicationExitInfo.toEntry(): CrashLogEntry = CrashLogEntry(
        timestamp = timestamp,
        reason = reasonLabel(reason),
        description = description ?: "",
        processName = processName ?: "",
        importance = importanceLabel(importance),
        trace = readTrace(),
    )

    /**
     * Stack traces are exposed as an optional InputStream; the OS only
     * fills it for ANR (and sometimes CRASH_NATIVE). Read defensively —
     * the stream can be null AND can throw on read if the trace was
     * pruned between the listing call and the read call.
     */
    private fun ApplicationExitInfo.readTrace(): String? = runCatching {
        traceInputStream?.use { stream ->
            BufferedReader(InputStreamReader(stream)).readText().ifBlank { null }
        }
    }.getOrNull()

    private companion object {
        val INTERESTING_REASONS = setOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        )

        fun reasonLabel(code: Int): String = when (code) {
            ApplicationExitInfo.REASON_UNKNOWN -> "Unknown"
            ApplicationExitInfo.REASON_EXIT_SELF -> "Self exit"
            ApplicationExitInfo.REASON_SIGNALED -> "Signaled"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Low memory"
            ApplicationExitInfo.REASON_CRASH -> "Crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Initialization failure"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "Permission change"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Excessive resource use"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "User killed"
            ApplicationExitInfo.REASON_USER_STOPPED -> "User stopped"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Dependency died"
            ApplicationExitInfo.REASON_OTHER -> "Other"
            else -> "Code $code"
        }

        fun importanceLabel(code: Int): String = when (code) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "Foreground"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "Foreground service"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "Visible"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "Perceptible"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "Service"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "Cached"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "Gone"
            else -> "Code $code"
        }
    }
}
