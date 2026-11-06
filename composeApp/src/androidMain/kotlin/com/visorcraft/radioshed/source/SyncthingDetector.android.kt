package com.visorcraft.radioshed.source

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Android implementation. Probes PackageManager for the two known
 * Syncthing package names. Order matters: Catfriend1's fork is
 * checked first because it's the actively-maintained build the
 * wizard's instructions are written against.
 *
 * Note that on API 30+ Android restricts package visibility unless
 * you declare `<queries>` in the manifest. The package names are
 * already declared there so `getPackageInfo` returns successfully
 * for installed Syncthings rather than throwing
 * NameNotFoundException for an installed-but-invisible app.
 */
actual class SyncthingDetector(private val context: Context) {
    actual fun isInstalled(): Boolean = installedPackage() != null

    actual fun installedPackage(): String? {
        val pm = context.packageManager
        for (pkg in KNOWN_PACKAGES) {
            if (isPackageInstalled(pm, pkg)) return pkg
        }
        return null
    }

    actual fun launch(): Boolean {
        val pkg = installedPackage() ?: return false
        return runCatching {
            val intent =
                context.packageManager.getLaunchIntentForPackage(pkg)
                    ?: return@runCatching false
            // FLAG_ACTIVITY_NEW_TASK is required when launching from a
            // non-Activity context (we may be invoked from the
            // application context via Koin).
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun isPackageInstalled(
        pm: PackageManager,
        pkg: String,
    ): Boolean =
        runCatching {
            // The signature with `flags: Int` is deprecated on API 33+ in
            // favour of a PackageInfoFlags overload, but the deprecated
            // form still works back to API 21 and reads fine on Tiramisu+
            // — we accept the lint warning rather than gate on Build.VERSION.
            pm.getPackageInfo(pkg, 0)
            true
        }.getOrDefault(false)

    private companion object {
        // Order = preference for the wizard's "first install" path.
        // Syncthing-Fork (Catfriend1) is the actively-maintained build
        // that lives on Play Store + F-Droid; the original Nutomic
        // build was discontinued in 2022 but lingers on devices that
        // installed it years ago. Either is acceptable.
        val KNOWN_PACKAGES =
            listOf(
                "com.github.catfriend1.syncthingandroid",
                "com.nutomic.syncthingandroid",
            )
    }
}
