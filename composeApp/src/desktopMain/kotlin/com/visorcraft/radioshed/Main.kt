package com.visorcraft.radioshed

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.AudioEngineDesktop
import com.visorcraft.radioshed.di.initKoin
import com.visorcraft.radioshed.source.appAppDataDir
import kotlinx.coroutines.delay
import org.koin.core.context.GlobalContext
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Desktop entry point. Mirrors what `MainActivity` does on Android:
 *  1. Initialise Koin with the desktop platform module.
 *  2. Open a single Compose Window hosting the shared [App] composable.
 *
 * Window state (size + position) is intentionally *not* persisted yet —
 * the user gets the default centered 1280×800. Persisting it across
 * launches is straightforward (write to SettingsStore) but isn't
 * required for the spike.
 *
 * Koin is started exactly once per JVM. `application { ... }` sets
 * up its own coroutine scope and re-entry isn't expected; if we ever
 * support window-reopen, gate this behind a check.
 */
fun main() {
    // Make sure the per-user app data dir exists *before* Koin spins
    // up the DatabaseDriverFactory — the factory writes radioshed.db
    // there and JdbcSqliteDriver doesn't auto-create parents.
    Files.createDirectories(appAppDataDir())

    initKoin()

    val icon = renderAppIcon()

    application {
        val state = rememberWindowState(width = 1280.dp, height = 800.dp)
        Window(
            onCloseRequest = {
                // Release libvlc handles before the JVM exits so the
                // audio device unlocks immediately. exitApplication()
                // alone terminates the process while libvlc still
                // owns the device, leaving it in a held state for
                // several seconds for the next app that opens it.
                runCatching {
                    val engine = GlobalContext.get().get<AudioEngine>()
                    if (engine is AudioEngineDesktop) engine.close()
                }
                exitApplication()
            },
            state = state,
            title = "RadioShed",
            icon = icon,
        ) {
            // Linux-only: repair the content offset that appears when the
            // window is dragged across monitors (see function KDoc).
            RepairWindowOnMonitorChange()
            Surface { App() }
        }
    }
}

/**
 * Linux multi-monitor workaround for a Compose Desktop / Skiko quirk: after the
 * window crosses to another screen (a `GraphicsConfiguration` change), Compose's
 * content placement goes stale and loses the title-bar inset, so the whole UI
 * renders shifted up by ~the title-bar height — clipping the top and leaving a
 * matching blank strip at the bottom.
 *
 * AWT's own geometry (insets, content-pane size, Skia layer bounds) stays
 * correct across the move, and the offset appears under both the OpenGL and the
 * software Skiko backends — i.e. it is a layout-placement glitch, not a
 * GPU-surface one. Any genuine re-layout clears it, so we force one by nudging
 * the window size by a pixel and back.
 *
 * Timing is the subtle part: the nudge must land *after* the window comes to
 * rest (the drag is dropped). Repairing the instant the screen change is
 * detected fires mid-drag on slow drags, and the window manager's final
 * reconfigure then undoes it — which left a fire-on-detection repair ~10%
 * unreliable. So each screen change arms a short window during which we repair a
 * few times, but only while the window is stationary.
 *
 * No-op on Windows and macOS, where the glitch does not occur.
 */
@Composable
private fun FrameWindowScope.RepairWindowOnMonitorChange() {
    val enabled =
        remember {
            System.getProperty("os.name").orEmpty().lowercase().contains("linux")
        }
    if (!enabled) return
    LaunchedEffect(Unit) {
        fun screenId(): String? = window.graphicsConfiguration?.device?.iDstring
        var lastScreen = screenId()
        var lastBounds = window.bounds
        var repairUntilMs = 0L
        var repairsLeft = 0
        while (true) {
            delay(120)
            val now = System.currentTimeMillis()
            val screen = screenId()
            val bounds = window.bounds
            val moving = bounds != lastBounds
            lastBounds = bounds
            if (screen != null && screen != lastScreen) {
                lastScreen = screen
                repairUntilMs = now + 2_500
                repairsLeft = 3
            }
            if (repairsLeft > 0 && now < repairUntilMs && !moving) {
                repairsLeft--
                val size = window.size
                window.setSize(size.width, size.height + 1)
                delay(60)
                window.setSize(size.width, size.height)
                lastBounds = window.bounds
                delay(400)
            }
        }
    }
}

/**
 * Load the title-bar / taskbar icon from the bundled Compose
 * resource at `composeApp/src/commonMain/composeResources/drawable/radioshed.png`
 * (256×256), which is shared with the in-app welcome screen.
 *
 * 256×256 is enough for every desktop OS — Windows, macOS, and
 * most Linux DEs auto-downscale for taskbar / dock display and
 * up-scale gracefully on HiDPI. The packaged-installer icons
 * (`.ico` on Windows, `.png` on Linux, `.icns` on macOS) are
 * wired separately via `compose.desktop.nativeDistributions`
 * in `composeApp/build.gradle.kts`.
 */
private fun renderAppIcon(): BitmapPainter {
    val stream =
        Thread.currentThread().contextClassLoader
            .getResourceAsStream(APP_ICON_RESOURCE)
            ?: error("$APP_ICON_RESOURCE not on the classpath")
    val img =
        stream.use { ImageIO.read(it) }
            ?: error("$APP_ICON_RESOURCE failed to decode")
    return BitmapPainter(img.toComposeImageBitmap())
}

private const val APP_ICON_RESOURCE =
    "composeResources/radioshed.composeapp.generated.resources/drawable/radioshed.png"

/**
 * Resolve the per-user app data directory:
 *  - Windows: %APPDATA%/RadioShed
 *  - macOS:   ~/Library/Application Support/RadioShed
 *  - Linux:   ${XDG_DATA_HOME:-~/.local/share}/RadioShed
 *
 * Lives in [com.visorcraft.radioshed.source] so the file path
 * resolver is reusable from `DatabaseDriverFactory`,
 * `DesktopArtworkPersister`, and any future feature that needs a
 * stable on-disk location.
 */
