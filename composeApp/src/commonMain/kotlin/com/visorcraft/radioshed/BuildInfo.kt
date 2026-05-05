package com.visorcraft.radioshed

// Hardcoded for now; will be wired up to version.properties via a Gradle
// build-config plugin in a follow-up commit so this stays in sync with
// AndroidManifest versionName / Info.plist CFBundleShortVersionString.
object BuildInfo {
    const val VERSION_NAME: String = "0.1.0"
    const val VERSION_CODE: Int = 1

    /**
     * UTC date string when this APK / framework was assembled. Used by
     * the App Settings → About panel so users (and bug reports) know
     * which build is running. Updated manually with each batch ship —
     * a Gradle task that stamps it from `git log -1 --format=%cd` is
     * tracked as a follow-up.
     */
    const val BUILD_DATE: String = "2026-05-04v"
}
