package com.visorcraft.radioshed.ui.player.visualizer

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import com.visorcraft.radioshed.audio.visualizer.VisualizerFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal fun DrawScope.drawVuMeters(
    levels: FloatArray,
    frame: VisualizerFrame,
    theme: VisualizerTheme,
    intensity: Float,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    brandStyle: TextStyle,
) {
    for (meter in 0 until 2) {
        val level = (levels.getOrElse(meter) { 0f } * intensity).coerceIn(0f, 1f)
        val pivot = Offset(size.width * (0.27f + meter * 0.46f), size.height * 0.73f)
        val radius = vuMeterRadius(width = size.width, height = size.height)
        val panelTopLeft = Offset(pivot.x - radius * 1.18f, pivot.y - radius * 1.18f)
        val panelSize = Size(radius * 2.36f, radius * 1.58f)
        drawRoundRect(
            brush =
                Brush.verticalGradient(
                    colors = listOf(Color.White.copy(alpha = 0.055f), theme.backgroundBottom.copy(alpha = 0.88f)),
                    startY = panelTopLeft.y,
                    endY = panelTopLeft.y + panelSize.height,
                ),
            topLeft = panelTopLeft,
            size = panelSize,
            cornerRadius = CornerRadius(28f, 28f),
        )
        drawRoundRect(
            color = theme.accent.copy(alpha = 0.13f),
            topLeft = panelTopLeft,
            size = panelSize,
            cornerRadius = CornerRadius(28f, 28f),
            style = Stroke(width = 1.5f),
        )

        drawArc(
            color = theme.glow.copy(alpha = 0.06f + level * 0.08f),
            startAngle = 210f,
            sweepAngle = 120f,
            useCenter = false,
            topLeft = Offset(pivot.x - radius, pivot.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = 28f),
            blendMode = BlendMode.Plus,
        )
        for (tick in 0..60) {
            val angle = (210.0 + tick * 2.0) * PI / 180.0
            val major = tick % 10 == 0
            val medium = tick % 5 == 0
            val inner =
                radius *
                    when {
                        major -> 0.72f
                        medium -> 0.78f
                        else -> 0.83f
                    }
            val active = tick / 60f <= level
            val color = theme.colorAt(tick / 60f)
            drawLine(
                color =
                    color.copy(
                        alpha =
                            if (active) {
                                0.94f
                            } else if (major) {
                                0.32f
                            } else {
                                0.15f
                            },
                    ),
                start = Offset((pivot.x + cos(angle) * inner).toFloat(), (pivot.y + sin(angle) * inner).toFloat()),
                end = Offset((pivot.x + cos(angle) * radius).toFloat(), (pivot.y + sin(angle) * radius).toFloat()),
                strokeWidth = if (major) 4f else 2f,
                cap = StrokeCap.Round,
                blendMode = if (active) BlendMode.Plus else BlendMode.SrcOver,
            )
        }

        val needleAngle = (210.0 + level * 120.0) * PI / 180.0
        val needleEnd =
            Offset(
                (pivot.x + cos(needleAngle) * radius * 0.83f).toFloat(),
                (pivot.y + sin(needleAngle) * radius * 0.83f).toFloat(),
            )
        drawLine(Color.Black.copy(alpha = 0.65f), pivot + Offset(5f, 7f), needleEnd + Offset(5f, 7f), strokeWidth = 8f)
        drawLine(
            color = theme.glow.copy(alpha = 0.24f),
            start = pivot,
            end = needleEnd,
            strokeWidth = 17f,
            cap = StrokeCap.Round,
            blendMode = BlendMode.Plus,
        )
        drawLine(theme.colorAt(0.88f), pivot, needleEnd, strokeWidth = 4f, cap = StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.78f), pivot, needleEnd, strokeWidth = 1.2f, cap = StrokeCap.Round)
        drawCircle(theme.backgroundBottom, radius = 17f, center = pivot)
        drawCircle(theme.colorAt(0.90f), radius = 10f, center = pivot)
        drawCircle(Color.White.copy(alpha = 0.72f), radius = 3f, center = pivot)

        drawText(
            textMeasurer = textMeasurer,
            text = if (meter == 0) "LEFT" else "RIGHT",
            topLeft = Offset(pivot.x - 27f, pivot.y + radius * 0.20f),
            style = labelStyle,
        )
        for (corner in 0 until 4) {
            val x = panelTopLeft.x + if (corner % 2 == 0) 18f else panelSize.width - 18f
            val y = panelTopLeft.y + if (corner < 2) 18f else panelSize.height - 18f
            drawCircle(Color.White.copy(alpha = 0.20f), radius = 3f, center = Offset(x, y))
            drawCircle(Color.Black.copy(alpha = 0.55f), radius = 1.2f, center = Offset(x, y))
        }
    }

    if (size.width > 1_000f) {
        drawText(
            textMeasurer = textMeasurer,
            text = "RADIOSHED",
            topLeft = Offset(center.x - 76f, size.height * 0.43f),
            style = brandStyle,
        )
        drawText(
            textMeasurer = textMeasurer,
            text = "STEREO",
            topLeft = Offset(center.x - 48f, size.height * 0.49f),
            style = labelStyle.copy(color = theme.accent.copy(alpha = 0.42f), fontSize = labelStyle.fontSize * 0.72f),
        )
        drawCircle(
            color = theme.colorAt(0.82f).copy(alpha = 0.32f + frame.beat * 0.38f),
            radius = 4f + frame.beat * 2f,
            center = Offset(center.x, size.height * 0.57f),
            blendMode = BlendMode.Plus,
        )
    }
}

internal fun vuMeterRadius(
    width: Float,
    height: Float,
): Float = minOf(minOf(width, height) * 0.44f, width * 0.185f)
