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
import java.awt.Color as AwtColor
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files

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
 * Programmatically render the title-bar / taskbar icon. Drawing it in
 * code (instead of shipping a `.png` resource) keeps the repo binary-
 * free and lets the brand colour flow from `Color.kt` if we ever bind
 * the two together. 256×256 is enough for every desktop OS — Windows,
 * macOS, and most Linux DEs auto-downscale.
 *
 * The mark is a flat blue rounded square with a lowercase `rs` set
 * in the JVM's default sans-serif. It's a placeholder — when a
 * proper logo lands, replace this with `BitmapPainter` over a real
 * resource.
 */
private fun renderAppIcon(): BitmapPainter {
    val size = 256
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    try {
        g.setRenderingHint(
            RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_ON,
        )
        g.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
        )
        // Brand blue background. RGB matches `LightPrimary` in
        // ui/theme/Color.kt so the icon's tile reads as the same
        // colour as the in-app primary surface.
        g.color = AwtColor(0x00, 0x61, 0xA4)
        g.fillRoundRect(0, 0, size, size, 64, 64)
        // White wordmark.
        g.color = AwtColor.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 140)
        val fm = g.fontMetrics
        val text = "rs"
        val tx = (size - fm.stringWidth(text)) / 2
        // Vertical centring: half the leading-adjusted height + ascent.
        val ty = (size - fm.height) / 2 + fm.ascent
        g.drawString(text, tx, ty)
    } finally {
        g.dispose()
    }
    return BitmapPainter(img.toComposeImageBitmap())
}

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
