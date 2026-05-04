package com.visorcraft.radioshed.source

/**
 * Detects whether a Syncthing client is installed and offers a way
 * to launch it. Used by the in-app Syncthing setup wizard.
 *
 * Two known Android packages — Syncthing-Fork by Catfriend1 (the
 * actively maintained fork on Play Store + F-Droid) and the original
 * Nutomic build (still floating around on older devices and
 * F-Droid mirrors). The detector reports either as "installed" so
 * the wizard doesn't push the user to install a second copy.
 *
 * iOS has no Syncthing app (the protocol is unfinished on iOS), so
 * the actual returns false / no-op there. The wizard's UI degrades
 * to a "this isn't supported on iOS yet" message.
 */
expect class SyncthingDetector {
    /** True when at least one known Syncthing package is installed. */
    fun isInstalled(): Boolean

    /**
     * The package name we detected, or null when none is installed.
     * Used by the wizard to render which fork the user has so the
     * step-by-step instructions match the right UI.
     */
    fun installedPackage(): String?

    /**
     * Launches the installed Syncthing app via its main launcher
     * intent. Returns false when no Syncthing is installed (the
     * caller should fall back to the install flow).
     */
    fun launch(): Boolean
}

/** Play Store URL for Syncthing-Fork — first-recommended install path. */
const val SYNCTHING_FORK_PLAY_URL: String =
    "https://play.google.com/store/apps/details?id=com.github.catfriend1.syncthingandroid"

/** F-Droid page for Syncthing-Fork — for users who prefer F-Droid / no Play Store. */
const val SYNCTHING_FORK_FDROID_URL: String =
    "https://f-droid.org/packages/com.github.catfriend1.syncthingandroid/"

/** Desktop installer landing page. */
const val SYNCTHING_DESKTOP_URL: String = "https://syncthing.net/downloads/"

/** Local Syncthing web admin UI — present once the user has launched the app at least once. */
const val SYNCTHING_LOCAL_WEB_UI_URL: String = "http://localhost:8384"
