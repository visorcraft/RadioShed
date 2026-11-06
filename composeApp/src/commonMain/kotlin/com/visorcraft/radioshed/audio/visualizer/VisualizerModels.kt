package com.visorcraft.radioshed.audio.visualizer

const val VISUALIZER_BANDS = 48
const val VISUALIZER_WAVEFORM_POINTS = 192

enum class VisualizerMode(
    val label: String,
) {
    Spectrum("Prism Spectrum"),
    Oscilloscope("Neon Oscilloscope"),
    Bloom("Radial Bloom"),
    Aurora("Aurora Ribbons"),
    Ripples("Bass Ripples"),
    Starfield("Hyperspace"),
    Waterfall("Spectral Waterfall"),
    VuMeters("Analog VU"),
    Vinyl("Vinyl Turntable"),
    ;

    fun next(): VisualizerMode = entries[(ordinal + 1) % entries.size]

    fun previous(): VisualizerMode = entries[(ordinal - 1 + entries.size) % entries.size]

    companion object {
        fun fromStorage(value: String?): VisualizerMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Spectrum
    }
}

enum class VisualizerPalette(
    val label: String,
) {
    Prism("Prism"),
    Synthwave("Synthwave"),
    Ember("Ember"),
    Ocean("Ocean"),
    Mono("Monochrome"),
    ;

    companion object {
        fun fromStorage(value: String?): VisualizerPalette =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Prism
    }
}

/** Normalized render input. Spectrum/peak values are 0..1, waveform is -1..1. */
class VisualizerFrame(
    val spectrum: FloatArray,
    val waveform: FloatArray,
    val peaks: FloatArray,
    val bass: Float,
    val mid: Float,
    val treble: Float,
    val beat: Float,
    val live: Boolean,
) {
    companion object {
        fun empty(live: Boolean = false): VisualizerFrame =
            VisualizerFrame(
                spectrum = FloatArray(VISUALIZER_BANDS),
                waveform = FloatArray(VISUALIZER_WAVEFORM_POINTS),
                peaks = FloatArray(VISUALIZER_BANDS),
                bass = 0f,
                mid = 0f,
                treble = 0f,
                beat = 0f,
                live = live,
            )
    }
}
