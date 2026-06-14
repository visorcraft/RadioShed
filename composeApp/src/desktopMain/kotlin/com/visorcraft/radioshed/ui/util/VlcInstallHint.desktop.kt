package com.visorcraft.radioshed.ui.util

actual fun vlcInstallHint(): String {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.contains("linux") ->
            "On Linux, install VLC from your package manager (for example: " +
                "`sudo apt install vlc` on Debian/Ubuntu, or the `vlc` package on Arch). " +
                "You can also set RADIOSHED_LIBVLC_PATH if libvlc is installed outside the " +
                "standard paths."
        os.contains("windows") ->
            "On Windows, run the standard VLC installer from videolan.org. RadioShed reads " +
                "the install directory from the registry automatically."
        os.contains("mac") ->
            "On macOS, install VLC.app from videolan.org. RadioShed looks for libvlc inside " +
                "the standard VLC.app bundle."
        else ->
            "Install VLC from videolan.org, then restart RadioShed."
    }
}
