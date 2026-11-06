package com.visorcraft.radioshed.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * Cross-platform equalizer surface. Android wires this to
 * `android.media.audiofx.Equalizer` attached to ExoPlayer's audio
 * session id (captured via `Player.Listener.onAudioSessionIdChanged`).
 * iOS gets a no-op stub until [AVAudioUnitEQ] integration lands.
 *
 * Gain is expressed in dB. The Android Equalizer API uses millibel
 * internally, but exposing dB to the UI keeps presets readable
 * ("+6 dB" rather than "+600 mB").
 *
 * The number of bands and their center frequencies are device-defined
 * on Android. The default Android Equalizer spec guarantees at least 5
 * bands centered roughly at 60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz — so the
 * UI can safely assume 5 sliders. [bandFrequenciesHz] reports what the
 * device actually returned in case it surprises us.
 */
interface EqualizerController {
    /** Number of equalizer bands. Always 0 on platforms where the EQ is unavailable. */
    val numBands: Int

    /** Center frequency for each band, in Hz. Empty if EQ is unavailable. */
    val bandFrequenciesHz: List<Int>

    /** Per-band gain in dB. Length matches [numBands]; updated atomically. */
    val bandGains: StateFlow<FloatArray>

    /** Whether the EQ is currently active. Toggling cheap; persists. */
    val enabled: StateFlow<Boolean>

    /** True when the platform actually has an audio effects pipeline. */
    val isAvailable: StateFlow<Boolean>

    /** Min / max gain the device's Equalizer accepts, in dB. */
    val gainRangeDb: ClosedFloatingPointRange<Float>

    fun setBand(
        index: Int,
        gainDb: Float,
    )

    fun setEnabled(enabled: Boolean)

    fun applyPreset(preset: EqPreset)

    /**
     * Release any native audio-effect resources. Called from the
     * media service's onDestroy. Android's `audiofx.Equalizer`
     * holds a reference-counted native handle; leaking it
     * eventually causes new effect instantiations to fail with
     * `IllegalStateException: too many effects`.
     */
    fun release()
}

/**
 * Built-in presets. [Custom] is a sentinel — selecting it is a no-op
 * (the user just keeps tweaking individual sliders); applying any of
 * the others overrides per-band values.
 */
enum class EqPreset(val label: String, val gainsDb: FloatArray) {
    Flat("Flat", floatArrayOf(0f, 0f, 0f, 0f, 0f)),
    BassBoost("Bass Boost", floatArrayOf(6f, 4f, 0f, 0f, 0f)),
    Vocal("Vocal", floatArrayOf(0f, 0f, 4f, 2f, 0f)),
    Treble("Treble", floatArrayOf(0f, 0f, 0f, 4f, 6f)),
    Custom("Custom", floatArrayOf(0f, 0f, 0f, 0f, 0f)),
}

/** Stub for platforms without an audio-effects pipeline (iOS for now). */
class NullEqualizerController : EqualizerController {
    private val flowFalse =
        kotlinx.coroutines.flow.MutableStateFlow(false).also { /* no-op */ }
    private val emptyGains =
        kotlinx.coroutines.flow.MutableStateFlow(FloatArray(0))

    override val numBands: Int = 0
    override val bandFrequenciesHz: List<Int> = emptyList()
    override val bandGains: StateFlow<FloatArray> = emptyGains
    override val enabled: StateFlow<Boolean> = flowFalse
    override val isAvailable: StateFlow<Boolean> = flowFalse
    override val gainRangeDb: ClosedFloatingPointRange<Float> = -15f..15f

    override fun setBand(
        index: Int,
        gainDb: Float,
    ) = Unit

    override fun setEnabled(enabled: Boolean) = Unit

    override fun applyPreset(preset: EqPreset) = Unit

    override fun release() = Unit
}
