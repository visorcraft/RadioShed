package com.visorcraft.radioshed.source

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class AndroidConnectivityChecker(private val context: Context) : ConnectivityChecker {
    override fun isOnline(): Boolean =
        caps()?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } ?: false

    override fun isUnmetered(): Boolean =
        caps()?.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
        ) ?: false
    // Why only NOT_METERED and not OR-of-transports: a device
    // tethered to a phone hotspot reports TRANSPORT_WIFI but the
    // upstream link is cellular and metered. Carrier-side metering
    // signal (`NET_CAPABILITY_NOT_METERED`) is the only reliable
    // indicator that bandwidth is actually free, regardless of
    // which physical transport is in use. Verified across phones
    // running Android 11+; older devices may under-report unmetered
    // for some Wi-Fi networks but the conservative direction is
    // correct (we'd block a fetch the user might've allowed,
    // rather than silently using their cellular allowance).

    private fun caps(): NetworkCapabilities? {
        val cm =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
        val active = cm.activeNetwork ?: return null
        return cm.getNetworkCapabilities(active)
    }
}
