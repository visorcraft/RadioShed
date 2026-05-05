package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.crash.CrashLogEntry
import com.visorcraft.radioshed.crash.CrashLogReader
import com.visorcraft.radioshed.crash.CrashLogSharer

/**
 * Bundle of small no-op desktop implementations for platform peers
 * we don't yet need to wire fully. Each is documented inline so
 * the gap is obvious to future-me — when a feature lands that needs
 * one of these, replace the corresponding stub with a real impl.
 */

/** Always reports online; full connectivity polling lands later. */
class DesktopConnectivityChecker : ConnectivityChecker {
    override fun isOnline(): Boolean = true
    override fun isUnmetered(): Boolean = true
}

/** No-op — desktop has no system ringtone concept. */
class DesktopRingtoneSetter : RingtoneSetter {
    override suspend fun setAsRingtone(absolutePath: String, displayName: String): RingtoneSetter.Result =
        RingtoneSetter.Result.NotSupported
}

/** No-op — sharing-via-OS share sheet isn't a thing on desktop. */
class DesktopTrackSharer : TrackSharer {
    override suspend fun share(absolutePath: String, displayName: String): Boolean = false
}

/** No-op — exporting a playlist needs a save-as dialog wired up. */
class DesktopPlaylistExporter : PlaylistExporter {
    override suspend fun exportM3u(
        playlistName: String,
        entries: List<M3uEntry>,
    ): Boolean = false
}

/** No-op — exporting history needs a save-as dialog wired up. */
class DesktopHistoryExporter : HistoryExporter {
    override suspend fun exportCsv(rows: List<HistoryCsvRow>): Boolean = false
}

/** No-op — desktop has no equivalent of Android ApplicationExitInfo. */
class DesktopCrashLogReader : CrashLogReader {
    override suspend fun recentEntries(limit: Int): List<CrashLogEntry> = emptyList()
}

/** No-op — desktop has no in-app share sheet. */
class DesktopCrashLogSharer : CrashLogSharer {
    override suspend fun share(subject: String, body: String): Boolean = false
}

/**
 * No-op TagWriter — full editing is deferred to the same pass that
 * implements iOS tag writing. Returns a generic failure so the UI's
 * "Save tags" path surfaces a clear "not supported on this platform"
 * message rather than appearing to succeed silently.
 */
class DesktopTagWriter : TagWriter {
    override suspend fun write(uri: String, patch: TagPatch): Result<Unit> =
        Result.failure(UnsupportedOperationException("Tag writing isn't supported on desktop yet."))
}
