package com.visorcraft.radioshed.source

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android impl of [HistoryExporter]. Mirrors AndroidPlaylistExporter:
 * write a CSV into `cacheDir/history/`, expose it through
 * FileProvider, fire ACTION_SEND. The MIME type advertises text/csv
 * so spreadsheet apps and email clients show up in the chooser.
 */
class AndroidHistoryExporter(private val context: Context) : HistoryExporter {
    override suspend fun exportCsv(rows: List<HistoryCsvRow>): Boolean =
        withContext(IODispatcher) {
            runCatching {
                val cacheRoot = File(context.cacheDir, "history").apply { mkdirs() }
                val stamp = System.currentTimeMillis()
                var outFile = File(cacheRoot, "radioshed-history-$stamp.csv")
                var counter = 1
                while (outFile.exists() && counter <= 32) {
                    outFile = File(cacheRoot, "radioshed-history-${stamp}_$counter.csv")
                    counter += 1
                }
                outFile.writeText(buildCsv(rows))

                val authority = "${context.packageName}.fileprovider"
                val uri = FileProvider.getUriForFile(context, authority, outFile)
                val intent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "RadioShed listening history")
                        putExtra(Intent.EXTRA_TITLE, outFile.name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                val chooser =
                    Intent.createChooser(intent, "Export history").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                context.startActivity(chooser)
                true
            }.getOrDefault(false)
        }

    private fun buildCsv(rows: List<HistoryCsvRow>): String =
        buildString {
            // RFC-4180 header — quote everything to keep the parser
            // happy on titles that contain commas / quotes / newlines.
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
