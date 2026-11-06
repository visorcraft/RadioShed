package com.visorcraft.radioshed.audio.visualizer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Real FFT/waveform capture attached only to the audible ExoPlayer session. */
class AndroidVisualizerAudioSource(
    private val context: Context,
) : VisualizerAudioSource {
    private val mutableCapture = kotlinx.coroutines.flow.MutableStateFlow<VisualizerCapture?>(null)
    override val capture = mutableCapture.asStateFlow()

    private val mutableStatus =
        kotlinx.coroutines.flow.MutableStateFlow(VisualizerAudioStatus.WaitingForAudio)
    override val status = mutableStatus.asStateFlow()

    private var visualizer: Visualizer? = null
    private var active = false
    private var sessionId = 0
    private var buffers = arrayOf(VisualizerCapture(), VisualizerCapture())
    private var bufferIndex = 0

    override fun start() {
        active = true
        rebuild()
    }

    override fun stop() {
        active = false
        release()
        mutableStatus.value = VisualizerAudioStatus.WaitingForAudio
    }

    override fun refreshPermission() {
        if (active) rebuild()
    }

    override fun onAudioSessionIdChanged(sessionId: Int) {
        if (this.sessionId == sessionId && visualizer != null) return
        this.sessionId = sessionId
        if (active) rebuild()
    }

    @Suppress("MissingPermission")
    private fun rebuild() {
        release()
        if (!active) return
        if (!hasPermission()) {
            mutableStatus.value = VisualizerAudioStatus.PermissionRequired
            return
        }
        if (sessionId == 0) {
            mutableStatus.value = VisualizerAudioStatus.WaitingForAudio
            return
        }
        runCatching {
            val range = Visualizer.getCaptureSizeRange()
            val captureSize = range.lastOrNull()?.coerceAtMost(MAX_CAPTURE_SIZE) ?: DEFAULT_CAPTURE_SIZE
            val effect = Visualizer(sessionId)
            effect.captureSize = captureSize
            effect.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        source: Visualizer,
                        bytes: ByteArray,
                        samplingRate: Int,
                    ) {
                        val target = buffers[bufferIndex]
                        decodeWaveformBytes(bytes, target.waveform)
                    }

                    override fun onFftDataCapture(
                        source: Visualizer,
                        bytes: ByteArray,
                        samplingRate: Int,
                    ) {
                        val target = buffers[bufferIndex]
                        decodeFftBytes(bytes, target.spectrum)
                        mutableCapture.value = target
                        bufferIndex = (bufferIndex + 1) % buffers.size
                    }
                },
                (Visualizer.getMaxCaptureRate() / 2).coerceIn(MIN_CAPTURE_RATE, MAX_CAPTURE_RATE),
                true,
                true,
            )
            effect.enabled = true
            visualizer = effect
            mutableStatus.value = VisualizerAudioStatus.Live
        }.onFailure {
            release()
            mutableStatus.value = VisualizerAudioStatus.Unsupported
        }
    }

    private fun release() {
        runCatching { visualizer?.enabled = false }
        runCatching { visualizer?.release() }
        visualizer = null
        mutableCapture.value = null
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val MAX_CAPTURE_SIZE = 1024
        const val DEFAULT_CAPTURE_SIZE = 1024
        const val MIN_CAPTURE_RATE = 10_000
        const val MAX_CAPTURE_RATE = 20_000
    }
}
