package com.visorcraft.radioshed.car

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android [BluetoothWatcher] backed by ACL connect/disconnect broadcasts.
 *
 * Tracks connected peers via the system-wide
 * [BluetoothDevice.ACTION_ACL_CONNECTED] / [BluetoothDevice.ACTION_ACL_DISCONNECTED]
 * broadcasts (these are implicit, app-receivable broadcasts that don't require
 * the BLUETOOTH_CONNECT permission to *receive* — but reading device.name and
 * the bonded list does, so every adapter/device read is permission-guarded).
 *
 * Permission model (API 31+): without runtime BLUETOOTH_CONNECT,
 * [connectedDevices] simply never populates and [bondedDevices] returns empty —
 * this class NEVER requests the permission (that's the config UI's job). Every
 * adapter/device access is wrapped in [runCatching] so a [SecurityException]
 * (e.g. permission revoked between the check and the read) degrades to "no
 * device" rather than crashing.
 */
class AndroidBluetoothWatcher(private val context: Context) : BluetoothWatcher {

    private val _connectedDevices = MutableStateFlow<Set<BtPeer>>(emptySet())
    override val connectedDevices: StateFlow<Set<BtPeer>> = _connectedDevices.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    override fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                // Wrap the whole body: BluetoothDevice.getAddress()/getName() both
                // require BLUETOOTH_CONNECT on API 31+ and throw SecurityException
                // if the user hasn't granted it. A throw out of onReceive crashes
                // the process, so a revoked-permission read degrades to "ignore
                // this broadcast" rather than taking the app down.
                runCatching {
                    val device: BluetoothDevice =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        } ?: return@runCatching
                    when (intent.action) {
                        BluetoothDevice.ACTION_ACL_CONNECTED ->
                            // Dedupe on mac: BtPeer equality includes the name, so a
                            // reconnect that resolves a different name (permission just
                            // granted, cached vs live name) would otherwise leave two
                            // peers for one device. Replace any same-mac entry.
                            _connectedDevices.value =
                                _connectedDevices.value.filterNot { it.mac == device.address }.toSet() +
                                    device.toPeer()
                        BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                            _connectedDevices.value =
                                _connectedDevices.value.filterNot { it.mac == device.address }.toSet()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // Seed empty and rely on ACL events. The adapter exposes bonded devices
        // but NOT a cheap "which bonded devices are currently connected" query
        // without per-profile proxies (A2DP/headset), which is more machinery
        // than this trigger warrants — so we start empty and let the first
        // connect/disconnect broadcast populate the set.
        runCatching {
            ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_EXPORTED)
        }
        receiver = r
    }

    override fun stop() {
        receiver?.let { r -> runCatching { context.unregisterReceiver(r) } }
        receiver = null
        _connectedDevices.value = emptySet()
    }

    override fun bondedDevices(): List<BtPeer> {
        if (!hasConnectPermission()) return emptyList()
        return runCatching {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter?.bondedDevices?.map { it.toPeer() }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /** device.name needs BLUETOOTH_CONNECT (API 31+) and can throw SecurityException;
     *  fall back to the mac as the display name. */
    private fun BluetoothDevice.toPeer(): BtPeer {
        val name = if (hasConnectPermission()) {
            runCatching { name }.getOrNull()
        } else {
            null
        }
        return BtPeer(mac = address, name = name ?: address)
    }

    private fun hasConnectPermission(): Boolean {
        // BLUETOOTH_CONNECT is only a runtime permission on API 31+. On earlier
        // releases the legacy BLUETOOTH permission is install-time, so the
        // adapter/name reads are always allowed.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
