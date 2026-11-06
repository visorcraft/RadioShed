package com.visorcraft.radioshed.ui.player.visualizer

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.visorcraft.radioshed.audio.visualizer.VisualizerFrame

internal fun DrawScope.drawSpectrum(
    frame: VisualizerFrame,
    theme: VisualizerTheme,
    intensity: Float,
) {
    val step = size.width / frame.spectrum.size
    val barWidth = step * 0.58f
    val corner = CornerRadius(barWidth / 2f, barWidth / 2f)
    val baseY = size.height * 0.78f
    val maxHeight = size.height * 0.59f * intensity

    drawCircle(
        brush =
            Brush.radialGradient(
                colors = listOf(theme.glow.copy(alpha = 0.10f + frame.beat * 0.10f), Color.Transparent),
                center = Offset(center.x, baseY),
                radius = size.width * 0.42f,
            ),
        center = Offset(center.x, baseY),
        radius = size.width * 0.42f,
    )

    for (index in frame.spectrum.indices) {
        val value = frame.spectrum[index].coerceIn(0f, 1f)
        val height = 3f + value * maxHeight
        val x = index * step + (step - barWidth) / 2f
        val color = theme.colorAt(value)
        val hotColor = theme.colorAt((value + 0.30f).coerceAtMost(1f))
        drawRoundRect(
            color = color.copy(alpha = 0.05f + value * 0.12f),
            topLeft = Offset(x - step * 0.14f, baseY - height - step * 0.22f),
            size = Size(barWidth + step * 0.28f, height + step * 0.28f),
            cornerRadius = corner,
            blendMode = BlendMode.Plus,
        )
        drawRoundRect(
            brush =
                Brush.verticalGradient(
                    colors = listOf(hotColor, color, theme.colorAt(value * 0.28f).copy(alpha = 0.48f)),
                    startY = baseY - height,
                    endY = baseY,
                ),
            topLeft = Offset(x, baseY - height),
            size = Size(barWidth, height),
            cornerRadius = corner,
        )
        if (value > 0.12f) {
            drawLine(
                color = Color.White.copy(alpha = value * 0.28f),
                start = Offset(x + barWidth * 0.28f, baseY - height + barWidth * 0.55f),
                end = Offset(x + barWidth * 0.28f, baseY - height * 0.12f),
                strokeWidth = (barWidth * 0.10f).coerceAtLeast(1f),
                cap = StrokeCap.Round,
            )
        }
        val peakY = baseY - 3f - frame.peaks[index] * maxHeight
        drawLine(
            color = hotColor.copy(alpha = 0.14f),
            start = Offset(x - 2f, peakY),
            end = Offset(x + barWidth + 2f, peakY),
            strokeWidth = 9f,
            cap = StrokeCap.Round,
            blendMode = BlendMode.Plus,
        )
        drawLine(
            color = hotColor.copy(alpha = 0.94f),
            start = Offset(x, peakY),
            end = Offset(x + barWidth, peakY),
            strokeWidth = 3f,
            cap = StrokeCap.Round,
        )
        if (height > 8f) {
            drawRoundRect(
                brush =
                    Brush.verticalGradient(
                        colors = listOf(color.copy(alpha = 0.22f), Color.Transparent),
                        startY = baseY + 8f,
                        endY = baseY + 8f + height * 0.24f,
                    ),
                topLeft = Offset(x, baseY + 8f),
                size = Size(barWidth, height * 0.24f),
                cornerRadius = corner,
            )
        }
    }

    drawLine(
        color = theme.glow.copy(alpha = 0.10f + frame.beat * 0.16f),
        start = Offset(0f, baseY),
        end = Offset(size.width, baseY),
        strokeWidth = 18f + frame.beat * 12f,
        blendMode = BlendMode.Plus,
    )
    drawLine(
        brush = Brush.horizontalGradient(listOf(Color.Transparent, theme.accent, Color.Transparent)),
        start = Offset(0f, baseY),
        end = Offset(size.width, baseY),
        strokeWidth = 2f,
    )
}
