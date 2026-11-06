package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sibling-file lyrics loader for desktop. Resolution order matches
 * the Android impl:
 *   1. `<dir>/<basename>.lrc`
 *   2. `<dir>/<basename>.LRC` (case-flipped — Windows is case-
 *                              preserving but case-insensitive,
 *                              Linux ext4 is case-sensitive, so an
 *                              explicit second probe covers both)
 *   3. `<dir>/<basename>.txt`
 *
 * Returns null on any IO failure so [LyricsService] falls through to
 * the network fetcher.
 */
class DesktopLocalLyricsLoader : LocalLyricsLoader {
    override suspend fun load(audioFilePath: String): LocalLyrics? =
        withContext(IODispatcher) {
            runCatching {
                val audioFile = File(audioFilePath)
                if (!audioFile.exists()) return@runCatching null
                val baseName = audioFile.nameWithoutExtension
                val dir = audioFile.parentFile ?: return@runCatching null

                val lrcLower = File(dir, "$baseName.lrc")
                val lrcUpper = File(dir, "$baseName.LRC")
                val txt = File(dir, "$baseName.txt")

                val lrcContent =
                    lrcLower.takeIf { it.exists() && it.canRead() }?.readText()
                        ?: lrcUpper.takeIf { it.exists() && it.canRead() }?.readText()
                if (lrcContent != null) {
                    return@runCatching LocalLyrics(synced = lrcContent, plain = null)
                }

                val txtContent = txt.takeIf { it.exists() && it.canRead() }?.readText()
                if (txtContent != null) {
                    return@runCatching LocalLyrics(synced = null, plain = txtContent)
                }
                null
            }.getOrNull()
        }
}
