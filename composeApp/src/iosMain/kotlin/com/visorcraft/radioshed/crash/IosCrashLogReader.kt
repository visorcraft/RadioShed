package com.visorcraft.radioshed.crash

/**
 * iOS stub. Crash reporting on iOS lives in TestFlight / Xcode Organizer
 * rather than via an in-app feed; if we ever want a local-only equivalent
 * we'd need to wire `MetricKit` (`MXCrashDiagnostic`) — but that requires
 * an `MXMetricManager` subscription on the iOS side and KMP plumbing
 * to flow `MXCrashDiagnostic` payloads here. For now, return empty.
 */
class IosCrashLogReader : CrashLogReader {
    override suspend fun recentEntries(limit: Int): List<CrashLogEntry> = emptyList()
}
