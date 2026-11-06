package com.visorcraft.radioshed.ui.player.visualizer

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.visorcraft.radioshed.audio.visualizer.ImpactRingPool
import com.visorcraft.radioshed.audio.visualizer.MusicStructure
import com.visorcraft.radioshed.audio.visualizer.SpectralHistory
import com.visorcraft.radioshed.audio.visualizer.VisualizerFrame
import com.visorcraft.radioshed.audio.visualizer.VisualizerMode
import com.visorcraft.radioshed.audio.visualizer.VisualizerPalette
import com.visorcraft.radioshed.audio.visualizer.WaveformHistory
import com.visorcraft.radioshed.audio.visualizer.sceneIntroProgress
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun VisualizerCanvas(
    frameState: State<VisualizerFrame>,
    timeState: State<Long>,
    structureState: State<MusicStructure>,
    vuLevels: State<FloatArray>,
    history: SpectralHistory,
    waveformHistory: WaveformHistory,
    impactRings: ImpactRingPool,
    mode: VisualizerMode,
    palette: VisualizerPalette,
    intensity: Float,
    playing: Boolean,
    reduceMotion: Boolean,
    sceneStartedAt: Long,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val theme = remember(palette) { visualizerTheme(palette) }
    val paths = remember { VisualizerPathCache() }
    val backdrop =
        remember(theme) {
            Brush.verticalGradient(listOf(theme.backgroundTop, theme.backgroundBottom))
        }
    val heroBrush = remember(theme) { Brush.horizontalGradient(theme.ramp.drop(1)) }
    val textMeasurer = rememberTextMeasurer()
    val meterLabelStyle =
        remember {
            TextStyle(
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 3.sp,
            )
        }
    val meterBrandStyle =
        remember {
            TextStyle(
                color = Color.White.copy(alpha = 0.22f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp,
            )
        }
    Canvas(
        modifier =
            modifier.graphicsLayer {
                val intro = sceneIntroProgress(timeState.value - sceneStartedAt, reduceMotion, playing)
                alpha = (if (playing) 1f else 0.38f) * intro
                val introScale = 0.965f + intro * 0.035f
                scaleX = introScale
                scaleY = introScale
            },
    ) {
        val frame = frameState.value
        val structure = structureState.value
        val time = timeState.value
        val motionTime = if (reduceMotion) time / 4 else time
        val intro = sceneIntroProgress(time - sceneStartedAt, reduceMotion, playing)
        drawRect(backdrop)
        drawAtmosphere(frame, theme, motionTime, reduceMotion)
        withTransform({
            if (!reduceMotion) {
                translate(
                    left = sin(time * 0.00007).toFloat() * size.width * 0.004f,
                    top = cos(time * 0.00009).toFloat() * size.height * 0.004f,
                )
                val punch =
                    1f + frame.beat * 0.012f * intensity.coerceIn(0f, 1.5f) +
                        structure.impact * 0.03f + structure.tension * 0.004f
                scale(punch, punch, pivot = center)
            }
        }) {
            when (mode) {
                VisualizerMode.Spectrum -> drawSpectrum(frame, theme, intensity)
                VisualizerMode.Oscilloscope ->
                    drawOscilloscope(
                        frame = frame,
                        history = waveformHistory,
                        theme = theme,
                        heroBrush = heroBrush,
                        intensity = intensity,
                        paths = paths,
                    )
                VisualizerMode.Bloom ->
                    drawBloom(frame, theme, intensity, motionTime, impactRings, paths.primary)
                VisualizerMode.Aurora -> drawAurora(frame, theme, intensity, motionTime, structure, paths.layers)
                VisualizerMode.Ripples ->
                    drawRipples(
                        frame = frame,
                        theme = theme,
                        heroBrush = heroBrush,
                        intensity = intensity,
                        timeMs = motionTime,
                        impactRings = impactRings,
                        primaryPath = paths.primary,
                        secondaryPath = paths.secondary,
                    )
                VisualizerMode.Starfield ->
                    drawStarfield(frame, theme, intensity, motionTime, reduceMotion, structure)
                VisualizerMode.Waterfall -> drawWaterfall(history, theme, heroBrush, intensity, paths.layers)
                VisualizerMode.VuMeters ->
                    drawVuMeters(
                        levels = vuLevels.value,
                        frame = frame,
                        theme = theme,
                        intensity = intensity,
                        textMeasurer = textMeasurer,
                        labelStyle = meterLabelStyle,
                        brandStyle = meterBrandStyle,
                    )
                VisualizerMode.Vinyl -> drawVinyl(frame, theme, intensity, motionTime, progress, reduceMotion)
            }
        }
        if (!reduceMotion) {
            val flash = (frame.beat * 0.025f + structure.impact * 0.045f).coerceAtMost(0.07f)
            drawRect(theme.glow.copy(alpha = flash), blendMode = BlendMode.Plus)
            drawSceneIntro(theme, intro)
        }
        drawVignette(theme)
    }
}

internal class VisualizerPathCache {
    val primary = Path()
    val secondary = Path()
    val layers = Array(WATERFALL_ROWS * 2) { Path() }
}

private fun DrawScope.drawSceneIntro(
    theme: VisualizerTheme,
    progress: Float,
) {
    if (progress >= 1f) return
    val x = progress * (size.width + 240f) - 120f
    drawLine(
        color = theme.glow.copy(alpha = (1f - progress) * 0.12f),
        start = Offset(x - 80f, 0f),
        end = Offset(x + 80f, size.height),
        strokeWidth = 52f,
        blendMode = BlendMode.Plus,
    )
    drawLine(
        color = theme.accent.copy(alpha = (1f - progress) * 0.46f),
        start = Offset(x - 80f, 0f),
        end = Offset(x + 80f, size.height),
        strokeWidth = 2f,
    )
}

private fun DrawScope.drawAtmosphere(
    frame: VisualizerFrame,
    theme: VisualizerTheme,
    timeMs: Long,
    reduceMotion: Boolean,
) {
    val drift = if (reduceMotion) 0.0 else timeMs * 0.00008
    drawCircle(
        brush =
            Brush.radialGradient(
                colors = listOf(theme.glow.copy(alpha = 0.12f + frame.bass * 0.14f), Color.Transparent),
                center = Offset(center.x, size.height * 0.56f),
                radius = size.minDimension * (0.52f + frame.beat * 0.10f),
            ),
        center = Offset(center.x, size.height * 0.56f),
        radius = size.minDimension * (0.52f + frame.beat * 0.10f),
        blendMode = BlendMode.Plus,
    )
    if (reduceMotion) return
    repeat(42) { index ->
        val xSeed = visualizerNoise(index * 4 + 11)
        val ySeed = visualizerNoise(index * 4 + 47)
        val x = (xSeed + sin(drift + index) * 0.012).toFloat() * size.width
        val y = visualizerFract(ySeed + drift * (0.10 + (index % 5) * 0.025)) * size.height
        val depth = 0.35f + visualizerNoise(index * 7 + 3) * 0.65f
        drawCircle(
            color = theme.colorAt(0.35f + depth * 0.55f).copy(alpha = (0.08f + frame.treble * 0.18f) * depth),
            radius = 1f + depth * 2.8f,
            center = Offset(x, y),
            blendMode = BlendMode.Plus,
        )
    }
}

private fun DrawScope.drawVignette(theme: VisualizerTheme) {
    val radius = maxOf(size.width, size.height) * 0.72f
    drawRect(
        brush =
            Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.58f)),
                center = center,
                radius = radius,
            ),
    )
    drawRect(
        brush =
            Brush.verticalGradient(
                colors = listOf(Color.Black.copy(alpha = 0.44f), Color.Transparent),
                startY = 0f,
                endY = size.height * 0.24f,
            ),
    )
    drawRect(
        brush =
            Brush.verticalGradient(
                colors = listOf(Color.Transparent, theme.backgroundBottom.copy(alpha = 0.64f)),
                startY = size.height * 0.72f,
                endY = size.height,
            ),
    )
}

internal fun visualizerNoise(seed: Int): Float = visualizerFract(sin(seed * 12.9898) * 43_758.5453)

internal fun visualizerFract(value: Double): Float {
    val fraction = value - value.toInt()
    return if (fraction < 0) (fraction + 1).toFloat() else fraction.toFloat()
}

internal fun visualizerFract(value: Float): Float {
    val fraction = value - value.toInt()
    return if (fraction < 0) fraction + 1f else fraction
}
