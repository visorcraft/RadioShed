package com.visorcraft.radioshed.ui.player.visualizer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.visorcraft.radioshed.audio.visualizer.MusicStructure
import com.visorcraft.radioshed.audio.visualizer.VisualizerFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal fun DrawScope.drawStarfield(
    frame: VisualizerFrame,
    theme: VisualizerTheme,
    intensity: Float,
    timeMs: Long,
    reduceMotion: Boolean,
    structure: MusicStructure,
) {
    val t =
        timeMs * 0.000055 * (0.55f + frame.bass * 1.8f) * intensity *
            (1f + structure.tension * 1.5f + structure.impact * 2f)
    val count = if (reduceMotion) 48 else 144

    drawCircle(
        brush =
            Brush.radialGradient(
                colors = listOf(theme.glow.copy(alpha = 0.12f + frame.bass * 0.08f), Color.Transparent),
                center = Offset(size.width * 0.32f, size.height * 0.38f),
                radius = size.minDimension * 0.52f,
            ),
        center = Offset(size.width * 0.32f, size.height * 0.38f),
        radius = size.minDimension * 0.52f,
    )
    drawCircle(
        brush =
            Brush.radialGradient(
                colors = listOf(theme.accent.copy(alpha = 0.07f + frame.mid * 0.08f), Color.Transparent),
                center = Offset(size.width * 0.70f, size.height * 0.62f),
                radius = size.minDimension * 0.46f,
            ),
        center = Offset(size.width * 0.70f, size.height * 0.62f),
        radius = size.minDimension * 0.46f,
    )

    if (!reduceMotion) {
        for (ring in 0 until 8) {
            val depth = visualizerFract(ring / 8.0 + t * 0.55)
            drawCircle(
                color = theme.colorAt(0.30f + depth * 0.45f).copy(alpha = depth * 0.08f),
                radius = size.minDimension * depth * 0.68f,
                center = center,
                style = Stroke(width = 1f + depth),
            )
        }
    }

    for (index in 0 until count) {
        val depth = visualizerFract(index * 0.6180339f + t)
        val perspective = depth * depth
        val xSeed = visualizerNoise(index * 2 + 3)
        val ySeed = visualizerNoise(index * 2 + 79)
        val spread = 1.75f
        val x = center.x + (xSeed - 0.5f) * size.width * perspective * spread
        val y = center.y + (ySeed - 0.5f) * size.height * perspective * spread
        val trail = if (reduceMotion) 0.006f else 0.035f + frame.beat * 0.065f
        val previousDepth = (depth - trail).coerceAtLeast(0f)
        val previousPerspective = previousDepth * previousDepth
        val previousX = center.x + (xSeed - 0.5f) * size.width * previousPerspective * spread
        val previousY = center.y + (ySeed - 0.5f) * size.height * previousPerspective * spread
        val color = theme.colorAt(0.32f + depth * 0.65f)
        drawLine(
            color = color.copy(alpha = (0.16f + depth * 0.84f) * (0.65f + frame.treble * 0.35f)),
            start = Offset(previousX, previousY),
            end = Offset(x, y),
            strokeWidth = 0.8f + perspective * 5f,
            cap = StrokeCap.Round,
            blendMode = BlendMode.Plus,
        )
        if (index % 9 == 0 && depth > 0.45f) {
            drawCircle(Color.White.copy(alpha = depth * 0.72f), radius = 0.8f + perspective * 3f, center = Offset(x, y))
        }
    }

    if (!reduceMotion && frame.beat > 0.18f) {
        val progress = 1f - frame.beat
        repeat(18) { index ->
            val angle = index / 18.0 * 2.0 * PI + index * 0.37
            val start = size.minDimension * (0.035f + progress * 0.20f)
            val length = size.minDimension * frame.beat * 0.045f
            val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
            drawLine(
                color = theme.colorAt(0.70f + (index % 3) * 0.10f).copy(alpha = frame.beat * 0.65f),
                start = center + direction * start,
                end = center + direction * (start + length),
                strokeWidth = 2f,
                cap = StrokeCap.Round,
                blendMode = BlendMode.Plus,
            )
        }
    }

    val coreRadius = size.minDimension * (0.028f + frame.beat * 0.025f)
    drawCircle(theme.glow.copy(alpha = 0.10f + frame.beat * 0.18f), radius = coreRadius * 3.2f, center = center)
    drawCircle(
        theme.colorAt(0.94f).copy(alpha = 0.72f),
        radius = coreRadius,
        center = center,
        blendMode = BlendMode.Plus,
    )
    drawLine(
        color = Color.White.copy(alpha = 0.25f + frame.beat * 0.45f),
        start = Offset(center.x - coreRadius * 2.2f, center.y),
        end = Offset(center.x + coreRadius * 2.2f, center.y),
        strokeWidth = 1.5f,
    )
    drawLine(
        color = Color.White.copy(alpha = 0.25f + frame.beat * 0.45f),
        start = Offset(center.x, center.y - coreRadius * 2.2f),
        end = Offset(center.x, center.y + coreRadius * 2.2f),
        strokeWidth = 1.5f,
    )
}
