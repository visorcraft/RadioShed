package com.visorcraft.radioshed.source

import java.awt.Desktop
import java.net.URI

/**
 * Desktop URL / mailto opener. Routes through the JVM's `Desktop`
 * API which dispatches to the user's default browser / mail client
 * via the platform shell. Returns false when the host doesn't
 * advertise the BROWSE action (rare on consumer desktops, common
 * on headless installs / WSL without an X server).
 */
internal actual fun openWebUrl(url: String): Boolean =
    runCatching {
        if (!Desktop.isDesktopSupported()) return@runCatching false
        val desktop = Desktop.getDesktop()
        val uri = URI(url)
        when {
            url.startsWith("mailto:") &&
                desktop.isSupported(Desktop.Action.MAIL) -> {
                desktop.mail(uri)
                true
            }
            desktop.isSupported(Desktop.Action.BROWSE) -> {
                desktop.browse(uri)
                true
            }
            else -> false
        }
    }.getOrDefault(false)
