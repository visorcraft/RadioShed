package com.visorcraft.radioshed

// Hardcoded for now; will be wired up to version.properties via a Gradle
// build-config plugin in a follow-up commit so this stays in sync with
// AndroidManifest versionName / Info.plist CFBundleShortVersionString.
object BuildInfo {
    const val VERSION_NAME: String = "0.1.0"
    const val VERSION_CODE: Int = 1
}
