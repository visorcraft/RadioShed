package com.visorcraft.radioshed

/**
 * Desktop platform descriptor. Reports the host OS so any
 * platform-aware code paths (telemetry strings, About panel) can
 * surface a meaningful label.
 */
private class DesktopPlatform : Platform {
    override val name: String = "Desktop ${System.getProperty("os.name").orEmpty()}"
}

actual fun getPlatform(): Platform = DesktopPlatform()
