package com.visorcraft.radioshed.car

import kotlinx.coroutines.flow.StateFlow

/**
 * Reports the device's current ground speed for Drive Mode speed triggers.
 * Android uses GPS; desktop and iOS use [NullSpeedWatcher].
 */
interface SpeedWatcher {
    /** Current speed in km/h, or null when unavailable. */
    val speedKmh: StateFlow<Float?>

    fun start()

    fun stop()
}

/** No-op speed source for platforms without location APIs. */
class NullSpeedWatcher : SpeedWatcher {
    override val speedKmh: StateFlow<Float?> =
        kotlinx.coroutines.flow.MutableStateFlow(null)

    override fun start() = Unit

    override fun stop() = Unit
}
