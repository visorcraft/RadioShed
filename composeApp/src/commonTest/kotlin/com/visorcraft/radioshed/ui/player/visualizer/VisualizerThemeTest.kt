package com.visorcraft.radioshed.ui.player.visualizer

import com.visorcraft.radioshed.audio.visualizer.VisualizerPalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class VisualizerThemeTest {
    @Test
    fun energyRampClampsToItsEndColors() {
        val theme = visualizerTheme(VisualizerPalette.Prism)

        assertEquals(theme.ramp.first(), theme.colorAt(-1f))
        assertEquals(theme.ramp.last(), theme.colorAt(2f))
    }

    @Test
    fun energyRampUsesLuminanceInsteadOfFrequencyRainbows() {
        val theme = visualizerTheme(VisualizerPalette.Ocean)

        assertNotEquals(theme.colorAt(0.2f), theme.colorAt(0.8f))
    }

    @Test
    fun eachPaletteHasItsOwnAtmosphere() {
        val backgrounds = VisualizerPalette.entries.map { visualizerTheme(it).backgroundTop }

        assertEquals(backgrounds.size, backgrounds.toSet().size)
    }
}
