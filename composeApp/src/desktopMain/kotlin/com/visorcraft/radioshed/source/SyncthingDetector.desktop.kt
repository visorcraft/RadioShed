package com.visorcraft.radioshed.source

/**
 * No-op desktop SyncthingDetector. The wizard's setup flow on desktop
 * pushes the user to syncthing.net's installer; there's no in-process
 * detection of an external Syncthing service yet (it'd need to probe
 * `localhost:8384` — deferred until the wizard explicitly needs it).
 */
actual class SyncthingDetector {
    actual fun isInstalled(): Boolean = false

    actual fun installedPackage(): String? = null

    actual fun launch(): Boolean = false
}
