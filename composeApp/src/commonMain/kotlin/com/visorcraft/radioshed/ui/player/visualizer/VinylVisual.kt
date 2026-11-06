package com.visorcraft.radioshed.ui.player.visualizer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.visorcraft.radioshed.audio.visualizer.VisualizerFrame
import com.visorcraft.radioshed.audio.visualizer.vinylProgressSweep
import com.visorcraft.radioshed.audio.visualizer.vinylRotationDegrees
import com.visorcraft.radioshed.audio.visualizer.vinylTonearmAngle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal fun DrawScope.drawVinyl(
    frame: VisualizerFrame,
    theme: VisualizerTheme,
    intensity: Float,
    timeMs: Long,
    progress: Float,
    reduceMotion: Boolean,
) {
    val radius = size.minDimension * 0.34f
    val rotation = vinylRotationDegrees(timeMs, reduceMotion) * PI / 180.0
    val clampedProgress = progress.coerceIn(0f, 1f)

    drawCircle(
        color = theme.glow.copy(alpha = 0.08f + frame.bass * 0.09f),
        radius = radius * (1.16f + frame.beat * 0.025f),
        center = center,
        blendMode = BlendMode.Plus,
    )
    drawCircle(
        brush =
            Brush.radialGradient(
                colors = listOf(Color(0xFF202029), Color(0xFF08080D), theme.backgroundBottom),
                center = center,
                radius = radius,
            ),
        radius = radius,
        center = center,
    )

    for (groove in 0 until 20) {
        val grooveProgress = groove / 19f
        val band = (grooveProgress * (frame.spectrum.lastIndex)).toInt().coerceIn(0, frame.spectrum.lastIndex)
        val energy = frame.spectrum[band] * intensity
        val grooveRadius = radius * (0.43f + grooveProgress * 0.52f)
        drawCircle(
            color = theme.colorAt(0.28f + energy.coerceIn(0f, 1f) * 0.55f).copy(alpha = 0.10f + energy * 0.28f),
            radius = grooveRadius,
            center = center,
            style = Stroke(width = 1f + energy * 1.6f),
        )
    }

    drawArc(
        color = Color.White.copy(alpha = 0.055f),
        startAngle = 202f,
        sweepAngle = 64f,
        useCenter = false,
        topLeft = Offset(center.x - radius * 0.94f, center.y - radius * 0.94f),
        size = Size(radius * 1.88f, radius * 1.88f),
        style = Stroke(width = radius * 0.11f, cap = StrokeCap.Round),
    )
    drawArc(
        color = theme.accent.copy(alpha = 0.08f),
        startAngle = 24f,
        sweepAngle = 42f,
        useCenter = false,
        topLeft = Offset(center.x - radius * 0.94f, center.y - radius * 0.94f),
        size = Size(radius * 1.88f, radius * 1.88f),
        style = Stroke(width = radius * 0.07f, cap = StrokeCap.Round),
    )

    val labelRadius = radius * 0.36f
    drawCircle(
        brush =
            Brush.radialGradient(
                colors = listOf(theme.colorAt(0.92f), theme.glow, theme.backgroundTop),
                center = center,
                radius = labelRadius,
            ),
        radius = labelRadius,
        center = center,
    )
    drawCircle(theme.accent.copy(alpha = 0.72f), radius = labelRadius, center = center, style = Stroke(2f))
    val marker =
        Offset(
            (center.x + cos(rotation) * labelRadius * 0.76f).toFloat(),
            (center.y + sin(rotation) * labelRadius * 0.76f).toFloat(),
        )
    drawCircle(Color.White.copy(alpha = 0.72f), radius = 3f + frame.beat * 2f, center = marker)
    drawCircle(theme.backgroundBottom, radius = 7f, center = center)
    drawCircle(Color.White.copy(alpha = 0.72f), radius = 2.5f, center = center)

    drawArc(
        color = theme.glow.copy(alpha = 0.16f + frame.beat * 0.10f),
        startAngle = -90f,
        sweepAngle = vinylProgressSweep(clampedProgress),
        useCenter = false,
        topLeft = Offset(center.x - radius * 1.045f, center.y - radius * 1.045f),
        size = Size(radius * 2.09f, radius * 2.09f),
        style = Stroke(width = 14f, cap = StrokeCap.Round),
        blendMode = BlendMode.Plus,
    )
    drawArc(
        color = theme.accent,
        startAngle = -90f,
        sweepAngle = vinylProgressSweep(clampedProgress),
        useCenter = false,
        topLeft = Offset(center.x - radius * 1.045f, center.y - radius * 1.045f),
        size = Size(radius * 2.09f, radius * 2.09f),
        style = Stroke(width = 3f, cap = StrokeCap.Round),
    )

    val pivot = Offset(center.x + radius * 0.84f, center.y - radius * 0.90f)
    val angle = vinylTonearmAngle(clampedProgress) * PI / 180.0
    val length = radius * 0.94f
    val stylus =
        Offset(
            (pivot.x + cos(angle) * length).toFloat(),
            (pivot.y + sin(angle) * length).toFloat(),
        )
    drawCircle(Color(0xFF1B1B22), radius = radius * 0.09f, center = pivot)
    drawCircle(theme.accent.copy(alpha = 0.42f), radius = radius * 0.09f, center = pivot, style = Stroke(2f))
    drawLine(Color.Black.copy(alpha = 0.65f), pivot + Offset(5f, 6f), stylus + Offset(5f, 6f), strokeWidth = 10f)
    drawLine(Color.White.copy(alpha = 0.58f), pivot, stylus, strokeWidth = 5f, cap = StrokeCap.Round)
    drawLine(theme.accent.copy(alpha = 0.32f), pivot, stylus, strokeWidth = 2f, cap = StrokeCap.Round)
    drawCircle(theme.glow.copy(alpha = 0.30f + frame.beat * 0.35f), radius = 13f + frame.beat * 7f, center = stylus)
    drawCircle(theme.colorAt(0.96f), radius = 5f, center = stylus)
}
