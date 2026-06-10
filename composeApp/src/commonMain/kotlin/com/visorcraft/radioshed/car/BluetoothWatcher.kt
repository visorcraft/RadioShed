package com.visorcraft.radioshed.car

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A Bluetooth peer the platform reports as connected or pairable. */
data class BtPeer(val mac: String, val name: String)

/**
 * Platform watcher for Bluetooth device connections, used by
 * [DriveModeController] to drive the `bt_device` trigger.
 *
 * [start] and [stop] are idempotent — calling start twice registers one
 * listener, calling stop twice is a no-op. [connectedDevices] reflects the
 * current set of connected peers and updates as devices connect/disconnect.
 */
interface BluetoothWatcher {

    /** The currently-connected Bluetooth peers. Empty until [start]. */
    val connectedDevices: StateFlow<Set<BtPeer>>

    fun start()

    fun stop()

    /**
     * Bonded (paired) devices, used by the Drive Mode config UI to offer
     * trigger candidates. Returns an empty list when the platform can't
     * enumerate them (no Bluetooth, permission missing, or a Null impl).
     */
    fun bondedDevices(): List<BtPeer>
}

/** No-op watcher for platforms without a Bluetooth peer (desktop/iOS). */
class NullBluetoothWatcher : BluetoothWatcher {
    override val connectedDevices: StateFlow<Set<BtPeer>> =
        MutableStateFlow(emptySet<BtPeer>()).asStateFlow()

    override fun start() {}

    override fun stop() {}

    override fun bondedDevices(): List<BtPeer> = emptyList()
}
