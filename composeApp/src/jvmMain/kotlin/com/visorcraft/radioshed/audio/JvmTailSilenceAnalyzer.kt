package com.visorcraft.radioshed.audio

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/**
 * Scans the last ~12s of PCM-decodable files (WAV, AIFF, AU) for trailing
 * silence. Compressed formats (MP3/FLAC/OGG) return null and Smart Fades fall
 * back to the track-length heuristic.
 */
class JvmTailSilenceAnalyzer : TailSilenceAnalyzer {
    override suspend fun trailingSilenceMs(
        uri: String,
        durationMs: Long,
    ): Long? =
        withContext(IODispatcher) {
            if (durationMs <= 0L) return@withContext null
            runCatching { analyze(File(uri), durationMs) }.getOrNull()
        }

    private fun analyze(
        file: File,
        durationMs: Long,
    ): Long? {
        if (!file.exists() || !file.canRead()) return null
        AudioSystem.getAudioInputStream(file).use { stream ->
            val format = stream.format
            if (format.encoding != AudioFormat.Encoding.PCM_SIGNED &&
                format.encoding != AudioFormat.Encoding.PCM_UNSIGNED
            ) {
                return null
            }
            return scanTail(stream, format, durationMs)
        }
    }

    private fun scanTail(
        stream: AudioInputStream,
        format: AudioFormat,
        durationMs: Long,
    ): Long? {
        val frameSize = format.frameSize
        val frameRate = format.frameRate
        if (frameSize <= 0 || frameRate <= 0f) return null

        val windowMs = 12_000L.coerceAtMost(durationMs)
        val windowFrames = (windowMs * frameRate / 1_000f).toLong().coerceAtLeast(1L)
        val totalFrames = stream.frameLength
        if (totalFrames <= 0L) return null

        val startFrame = (totalFrames - windowFrames).coerceAtLeast(0L)
        stream.skip(startFrame * frameSize)

        val chunkFrames = (frameRate * 0.05f).toInt().coerceAtLeast(1) // 50 ms
        val buffer = ByteArray(chunkFrames * frameSize)
        var trailingSilentMs = 0L
        var foundAudio = false

        while (true) {
            val read = stream.read(buffer, 0, buffer.size)
            if (read <= 0) break
            val framesRead = read / frameSize
            if (framesRead <= 0) break
            val rms = pcmRms(buffer, read, format)
            val silent = rms < SILENCE_RMS
            val chunkMs = (framesRead * 1_000f / frameRate).toLong().coerceAtLeast(1L)
            if (silent) {
                trailingSilentMs += chunkMs
            } else {
                foundAudio = true
                trailingSilentMs = 0L
            }
        }

        return if (foundAudio && trailingSilentMs >= MIN_TRAILING_SILENCE_MS) {
            trailingSilentMs.coerceAtMost(windowMs)
        } else {
            null
        }
    }

    private fun pcmRms(
        buffer: ByteArray,
        length: Int,
        format: AudioFormat,
    ): Double {
        val sampleSize = format.sampleSizeInBits / 8
        if (sampleSize != 2) return 1.0 // only 16-bit PCM supported
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val signed = if (sample and 0x8000 != 0) sample or -0x10000 else sample
            sum += signed * signed.toDouble()
            count++
            i += sampleSize * format.channels
        }
        if (count == 0) return 0.0
        return kotlin.math.sqrt(sum / count) / 32768.0
    }

    companion object {
        private const val SILENCE_RMS = 0.008
        private const val MIN_TRAILING_SILENCE_MS = 400L
    }
}
