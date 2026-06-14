package com.visorcraft.radioshed.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable

/**
 * Cross-platform coarse-location runtime-permission gate for Drive Mode speed
 * triggers, which need a GPS fix via [com.visorcraft.radioshed.car.SpeedWatcher].
 *
 * Android backs it with `rememberLauncherForActivityResult(RequestPermission())`;
 * desktop and iOS report [LocationGate.granted] = true and no-op the request
 * (those platforms bind [com.visorcraft.radioshed.car.NullSpeedWatcher]).
 */
@Composable
expect fun rememberLocationGate(): LocationGate

/** Current grant state plus a request trigger for coarse location. */
@Stable
interface LocationGate {
    val granted: Boolean

    fun request()
}
