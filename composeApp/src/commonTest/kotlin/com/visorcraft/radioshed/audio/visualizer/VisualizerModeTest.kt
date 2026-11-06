package com.visorcraft.radioshed.audio.visualizer

import kotlin.test.Test
import kotlin.test.assertEquals

class VisualizerModeTest {
    @Test
    fun unknownStoredModeFallsBackToSpectrum() {
        assertEquals(VisualizerMode.Spectrum, VisualizerMode.fromStorage("from-the-future"))
    }

    @Test
    fun modeCycleWrapsInBothDirections() {
        val first = VisualizerMode.entries.first()
        val last = VisualizerMode.entries.last()

        assertEquals(first, last.next())
        assertEquals(last, first.previous())
    }

    @Test
    fun vinylIsAvailableAfterAnalogMeters() {
        assertEquals(VisualizerMode.Vinyl, VisualizerMode.VuMeters.next())
        assertEquals(9, VisualizerMode.entries.size)
    }

    @Test
    fun paletteStorageFallsBackToPrism() {
        assertEquals(VisualizerPalette.Prism, VisualizerPalette.fromStorage("unknown"))
    }
}
