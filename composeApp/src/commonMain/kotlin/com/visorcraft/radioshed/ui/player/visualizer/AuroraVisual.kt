package com.visorcraft.radioshed.ui.player.visualizer

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.visorcraft.radioshed.audio.visualizer.MusicStructure
import com.visorcraft.radioshed.audio.visualizer.VISUALIZER_BANDS
import com.visorcraft.radioshed.audio.visualizer.VisualizerFrame
import kotlin.math.PI
import kotlin.math.sin

internal fun DrawScope.drawAurora(
    frame: VisualizerFrame,
    theme: VisualizerTheme,
    intensity: Float,
    timeMs: Long,
    structure: MusicStructure,
    paths: Array<Path>,
) {
    val t = timeMs * 0.00038
    for (layer in 5 downTo 0) {
        val fillPath = paths[layer * 2]
        val crestPath = paths[layer * 2 + 1]
        fillPath.rewind()
        crestPath.rewind()
        val baseline = size.height * (0.24f + layer * 0.095f)
        val amplitude =
            size.height * (0.07f + layer * 0.008f) * intensity *
                (1f + structure.tension * 0.25f + structure.impact * 0.15f)
        val points = 64
        var previousX = 0f
        var previousY = baseline
        for (point in 0..points) {
            val x = point.toFloat() / points
            val band = (x * (VISUALIZER_BANDS - 1)).toInt().coerceIn(0, VISUALIZER_BANDS - 1)
            val wave =
                sin(x * (2.0 + layer * 0.32) * 2.0 * PI + t * (0.7 + layer * 0.11) + layer * 0.8) * 0.62 +
                    sin(x * 5.2 * PI - t * 0.55 + layer * 1.6) * 0.26
            val audioLift = frame.spectrum[band] * size.height * 0.095f * intensity
            val y = baseline + (wave * amplitude + audioLift).toFloat()
            val drawX = x * size.width
            if (point == 0) {
                crestPath.moveTo(drawX, y)
            } else {
                val middleX = (previousX + drawX) / 2f
                val middleY = (previousY + y) / 2f
                crestPath.quadraticTo(previousX, previousY, middleX, middleY)
                if (point == points) crestPath.quadraticTo(previousX, previousY, drawX, y)
            }
            previousX = drawX
            previousY = y
        }
        fillPath.addPath(crestPath)
        fillPath.lineTo(size.width, size.height)
        fillPath.lineTo(0f, size.height)
        fillPath.close()
        val color = theme.colorAt(0.32f + layer * 0.095f)
        drawPath(
            path = fillPath,
            brush =
                Brush.verticalGradient(
                    colors = listOf(color.copy(alpha = 0.12f + layer * 0.018f), Color.Transparent),
                    startY = baseline - amplitude,
                    endY = size.height * 0.94f,
                ),
        )
        drawPath(
            path = crestPath,
            color = color.copy(alpha = 0.055f),
            style = Stroke(width = 28f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            blendMode = BlendMode.Plus,
        )
        drawPath(
            path = crestPath,
            color = color.copy(alpha = 0.32f + layer * 0.045f),
            style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            blendMode = BlendMode.Plus,
        )
        drawPath(
            path = crestPath,
            color = theme.colorAt(0.72f + layer * 0.04f).copy(alpha = 0.52f),
            style = Stroke(width = 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
