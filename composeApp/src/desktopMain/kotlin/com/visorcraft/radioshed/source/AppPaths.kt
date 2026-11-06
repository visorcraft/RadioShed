package com.visorcraft.radioshed.source

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Per-user application data directory. Resolves to:
 *  - **Windows**: `%APPDATA%/RadioShed`
 *    (falls back to `%USERPROFILE%/AppData/Roaming/RadioShed`).
 *  - **macOS**:   `~/Library/Application Support/RadioShed`.
 *  - **Linux**:   `${XDG_DATA_HOME:-~/.local/share}/RadioShed`.
 *
 * Caller is responsible for `Files.createDirectories(...)` if the
 * directory must exist before use; the resolver itself is pure-path
 * (no I/O), so it's safe to call from background threads.
 */
internal fun appAppDataDir(): Path {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    val home = System.getProperty("user.home") ?: "."
    return when {
        osName.contains("win") -> {
            val appdata =
                System.getenv("APPDATA")
                    ?: "$home/AppData/Roaming"
            Paths.get(appdata, "RadioShed")
        }
        osName.contains("mac") || osName.contains("darwin") ->
            Paths.get(home, "Library", "Application Support", "RadioShed")
        else -> {
            val xdg = System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
            Paths.get(xdg, "RadioShed")
        }
    }
}

/** Subdirectory for persisted album-art bytes (sha256-keyed). */
internal fun appArtworkDir(): Path = appAppDataDir().resolve("artwork")
