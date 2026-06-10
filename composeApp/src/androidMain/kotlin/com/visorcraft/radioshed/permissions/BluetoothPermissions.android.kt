package com.visorcraft.radioshed.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
actual fun rememberBluetoothConnectGate(): BluetoothConnectGate {
    val context = LocalContext.current
    // BLUETOOTH_CONNECT is only a runtime permission on API 31+ (S). On older
    // releases the legacy install-time BLUETOOTH permission already covers the
    // bonded-device read, so treat it as permanently granted there.
    val needsRuntimeGrant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun checkGranted(): Boolean = !needsRuntimeGrant ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED

    // Held in a MutableState so the gate object reads it reactively — flipping
    // it on the dialog result recomposes any caller observing gate.granted.
    val grantedState: MutableState<Boolean> = remember { mutableStateOf(checkGranted()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        grantedState.value = result || checkGranted()
    }
    return remember(grantedState, launcher, needsRuntimeGrant) {
        object : BluetoothConnectGate {
            override val granted: Boolean get() = grantedState.value

            override fun request() {
                if (grantedState.value) return
                if (!needsRuntimeGrant) {
                    grantedState.value = true
                    return
                }
                launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }
}
