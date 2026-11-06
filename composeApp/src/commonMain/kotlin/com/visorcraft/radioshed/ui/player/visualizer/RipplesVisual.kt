package com.visorcraft.radioshed.ui.player.visualizer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.visorcraft.radioshed.audio.visualizer.ImpactRingPool
import com.visorcraft.radioshed.audio.visualizer.VisualizerFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal fun DrawScope.drawRipples(
    frame: VisualizerFrame,
    theme: VisualizerTheme,
    heroBrush: Brush,
    intensity: Float,
    timeMs: Long,
    impactRings: ImpactRingPool,
    primaryPath: Path,
    secondaryPath: Path,
) {
    val baseRadius = size.minDimension * 0.15f
    drawImpactRings(impactRings, timeMs, theme, baseRadius, size.minDimension * 0.54f)

    for (ring in 0 until 4) {
        val phase = visualizerFract(timeMs * 0.000045 + ring / 4.0)
        val radius = baseRadius + phase * size.minDimension * 0.48f * intensity
        drawCircle(
            color = theme.colorAt(0.30f + phase * 0.35f).copy(alpha = (1f - phase) * 0.16f),
            radius = radius,
            center = center,
            style = Stroke(width = 1.5f),
        )
    }

    traceRadialWave(primaryPath, frame, size.minDimension * (0.16f + frame.beat * 0.012f), intensity, 0.0)
    traceRadialWave(secondaryPath, frame, size.minDimension * 0.115f, intensity * 0.62f, PI)
    drawPath(
        path = primaryPath,
        color = theme.glow.copy(alpha = 0.08f + frame.bass * 0.07f),
        style = Stroke(width = 32f),
        blendMode = BlendMode.Plus,
    )
    drawPath(primaryPath, heroBrush, style = Stroke(width = 4f))
    drawPath(primaryPath, Color.White.copy(alpha = 0.36f), style = Stroke(width = 1.2f))
    drawPath(secondaryPath, theme.accent.copy(alpha = 0.24f), style = Stroke(width = 2f))

    repeat(24) { index ->
        val angle = index / 24.0 * 2.0 * PI + timeMs * 0.00010
        val travel = visualizerFract(index * 0.6180339f + timeMs * 0.00008 * (0.4f + frame.bass))
        val radius = baseRadius * 1.3f + travel * size.minDimension * 0.34f
        val point = Offset((center.x + cos(angle) * radius).toFloat(), (center.y + sin(angle) * radius).toFloat())
        drawCircle(
            color = theme.colorAt(0.40f + travel * 0.50f).copy(alpha = (1f - travel) * 0.55f),
            radius = 1.5f + (1f - travel) * 3f,
            center = point,
            blendMode = BlendMode.Plus,
        )
    }

    val coreRadius = size.minDimension * (0.075f + frame.bass * 0.025f)
    drawCircle(
        brush =
            Brush.radialGradient(
                colors = listOf(theme.colorAt(0.96f), theme.glow.copy(alpha = 0.48f), Color.Transparent),
                center = center,
                radius = coreRadius * 1.8f,
            ),
        radius = coreRadius * 1.8f,
        center = center,
        blendMode = BlendMode.Plus,
    )
    drawCircle(theme.accent.copy(alpha = 0.65f), radius = coreRadius * 0.44f, center = center, style = Stroke(2f))
}

private fun DrawScope.traceRadialWave(
    path: Path,
    frame: VisualizerFrame,
    baseRadius: Float,
    intensity: Float,
    phase: Double,
) {
    path.rewind()
    val points = 128
    for (point in 0 until points) {
        val position = point.toFloat() / points
        val angle = position.toDouble() * 2.0 * PI + phase
        val value = sampleCircularSpectrum(frame.spectrum, position)
        val radius = baseRadius + value * size.minDimension * 0.17f * intensity
        val x = (center.x + cos(angle) * radius).toFloat()
        val y = (center.y + sin(angle) * radius).toFloat()
        if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}

internal fun sampleCircularSpectrum(
    values: FloatArray,
    position: Float,
): Float {
    if (values.isEmpty()) return 0f
    val scaled = visualizerFract(position) * values.size
    val left = scaled.toInt() % values.size
    val right = (left + 1) % values.size
    val fraction = scaled - left
    return values[left] * (1f - fraction) + values[right] * fraction
}
