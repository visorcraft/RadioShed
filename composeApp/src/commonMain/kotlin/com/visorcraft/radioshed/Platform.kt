package com.visorcraft.radioshed

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

/**
 * True on the JVM/desktop target, false on Android and iOS. Used by
 * UI surfaces that want to expose desktop-style affordances (inline
 * volume slider, keyboard shortcuts) which don't make sense on a
 * touch-first mobile build where hardware keys handle the same job.
 */
expect val isDesktopHost: Boolean