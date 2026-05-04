package com.visorcraft.radioshed.source

/**
 * iOS stub. There's no maintained Syncthing client for iOS — the
 * protocol's incomplete on Apple platforms because background
 * networking is too restrictive to keep peers alive without a
 * persistent foreground session. The wizard's UI degrades to a
 * "not supported on iOS" message when this returns false / null.
 */
actual class SyncthingDetector {
    actual fun isInstalled(): Boolean = false
    actual fun installedPackage(): String? = null
    actual fun launch(): Boolean = false
}
