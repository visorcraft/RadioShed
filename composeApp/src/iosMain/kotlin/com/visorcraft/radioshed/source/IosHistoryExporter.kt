package com.visorcraft.radioshed.source

class IosHistoryExporter : HistoryExporter {
    override suspend fun exportCsv(rows: List<HistoryCsvRow>): Boolean = false
}
