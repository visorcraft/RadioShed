package com.visorcraft.radioshed.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * GPS-backed speed watcher for Drive Mode speed triggers. Updates roughly
 * once per second when location permission is granted; emits null otherwise.
 *
 * [start] is idempotent and may be called again after the user grants
 * permission or re-enables GPS — only a successful [requestLocationUpdates]
 * registration counts as active.
 */
class AndroidSpeedWatcher(
    private val context: Context,
) : SpeedWatcher, LocationListener {
    private val _speedKmh = MutableStateFlow<Float?>(null)
    override val speedKmh: StateFlow<Float?> = _speedKmh

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /** True while location updates are registered with the OS. */
    private var listening = false

    override fun start() {
        if (listening) return
        if (!hasLocationPermission()) return
        val provider = resolveProvider() ?: return
        val registered =
            runCatching {
                locationManager.requestLocationUpdates(
                    provider,
                    1_000L,
                    0f,
                    this,
                    Looper.getMainLooper(),
                )
                locationManager.getLastKnownLocation(provider)?.let(::onLocationChanged)
            }.isSuccess
        if (registered) {
            listening = true
        }
    }

    override fun stop() {
        if (!listening) return
        listening = false
        runCatching { locationManager.removeUpdates(this) }
        _speedKmh.value = null
    }

    override fun onLocationChanged(location: Location) {
        if (!location.hasSpeed()) return
        _speedKmh.value = location.speed * 3.6f
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(
        provider: String?,
        status: Int,
        extras: Bundle?,
    ) = Unit

    override fun onProviderEnabled(provider: String) {
        start()
    }

    override fun onProviderDisabled(provider: String) {
        if (listening) {
            runCatching { locationManager.removeUpdates(this) }
            listening = false
        }
        _speedKmh.value = null
    }

    private fun resolveProvider(): String? =
        when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                // Network fixes rarely expose speed; GPS is required for triggers.
                null
            else -> null
        }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
