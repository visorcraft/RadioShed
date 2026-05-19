package com.visorcraft.radioshed

import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.AudioEngineDesktop
import com.visorcraft.radioshed.di.initKoin
import com.visorcraft.radioshed.source.appAppDataDir
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
            Surface { App() }
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
    val stream = Thread.currentThread().contextClassLoader
        .getResourceAsStream(APP_ICON_RESOURCE)
        ?: error("$APP_ICON_RESOURCE not on the classpath")
    val img = stream.use { ImageIO.read(it) }
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
