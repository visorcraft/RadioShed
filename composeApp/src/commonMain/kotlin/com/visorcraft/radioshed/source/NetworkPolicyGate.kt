package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.domain.ConnectivityPolicy

interface ConnectivityChecker {
    fun isOnline(): Boolean
    fun isUnmetered(): Boolean
}

class NetworkPolicyGate(
    private val settings: SettingsStore,
    private val checker: ConnectivityChecker,
) {
    fun allowAutomaticFetch(): Boolean = when (settings.getConnectivityPolicy()) {
        ConnectivityPolicy.Always -> checker.isOnline()
        ConnectivityPolicy.WiFiOnly -> checker.isOnline() && checker.isUnmetered()
        ConnectivityPolicy.Never -> false
        ConnectivityPolicy.Manual -> false
    }

    fun allowUserInitiatedFetch(): Boolean = when (settings.getConnectivityPolicy()) {
        ConnectivityPolicy.Always -> checker.isOnline()
        ConnectivityPolicy.WiFiOnly -> checker.isOnline() && checker.isUnmetered()
        ConnectivityPolicy.Never -> false
        ConnectivityPolicy.Manual -> checker.isOnline()
    }
}
