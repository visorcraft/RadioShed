package com.visorcraft.radioshed.crash

/**
 * Local-only crash / ANR triage. Reads the OS-maintained list of recent
 * abnormal app exits (Android's `ApplicationExitInfo`, available since
 * API 30 — our minSdk) and exposes them to the in-app Crash Log screen.
 *
 * Nothing is uploaded anywhere. The list is sourced directly from the
 * system; clearing it requires uninstalling the app or letting the OS
 * age it out. iOS implementation is a stub for now — equivalent crash
 * reporting on iOS lives in TestFlight / Xcode Organizer rather than
 * an in-app feed.
 */
data class CrashLogEntry(
    val timestamp: Long,
    val reason: String,
    val description: String,
    val processName: String,
    val importance: String,
    val trace: String?,
)

interface CrashLogReader {
    /** Newest-first. Empty if the platform exposes nothing or the app
     *  hasn't crashed since install. */
    suspend fun recentEntries(limit: Int = 50): List<CrashLogEntry>
}
