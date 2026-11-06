package com.visorcraft.radioshed.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable

/**
 * Cross-platform `BLUETOOTH_CONNECT` runtime-permission gate for the Drive
 * Mode config UI, which needs the grant (API 31+) to enumerate paired
 * devices via [com.visorcraft.radioshed.car.BluetoothWatcher.bondedDevices].
 *
 * Mirrors the Compose-side `expect`/`actual` hook idiom used by
 * `ui/util/VoiceInput` rather than the deep-link
 * [StoragePermissions] shim, because `BLUETOOTH_CONNECT` is a genuine
 * runtime dialog (not a Settings deep-link). Android backs it with
 * `rememberLauncherForActivityResult(RequestPermission())`; desktop and iOS
 * report [BluetoothConnectGate.granted] = true and no-op the request (the
 * Null watcher there simply returns no devices).
 */
@Composable
expect fun rememberBluetoothConnectGate(): BluetoothConnectGate

/**
 * Current grant state plus a request trigger. [granted] recomposes when the
 * user answers the system dialog; callers re-read [com.visorcraft.radioshed
 * .car.BluetoothWatcher.bondedDevices] once it flips true.
 */
@Stable
interface BluetoothConnectGate {
    /** True when `BLUETOOTH_CONNECT` is granted (always true off-Android). */
    val granted: Boolean

    /** Show the system runtime dialog. No-op once already granted / off-Android. */
    fun request()
}
