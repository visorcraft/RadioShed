package com.visorcraft.radioshed.scanner

/**
 * Events emitted by [Scanner.scanFolder]. The UI subscribes to a flow of
 * these to render scan progress and surface warnings about individual files
 * without aborting the whole scan.
 */
sealed interface ScanProgress {

    val folderId: Long

    data class Started(override val folderId: Long) : ScanProgress

    data class Progress(
        override val folderId: Long,
        val tracksProcessed: Int,
        val currentFile: String,
    ) : ScanProgress

    /** A single file failed to parse / persist; scan continues. */
    data class Warning(
        override val folderId: Long,
        val message: String,
    ) : ScanProgress

    data class Completed(
        override val folderId: Long,
        val totalTracks: Int,
        val durationMs: Long,
    ) : ScanProgress

    data class Failed(
        override val folderId: Long,
        val message: String,
    ) : ScanProgress
}
