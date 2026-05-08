package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Desktop [HistoryExporter]. AWT save dialog → write CSV to the
 * user's chosen location. Identical column layout +
 * RFC-4180 quoting to [AndroidHistoryExporter] so a sheet built
 * against one export keeps working when fed the other.
 */
class DesktopHistoryExporter : HistoryExporter {

    override suspend fun exportCsv(rows: List<HistoryCsvRow>): Boolean = runCatching {
        val stamp = System.currentTimeMillis()
        val (dir, name) = showSaveDialog("Export history", "radioshed-history-$stamp.csv")
        if (dir == null || name == null) return@runCatching false
        withContext(IODispatcher) {
            File(dir, name).writeText(buildCsv(rows))
        }
        true
    }.getOrDefault(false)

    private fun buildCsv(rows: List<HistoryCsvRow>): String = buildString {
        append("played_at,title,artist,album,station,listened_ms,completed\n")
        rows.forEach { row ->
            append(quote(row.playedAtIso)).append(',')
            append(quote(row.title)).append(',')
            append(quote(row.artist ?: "")).append(',')
            append(quote(row.album ?: "")).append(',')
            append(quote(row.stationName ?: "")).append(',')
            append(row.listenedMs).append(',')
            append(if (row.completed) "true" else "false").append('\n')
        }
    }

    private fun quote(s: String): String {
        val escaped = s.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
