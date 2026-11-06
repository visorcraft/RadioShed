package com.visorcraft.radioshed.car

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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
 *
 * Lifecycle note: [start] and [stop] are each idempotent (multiple consecutive
 * calls are safe). [start] also kicks off a best-effort, one-shot seed of
 * [connectedDevices] from the A2DP/HEADSET profile proxies — the proxy callback
 * is async, so the seed lands shortly after [start] returns and is merged
 * (union) into whatever ACL broadcasts have already reported. Without it a peer
 * connected *before* the app launched (the primary Drive Mode case: the car
 * stereo auto-connects, then the user opens the app) would never appear, because
 * no fresh ACL event fires for an existing connection. Seeding failure of any
 * kind degrades to "no seed"; ACL broadcasts keep the set current from then on.
 */
class AndroidBluetoothWatcher(private val context: Context) : BluetoothWatcher {
    private val _connectedDevices = MutableStateFlow<Set<BtPeer>>(emptySet())
    override val connectedDevices: StateFlow<Set<BtPeer>> = _connectedDevices.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    override fun start() {
        if (receiver != null) return
        val r =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
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
        val filter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
        // ACL events keep the set current from here on; the profile-proxy seed
        // below back-fills peers that connected before we registered, since no
        // fresh ACL broadcast ever fires for an already-established link.
        runCatching {
            ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_EXPORTED)
        }
        receiver = r
        seedAlreadyConnected()
    }

    override fun stop() {
        receiver?.let { r -> runCatching { context.unregisterReceiver(r) } }
        receiver = null
        // Seed proxies whose async callback hasn't fired yet are released by
        // the callback itself (it checks [receiver] and skips the merge once
        // stopped) — there is no handle to close before the binder connects.
        _connectedDevices.value = emptySet()
    }

    /**
     * Best-effort, one-shot seed of [connectedDevices] from the audio profile
     * proxies (A2DP for media, HEADSET for HFP — car stereos expose one or
     * both). [BluetoothManager.getAdapter]'s `getProfileProxy` is inherently
     * async: the snapshot arrives in `onServiceConnected` on a binder-driven
     * callback, so this never blocks [start]. Each callback MERGES into the
     * set (existing macs win) rather than replacing it, so an ACL broadcast
     * that raced ahead of the proxy bind is never lost, then releases the
     * proxy immediately. Permission-guarded and [runCatching]-wrapped like
     * every other adapter read in this file: any failure degrades to "no
     * seed", never a crash.
     */
    private fun seedAlreadyConnected() {
        if (!hasConnectPermission()) return
        runCatching {
            val adapter =
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                    ?.adapter ?: return
            val listener =
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(
                        profile: Int,
                        proxy: BluetoothProfile,
                    ) {
                        runCatching {
                            // stop() may have run while the proxy was binding; in
                            // that case skip the merge so the cleared set isn't
                            // repopulated, but still fall through to the close.
                            if (receiver != null) {
                                val current = _connectedDevices.value
                                val newPeers =
                                    proxy.connectedDevices.orEmpty()
                                        .map { it.toPeer() }
                                        .filterNot { peer -> current.any { it.mac == peer.mac } }
                                if (newPeers.isNotEmpty()) {
                                    _connectedDevices.value = current + newPeers
                                }
                            }
                        }
                        runCatching { adapter.closeProfileProxy(profile, proxy) }
                    }

                    override fun onServiceDisconnected(profile: Int) {}
                }
            adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP)
            adapter.getProfileProxy(context, listener, BluetoothProfile.HEADSET)
        }
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
        val name =
            if (hasConnectPermission()) {
                runCatching { name }.getOrNull()
            } else {
                null
            }
        return BtPeer(mac = address, name = name ?: address)
    }

    private fun hasConnectPermission(): Boolean {
        // Below API 31 the legacy BLUETOOTH permission is declared in the manifest
        // with android:maxSdkVersion="30" and is granted at install time — no runtime
        // prompt is ever shown. Returning true here is genuinely correct: the grant
        // is unconditional on those releases.
        // From API 31 onward the legacy permission no longer exists; BLUETOOTH_CONNECT
        // is a runtime permission that the config UI must request explicitly.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
