package com.visorcraft.radioshed.crash

/**
 * Hands a plain-text crash dump to the platform share sheet. Used by
 * the in-app Crash Log screen so the user can email / paste the dump
 * into a bug report. No telemetry path — sharing is fully user-driven.
 */
interface CrashLogSharer {
    /** Returns true if the share sheet was launched. */
    suspend fun share(
        subject: String,
        body: String,
    ): Boolean
}
