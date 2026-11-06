package com.visorcraft.radioshed.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * iOS Bluetooth peer enumeration is stubbed (the Null watcher returns no
 * devices), so the gate reports granted and the request is a no-op.
 */
@Composable
actual fun rememberBluetoothConnectGate(): BluetoothConnectGate =
    remember {
        object : BluetoothConnectGate {
            override val granted: Boolean = true

            override fun request() { /* stub on iOS */ }
        }
    }
