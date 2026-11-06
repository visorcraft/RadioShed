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
import com.visorcraft.radioshed.audio.visualizer.SpectralHistory
import com.visorcraft.radioshed.audio.visualizer.VISUALIZER_BANDS

internal const val WATERFALL_ROWS = 24

internal fun DrawScope.drawWaterfall(
    history: SpectralHistory,
    theme: VisualizerTheme,
    heroBrush: Brush,
    intensity: Float,
    paths: Array<Path>,
) {
    val rows = WATERFALL_ROWS
    val horizon = size.height * 0.23f
    val foreground = size.height * 0.84f
    val rowSpacing = (foreground - horizon) / rows

    for (line in 0..12) {
        val bottomX = size.width * line / 12f
        val topX = center.x + (bottomX - center.x) * 0.56f
        drawLine(
            color = theme.accent.copy(alpha = 0.035f),
            start = Offset(topX, horizon),
            end = Offset(bottomX, foreground),
            strokeWidth = 1f,
        )
    }

    for (row in rows - 1 downTo 0) {
        val progress = row.toFloat() / (rows - 1)
        val y = foreground - progress * (foreground - horizon)
        val rowWidth = size.width * (1f - progress * 0.40f)
        val left = center.x - rowWidth / 2f
        val amplitude = size.height * 0.13f * intensity * (1f - progress * 0.32f)
        val crest = paths[row]
        crest.rewind()
        for (band in 0 until VISUALIZER_BANDS) {
            val value = history.valueAtNewestFirst(row * 2, band)
            val x = left + band.toFloat() / (VISUALIZER_BANDS - 1) * rowWidth
            val pointY = y - value * amplitude
            if (band == 0) crest.moveTo(x, pointY) else crest.lineTo(x, pointY)
        }
        val fill = paths[rows + row]
        fill.rewind()
        fill.addPath(crest)
        fill.lineTo(left + rowWidth, y + rowSpacing + 3f)
        fill.lineTo(left, y + rowSpacing + 3f)
        fill.close()
        drawPath(
            path = fill,
            brush =
                Brush.verticalGradient(
                    colors =
                        listOf(
                            theme.glow.copy(alpha = 0.10f + (1f - progress) * 0.14f),
                            theme.backgroundBottom.copy(alpha = 0.74f),
                        ),
                    startY = y - amplitude,
                    endY = y + rowSpacing,
                ),
        )
        drawPath(
            path = crest,
            brush = heroBrush,
            alpha = 0.16f + (1f - progress) * 0.58f,
            style = Stroke(width = 2f + (1f - progress) * 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            blendMode = BlendMode.Plus,
        )
        if (row < 8) {
            drawPath(
                path = crest,
                color = Color.White.copy(alpha = (8 - row) * 0.018f),
                style = Stroke(width = 1f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }

    drawLine(
        color = theme.glow.copy(alpha = 0.14f),
        start = Offset(0f, foreground),
        end = Offset(size.width, foreground),
        strokeWidth = 22f,
        blendMode = BlendMode.Plus,
    )
    drawLine(
        brush = Brush.horizontalGradient(listOf(Color.Transparent, theme.accent, Color.Transparent)),
        start = Offset(0f, foreground),
        end = Offset(size.width, foreground),
        strokeWidth = 2f,
    )
}
