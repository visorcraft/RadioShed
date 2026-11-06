package com.visorcraft.radioshed.source

/**
 * Writes the listening-history rows visible to the user as a CSV
 * file and hands it to the platform share sheet. CSV instead of
 * JSON because the typical use is "drop this into a spreadsheet
 * to chart my listening" — Excel / Numbers / Sheets all open CSV
 * natively, no parsing required.
 *
 * iOS impl is a Null stub for now; Android wires the file up via
 * FileProvider + ACTION_SEND, same pattern as [PlaylistExporter].
 */
interface HistoryExporter {
    /** Returns true when the share sheet was successfully launched. */
    suspend fun exportCsv(rows: List<HistoryCsvRow>): Boolean
}

/**
 * One row in the CSV. [playedAtIso] is an RFC-3339 / ISO-8601 date
 * string (e.g. "2026-05-03T19:24:11Z") because spreadsheet apps
 * reliably sniff that format as a date column. [listenedMs] is the
 * counted listening time for the play; [completed] is true when the
 * track ran past its completion threshold (≥80% of duration).
 */
data class HistoryCsvRow(
    val playedAtIso: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val stationName: String?,
    val listenedMs: Long,
    val completed: Boolean,
)
