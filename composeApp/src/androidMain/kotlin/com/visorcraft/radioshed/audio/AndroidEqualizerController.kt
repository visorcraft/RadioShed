package com.visorcraft.radioshed.audio

import android.media.audiofx.Equalizer
import com.visorcraft.radioshed.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android Equalizer-backed controller. The ExoPlayer in
 * [RadioShedMediaSessionService] reports its audio session id via
 * [Player.Listener.onAudioSessionIdChanged] — the service forwards
 * those events here, where we (re)attach the Equalizer instance to
 * the new session.
 *
 * Why we don't construct the Equalizer eagerly:
 *   `Equalizer(priority, sessionId)` requires a real audio session id
 *   to attach to. That id is only assigned when the AudioSink first
 *   binds, which happens AFTER the first `prepare()` / `play()` cycle.
 *   Constructing eagerly with sessionId=0 (system mix) is rejected on
 *   Android 9+ when the global-effects role isn't granted. Lazy
 *   attach via the listener is the only consistently-working path.
 *
 * Gain accounting:
 *   The Android API speaks millibel ("mB", 1/100th of a dB). We
 *   accept dB at the public surface and convert internally.
 *
 * Persistence:
 *   Per-band gains + enabled flag are persisted via [SettingsStore]
 *   so the EQ survives app restarts. Re-attaching to a new session
 *   restores the saved values automatically.
 */
class AndroidEqualizerController(
    private val settings: SettingsStore,
) : EqualizerController {
    private val _bandGains = MutableStateFlow(loadPersistedGains())
    private val _enabled = MutableStateFlow(settings.getEqEnabled())
    private val _isAvailable = MutableStateFlow(false)

    private var equalizer: Equalizer? = null
    private var currentSessionId: Int = 0
    private var probedNumBands: Int = DEFAULT_NUM_BANDS
    private var probedFreqs: List<Int> = DEFAULT_BAND_FREQS_HZ

    override val numBands: Int get() = probedNumBands
    override val bandFrequenciesHz: List<Int> get() = probedFreqs
    override val bandGains: StateFlow<FloatArray> = _bandGains.asStateFlow()
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    override val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()
    override val gainRangeDb: ClosedFloatingPointRange<Float> = -15f..15f

    /**
     * Called by [RadioShedMediaSessionService] every time ExoPlayer
     * reports a new audio session id. A value of 0 means "session
     * unassigned" — we tear the Equalizer down rather than try to
     * attach to an invalid session.
     */
    fun onAudioSessionIdChanged(sessionId: Int) {
        if (currentSessionId == sessionId) return
        currentSessionId = sessionId
        rebuildEqualizer()
    }

    private fun rebuildEqualizer() {
        runCatching { equalizer?.release() }
        equalizer = null
        _isAvailable.value = false
        if (currentSessionId == 0) return

        runCatching {
            val eq = Equalizer(EFFECT_PRIORITY, currentSessionId)
            // Probe device-reported band count + center freqs the first
            // time we successfully attach. Subsequent attaches keep the
            // same numbers (devices don't change EQ topology at runtime).
            val deviceBands = eq.numberOfBands.toInt()
            probedNumBands = deviceBands.coerceIn(1, MAX_BANDS)
            probedFreqs =
                (0 until probedNumBands).map { i ->
                    runCatching { eq.getCenterFreq(i.toShort()) / 1000 }.getOrDefault(0)
                }
            // Make sure persisted gains array length matches device band count.
            if (_bandGains.value.size != probedNumBands) {
                _bandGains.value = _bandGains.value.copyOf(probedNumBands)
            }
            applyGainsTo(eq, _bandGains.value)
            eq.enabled = _enabled.value
            equalizer = eq
            _isAvailable.value = true
        }
    }

    override fun setBand(
        index: Int,
        gainDb: Float,
    ) {
        if (index < 0 || index >= numBands) return
        val gains = _bandGains.value.copyOf()
        if (index >= gains.size) return
        gains[index] = gainDb.coerceIn(gainRangeDb.start, gainRangeDb.endInclusive)
        _bandGains.value = gains
        equalizer?.let { applyGainsTo(it, gains) }
        persistGains(gains)
    }

    override fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        runCatching { equalizer?.enabled = enabled }
        settings.setEqEnabled(enabled)
    }

    override fun release() {
        runCatching { equalizer?.release() }
        equalizer = null
        currentSessionId = 0
        _isAvailable.value = false
    }

    override fun applyPreset(preset: EqPreset) {
        if (preset == EqPreset.Custom) return
        // Pad / truncate the preset's 5 hard-coded gains to match the
        // device's actual band count.
        val gains =
            FloatArray(numBands) { i ->
                preset.gainsDb.getOrElse(i) { 0f }
            }
        _bandGains.value = gains
        equalizer?.let { applyGainsTo(it, gains) }
        persistGains(gains)
    }

    private fun applyGainsTo(
        eq: Equalizer,
        gains: FloatArray,
    ) {
        val range = runCatching { eq.bandLevelRange }.getOrNull() ?: return
        val minMb = range[0].toInt()
        val maxMb = range[1].toInt()
        val n = eq.numberOfBands.toInt().coerceAtMost(gains.size)
        for (i in 0 until n) {
            val mb = (gains[i] * 100f).toInt().coerceIn(minMb, maxMb)
            runCatching { eq.setBandLevel(i.toShort(), mb.toShort()) }
        }
    }

    private fun loadPersistedGains(): FloatArray {
        val arr = FloatArray(DEFAULT_NUM_BANDS)
        for (i in 0 until DEFAULT_NUM_BANDS) {
            arr[i] = settings.getEqBandGain(i)
        }
        return arr
    }

    private fun persistGains(gains: FloatArray) {
        for (i in gains.indices) settings.setEqBandGain(i, gains[i])
    }

    private companion object {
        /** Higher than ExoPlayer's own effect priority (default 0). */
        const val EFFECT_PRIORITY = 1
        const val DEFAULT_NUM_BANDS = 5
        const val MAX_BANDS = 10

        // Standard Android Equalizer center frequencies as a fallback
        // before we probe an actual device. Devices may report different
        // frequencies; probedFreqs replaces this once attached.
        val DEFAULT_BAND_FREQS_HZ = listOf(60, 230, 910, 3600, 14000)
    }
}
