package com.visorcraft.radioshed.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Desktop has no runtime Bluetooth permission and the Null watcher returns no
 * devices anyway, so the gate reports granted and the request is a no-op.
 */
@Composable
actual fun rememberBluetoothConnectGate(): BluetoothConnectGate =
    remember {
        object : BluetoothConnectGate {
            override val granted: Boolean = true

            override fun request() { /* no-op on desktop */ }
        }
    }
