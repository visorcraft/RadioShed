package com.visorcraft.radioshed.source

import java.io.File

/**
 * Desktop volume discovery. Reports each filesystem root the OS
 * exposes via `File.listRoots()` — on Windows that's every drive
 * letter (`C:\`, `D:\`, …) including connected USB / SD / network
 * mounts; on Linux/macOS that's `/` plus any mountpoints the JVM
 * surfaces. The user's home directory is added as a "Home" entry up
 * front since it's the most common library root and not surfaced
 * by listRoots on macOS / Linux.
 *
 * The "primary" volume is the root the user's home lives under — a
 * loose Windows-friendly heuristic; on POSIX everything falls under
 * `/` so primary == that root.
 *
 * Removable detection is best-effort: on Windows, drive letters
 * other than the system drive are flagged removable so external /
 * USB drives get the visual cue in the Library Settings UI. On
 * POSIX we don't try (no clean JVM API for that).
 */
actual class StorageVolumes {
    actual fun discover(): List<DiscoveredVolume> {
        val home = System.getProperty("user.home")
        val isWindows = System.getProperty("os.name").orEmpty().lowercase()
            .contains("win")
        val systemDrive = if (isWindows) {
            (System.getenv("SystemDrive") ?: "C:").uppercase() + "\\"
        } else {
            "/"
        }
        val roots = File.listRoots().orEmpty()
        val volumeFromRoots = roots.map { root ->
            val path = root.absolutePath
            val label = if (isWindows) {
                "Drive ${path.removeSuffix("\\").uppercase()}"
            } else {
                "Root ($path)"
            }
            DiscoveredVolume(
                path = path,
                label = label,
                isPrimary = path.equals(systemDrive, ignoreCase = true),
                isRemovable = isWindows && !path.equals(
                    systemDrive,
                    ignoreCase = true,
                ),
            )
        }
        // Always include "Home" as a first-class entry — it's the
        // overwhelmingly common library root and isn't a listRoots()
        // result on macOS / Linux.
        val homeVolume = DiscoveredVolume(
            path = home,
            label = "Home",
            isPrimary = !isWindows,
            isRemovable = false,
        )
        return listOf(homeVolume) + volumeFromRoots
    }
}
