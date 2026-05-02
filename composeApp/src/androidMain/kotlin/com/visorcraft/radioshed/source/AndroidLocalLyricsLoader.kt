package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Looks for sibling lyrics files next to the audio file. Resolution
 * order (first hit wins):
 *   1. `<dir>/<basename>.lrc`
 *   2. `<dir>/<basename>.LRC`     (case-flipped, since Android FAT/exFAT
 *                                  on SD cards is case-preserving but
 *                                  case-insensitive — `File.exists()`
 *                                  on Linux ext4 is not, hence the
 *                                  explicit second probe.)
 *   3. `<dir>/<basename>.txt`
 *
 * Reading is best-effort: any IO failure returns null and the caller
 * falls back to the network lyrics fetcher.
 */
class AndroidLocalLyricsLoader : LocalLyricsLoader {

    override suspend fun load(audioFilePath: String): LocalLyrics? = withContext(IODispatcher) {
        runCatching {
            val audioFile = File(audioFilePath)
            if (!audioFile.exists()) return@runCatching null
            val baseName = audioFile.nameWithoutExtension
            val dir = audioFile.parentFile ?: return@runCatching null

            val lrcLower = File(dir, "$baseName.lrc")
            val lrcUpper = File(dir, "$baseName.LRC")
            val txt = File(dir, "$baseName.txt")

            val lrcContent = lrcLower.takeIf { it.exists() && it.canRead() }?.readText()
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
