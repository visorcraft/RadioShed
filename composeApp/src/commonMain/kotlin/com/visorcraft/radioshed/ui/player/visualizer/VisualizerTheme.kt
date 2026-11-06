package com.visorcraft.radioshed.ui.player.visualizer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.visorcraft.radioshed.audio.visualizer.VisualizerPalette
import kotlin.math.sqrt

internal class VisualizerTheme(
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val ramp: List<Color>,
    val accent: Color,
    val glow: Color,
) {
    fun colorAt(energy: Float): Color {
        val position = sqrt(energy.coerceIn(0f, 1f)) * ramp.lastIndex
        val index = position.toInt().coerceIn(0, ramp.lastIndex)
        val next = (index + 1).coerceAtMost(ramp.lastIndex)
        return lerp(ramp[index], ramp[next], position - index)
    }
}

internal fun visualizerTheme(palette: VisualizerPalette): VisualizerTheme =
    when (palette) {
        VisualizerPalette.Prism ->
            VisualizerTheme(
                backgroundTop = Color(0xFF09051D),
                backgroundBottom = Color(0xFF010107),
                ramp =
                    listOf(
                        Color(0xFF4527A0),
                        Color(0xFF356DFF),
                        Color(0xFF00E5FF),
                        Color(0xFFFF4D8D),
                        Color(0xFFFFF3C4),
                    ),
                accent = Color(0xFF6EF2FF),
                glow = Color(0xFF745CFF),
            )
        VisualizerPalette.Synthwave ->
            VisualizerTheme(
                backgroundTop = Color(0xFF180724),
                backgroundBottom = Color(0xFF030108),
                ramp =
                    listOf(
                        Color(0xFF3D1766),
                        Color(0xFF7B2CFF),
                        Color(0xFFFF3CAC),
                        Color(0xFF00F5D4),
                        Color(0xFFF8F7FF),
                    ),
                accent = Color(0xFFFF67C8),
                glow = Color(0xFFB04CFF),
            )
        VisualizerPalette.Ember ->
            VisualizerTheme(
                backgroundTop = Color(0xFF1D0804),
                backgroundBottom = Color(0xFF050201),
                ramp =
                    listOf(
                        Color(0xFF64180E),
                        Color(0xFFC62B19),
                        Color(0xFFFF6B1A),
                        Color(0xFFFFC94A),
                        Color(0xFFFFF4D6),
                    ),
                accent = Color(0xFFFFB347),
                glow = Color(0xFFFF4D1C),
            )
        VisualizerPalette.Ocean ->
            VisualizerTheme(
                backgroundTop = Color(0xFF031526),
                backgroundBottom = Color(0xFF010609),
                ramp =
                    listOf(
                        Color(0xFF12376B),
                        Color(0xFF075FB5),
                        Color(0xFF00A6D6),
                        Color(0xFF51F6D3),
                        Color(0xFFE8FFFA),
                    ),
                accent = Color(0xFF62F4DC),
                glow = Color(0xFF008CFF),
            )
        VisualizerPalette.Mono ->
            VisualizerTheme(
                backgroundTop = Color(0xFF111115),
                backgroundBottom = Color(0xFF020203),
                ramp =
                    listOf(
                        Color(0xFF303037),
                        Color(0xFF676772),
                        Color(0xFFA7A7B2),
                        Color(0xFFE4E4EA),
                        Color(0xFFFFFFFF),
                    ),
                accent = Color(0xFFF5F5F7),
                glow = Color(0xFFD8D8E0),
            )
    }
