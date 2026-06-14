package com.visorcraft.radioshed.permissions

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
actual fun rememberLocationGate(): LocationGate {
    val context = LocalContext.current

    fun checkGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    val grantedState: MutableState<Boolean> = remember { mutableStateOf(checkGranted()) }
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { result ->
            grantedState.value = result || checkGranted()
        }
    return remember(grantedState, launcher) {
        object : LocationGate {
            override val granted: Boolean get() = grantedState.value

            override fun request() {
                if (grantedState.value) return
                launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
    }
}
