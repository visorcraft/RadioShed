package com.visorcraft.radioshed.audio.visualizer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VisualizerAudioStatus {
    WaitingForAudio,
    Live,
    PermissionRequired,
    Unsupported,
}

class VisualizerCapture(
    val spectrum: FloatArray = FloatArray(VISUALIZER_BANDS),
    val waveform: FloatArray = FloatArray(VISUALIZER_WAVEFORM_POINTS),
)

/** Platform-owned capture handle. Capture exists only while the fullscreen is visible. */
interface VisualizerAudioSource {
    val capture: StateFlow<VisualizerCapture?>
    val status: StateFlow<VisualizerAudioStatus>

    fun start()

    fun stop()

    fun refreshPermission()

    fun onAudioSessionIdChanged(sessionId: Int) {}
}

/** Used on platforms where the playback engine cannot expose audio frames safely. */
class UnavailableVisualizerAudioSource : VisualizerAudioSource {
    override val capture: StateFlow<VisualizerCapture?> = MutableStateFlow<VisualizerCapture?>(null).asStateFlow()
    override val status: StateFlow<VisualizerAudioStatus> =
        MutableStateFlow(VisualizerAudioStatus.Unsupported).asStateFlow()

    override fun start() {}

    override fun stop() {}

    override fun refreshPermission() {}
}
