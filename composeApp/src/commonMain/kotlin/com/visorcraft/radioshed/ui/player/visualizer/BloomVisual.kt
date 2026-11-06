package com.visorcraft.radioshed.ui.player.visualizer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.visorcraft.radioshed.audio.visualizer.ImpactRingPool
import com.visorcraft.radioshed.audio.visualizer.VisualizerFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal fun DrawScope.drawBloom(
    frame: VisualizerFrame,
    theme: VisualizerTheme,
    intensity: Float,
    timeMs: Long,
    impactRings: ImpactRingPool,
    orbitPath: Path,
) {
    val radius = size.minDimension * 0.14f
    val rotation = timeMs * 0.00007

    drawImpactRings(impactRings, timeMs, theme, radius * 1.2f, size.minDimension * 0.58f)
    repeat(3) { ring ->
        drawCircle(
            color = theme.colorAt(0.32f + ring * 0.18f).copy(alpha = 0.12f - ring * 0.02f),
            radius = radius * (1.35f + ring * 0.52f) * (1f + frame.beat * 0.035f),
            center = center,
            style = Stroke(width = if (ring == 0) 2f else 1f),
        )
    }

    for (pass in 0..1) {
        val back = pass == 0
        for (index in frame.spectrum.indices) {
            val angle = index.toDouble() / frame.spectrum.size * 2.0 * PI + rotation * if (back) -0.65 else 1.0
            val value = frame.spectrum[index]
            val length = value * size.minDimension * (if (back) 0.20f else 0.34f) * intensity
            val startRadius = radius * if (back) 0.90f else 1f
            val start =
                Offset((center.x + cos(angle) * startRadius).toFloat(), (center.y + sin(angle) * startRadius).toFloat())
            val endRadius = startRadius + length
            val end =
                Offset((center.x + cos(angle) * endRadius).toFloat(), (center.y + sin(angle) * endRadius).toFloat())
            val color = theme.colorAt(value)
            val alpha = if (back) 0.22f else 1f
            drawLine(
                color = color.copy(alpha = (0.04f + value * 0.12f) * alpha),
                start = start,
                end = end,
                strokeWidth = if (back) 13f else 22f,
                cap = StrokeCap.Round,
                blendMode = BlendMode.Plus,
            )
            drawLine(
                color = color.copy(alpha = (0.32f + value * 0.68f) * alpha),
                start = start,
                end = end,
                strokeWidth = if (back) 2.5f else 5f,
                cap = StrokeCap.Round,
            )
            if (!back && value > 0.56f) {
                drawCircle(
                    color = theme.colorAt((value + 0.24f).coerceAtMost(1f)).copy(alpha = value * 0.72f),
                    radius = 2f + value * 5f,
                    center = end,
                    blendMode = BlendMode.Plus,
                )
            }
        }
    }

    orbitPath.rewind()
    repeat(12) { index ->
        val angle = index / 12.0 * 2.0 * PI - rotation * 1.8
        val orbit = radius * 1.62f
        val point = Offset((center.x + cos(angle) * orbit).toFloat(), (center.y + sin(angle) * orbit).toFloat())
        if (index == 0) orbitPath.moveTo(point.x, point.y) else orbitPath.lineTo(point.x, point.y)
        drawCircle(
            color = theme.colorAt(0.42f + (index % 4) * 0.12f).copy(alpha = 0.46f),
            radius = if (index % 3 == 0) 4f else 2f,
            center = point,
        )
    }
    orbitPath.close()
    drawPath(orbitPath, theme.accent.copy(alpha = 0.06f), style = Stroke(1f))

    val coreRadius = radius * (0.68f + frame.bass * 0.22f + frame.beat * 0.06f)
    drawCircle(theme.glow.copy(alpha = 0.07f + frame.beat * 0.08f), radius = coreRadius * 1.85f, center = center)
    drawCircle(
        brush =
            Brush.radialGradient(
                colors = listOf(theme.colorAt(0.95f), theme.glow, theme.backgroundTop),
                center = center,
                radius = coreRadius,
            ),
        radius = coreRadius,
        center = center,
        blendMode = BlendMode.Plus,
    )
    drawCircle(Color.White.copy(alpha = 0.16f + frame.beat * 0.25f), radius = coreRadius * 0.24f, center = center)
    drawCircle(theme.accent.copy(alpha = 0.70f), radius = coreRadius, center = center, style = Stroke(2f))
}

internal fun DrawScope.drawImpactRings(
    rings: ImpactRingPool,
    timeMs: Long,
    theme: VisualizerTheme,
    baseRadius: Float,
    travel: Float,
) {
    rings.forEachActive(timeMs) { age, strength ->
        val progress = 1f - (1f - age) * (1f - age) * (1f - age)
        val radius = baseRadius + progress * travel
        val alpha = (1f - age) * (1f - age) * strength
        drawCircle(
            color = theme.glow.copy(alpha = alpha * 0.12f),
            radius = radius,
            center = center,
            style = Stroke(width = 18f * (1f - age) + 2f),
            blendMode = BlendMode.Plus,
        )
        drawCircle(
            color = theme.accent.copy(alpha = alpha * 0.65f),
            radius = radius,
            center = center,
            style = Stroke(width = 2.5f * (1f - age) + 0.8f),
        )
    }
}
