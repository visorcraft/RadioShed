package com.visorcraft.radioshed.ui.player.visualizer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.visorcraft.radioshed.audio.visualizer.VISUALIZER_WAVEFORM_POINTS
import com.visorcraft.radioshed.audio.visualizer.VisualizerFrame
import com.visorcraft.radioshed.audio.visualizer.WaveformHistory

internal fun DrawScope.drawOscilloscope(
    frame: VisualizerFrame,
    history: WaveformHistory,
    theme: VisualizerTheme,
    heroBrush: Brush,
    intensity: Float,
    paths: VisualizerPathCache,
) {
    val middle = size.height / 2f
    val amplitude = size.height * (0.29f + frame.beat * 0.035f) * intensity

    for (line in 1..8) {
        val x = size.width * line / 9f
        drawLine(theme.accent.copy(alpha = 0.025f), Offset(x, size.height * 0.16f), Offset(x, size.height * 0.84f))
    }
    for (line in 1..4) {
        val y = size.height * line / 5f
        drawLine(theme.accent.copy(alpha = 0.035f), Offset(0f, y), Offset(size.width, y))
    }

    for (echo in 5 downTo 1) {
        val path = paths.layers[echo]
        path.rewind()
        val echoAmplitude = amplitude * (1f - echo * 0.055f)
        for (point in 0 until VISUALIZER_WAVEFORM_POINTS) {
            val x = point.toFloat() / (VISUALIZER_WAVEFORM_POINTS - 1) * size.width
            val y = middle - history.valueAtNewestFirst(echo, point) * echoAmplitude
            if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = theme.colorAt(0.45f + frame.mid * 0.35f).copy(alpha = (6 - echo) * 0.018f),
            style = Stroke(width = 5f + (6 - echo) * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            blendMode = BlendMode.Plus,
        )
    }

    val primary = paths.primary
    traceCurrentWave(primary, frame.waveform, size.width, middle, amplitude, mirror = false)
    val mirror = paths.secondary
    traceCurrentWave(mirror, frame.waveform, size.width, middle, amplitude * 0.82f, mirror = true)
    drawPath(
        path = mirror,
        color = theme.glow.copy(alpha = 0.10f),
        style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(
        path = primary,
        color = theme.glow.copy(alpha = 0.07f),
        style = Stroke(width = 38f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        blendMode = BlendMode.Plus,
    )
    drawPath(
        path = primary,
        color = theme.colorAt(0.62f).copy(alpha = 0.20f),
        style = Stroke(width = 17f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        blendMode = BlendMode.Plus,
    )
    drawPath(
        path = primary,
        brush = heroBrush,
        style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(
        path = primary,
        color = Color.White.copy(alpha = 0.72f),
        style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    for (point in 12 until VISUALIZER_WAVEFORM_POINTS step 24) {
        val x = point.toFloat() / (VISUALIZER_WAVEFORM_POINTS - 1) * size.width
        val y = middle - frame.waveform[point] * amplitude
        val energy = frame.waveform[point].coerceIn(-1f, 1f)
        drawCircle(
            color = theme.colorAt(0.55f + frame.treble * 0.40f).copy(alpha = 0.65f),
            radius = 3f + frame.treble * 5f,
            center = Offset(x, y),
            blendMode = BlendMode.Plus,
        )
        if (energy > 0.45f) drawCircle(Color.White.copy(alpha = 0.65f), radius = 1.5f, center = Offset(x, y))
    }
    drawLine(
        brush =
            Brush.horizontalGradient(
                listOf(Color.Transparent, theme.accent.copy(alpha = 0.30f), Color.Transparent),
            ),
        start = Offset(0f, middle),
        end = Offset(size.width, middle),
        strokeWidth = 1f,
    )
}

private fun traceCurrentWave(
    path: Path,
    waveform: FloatArray,
    width: Float,
    middle: Float,
    amplitude: Float,
    mirror: Boolean,
) {
    path.rewind()
    for (point in waveform.indices) {
        val x = point.toFloat() / waveform.lastIndex * width
        val direction = if (mirror) 1f else -1f
        val y = middle + waveform[point] * amplitude * direction
        if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
}
