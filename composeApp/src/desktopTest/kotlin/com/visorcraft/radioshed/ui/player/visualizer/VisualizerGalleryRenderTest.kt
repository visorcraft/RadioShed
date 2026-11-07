package com.visorcraft.radioshed.ui.player.visualizer

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.visorcraft.radioshed.audio.visualizer.AmbientSignalGenerator
import com.visorcraft.radioshed.audio.visualizer.MusicStructure
import com.visorcraft.radioshed.audio.visualizer.SpectralHistory
import com.visorcraft.radioshed.audio.visualizer.VisualizerFrame
import com.visorcraft.radioshed.audio.visualizer.VisualizerPalette
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Rewrites the Aurora and Waterfall README frames in the Prism palette.
 * The checked-in pair was captured in Monochrome, so those two tiles
 * read as gray next to the rest of the gallery. Set RADIOSHED_GALLERY_DIR
 * to a directory to regenerate them from the app's draw path. With the
 * variable unset, the test does not write anything.
 */
class VisualizerGalleryRenderTest {
    @Test
    fun renderPrismAuroraAndWaterfall() {
        val dir = System.getenv("RADIOSHED_GALLERY_DIR") ?: return
        val out = File(dir)
        check(out.isDirectory) { "RADIOSHED_GALLERY_DIR is not a directory: $dir" }

        val generator = AmbientSignalGenerator()
        val timeMs = 18_600L
        val trackId = 7L
        val history = SpectralHistory()
        repeat(56) { step ->
            history.add(generator.frame(timeMs - (55 - step) * 90L, trackId).spectrum)
        }
        val frame = generator.frame(timeMs, trackId)
        val theme = visualizerTheme(VisualizerPalette.Prism)
        val structure = MusicStructure(tension = 0.62f, impact = 0.28f, dropped = false)
        val paths = VisualizerPathCache()
        val heroBrush = Brush.horizontalGradient(theme.ramp.drop(1))

        writeScene(out.resolve("aurora.png"), frame, theme) {
            drawAurora(
                frame = frame,
                theme = theme,
                intensity = 1.15f,
                timeMs = timeMs,
                structure = structure,
                paths = paths.layers,
            )
        }
        writeScene(out.resolve("waterfall.png"), frame, theme) {
            drawWaterfall(
                history = history,
                theme = theme,
                heroBrush = heroBrush,
                intensity = 1.2f,
                paths = paths.layers,
            )
        }
    }

    private fun writeScene(
        file: File,
        frame: VisualizerFrame,
        theme: VisualizerTheme,
        scene: DrawScope.() -> Unit,
    ) {
        val bitmap = ImageBitmap(WIDTH, HEIGHT)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(WIDTH.toFloat(), HEIGHT.toFloat()),
        ) {
            drawRect(Brush.verticalGradient(listOf(theme.backgroundTop, theme.backgroundBottom)))
            drawAtmosphere(frame = frame, theme = theme, timeMs = 18_600L, reduceMotion = false)
            scene()
            drawVignette(theme)
        }
        ImageIO.write(bitmap.toAwtImage(), "png", file)
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 800
    }
}
