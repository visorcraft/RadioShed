package com.visorcraft.radioshed.source

class IosConnectivityChecker : ConnectivityChecker {
    override fun isOnline(): Boolean = true
    override fun isUnmetered(): Boolean = true
}
