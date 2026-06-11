package com.visorcraft.radioshed.car

import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.data.localMinutesOfDay
import com.visorcraft.radioshed.data.nowMillis
import com.visorcraft.radioshed.domain.DriveModeTrigger
import com.visorcraft.radioshed.domain.decodeDriveTriggers
import com.visorcraft.radioshed.util.isInMinuteWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drives Drive Mode automatically from the user's configured
 * [DriveModeTrigger]s — a connected Bluetooth device and/or a scheduled
 * time window.
 *
 * ## State model: edge-triggered, never continuous
 * The controller derives one boolean "any trigger active?" signal by OR-ing:
 *  - **Bluetooth**: any connected peer's mac matches a [DriveModeTrigger.BtDevice].
 *  - **Schedule**: the current minute-of-day falls inside any
 *    [DriveModeTrigger.Schedule] window (via [isInMinuteWindow], re-evaluated
 *    on each [ticker] pass — once a minute in production).
 *
 * It writes Drive Mode **only on an edge** of that signal:
 *  - **Rising edge** (no trigger → trigger active): set Drive Mode ON.
 *  - **Falling edge** (trigger active → none): set Drive Mode OFF, but only
 *    when [SettingsStore.getDriveAutoExit] is enabled. With auto-exit off the
 *    falling edge is ignored and Drive Mode stays on until the user exits.
 *
 * Between edges the controller is silent. This is what makes a **manual**
 * toggle stick: if the user turns Drive Mode on by hand while no trigger is
 * active, no edge fires, so the controller never stomps it back. Normal
 * automatic control resumes on the next genuine trigger edge.
 *
 * State is mirrored through [SettingsStore.setDriveMode] (which also updates
 * `driveModeFlow`) so every existing consumer — Now Playing's re-skin, the
 * Settings toggle — observes the change with no extra wiring. [active] simply
 * re-exposes the authoritative `driveModeFlow`, so it stays in sync even when
 * Drive Mode is toggled externally (e.g. the Settings switch).
 *
 * [start]/[stop] are idempotent. The controller owns no scope; the caller
 * injects one (the app passes a long-lived [SupervisorJob]-backed scope).
 */
class DriveModeController(
    private val watcher: BluetoothWatcher,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val ticker: Flow<Unit> = oneMinuteTicker(),
    private val nowMinutesOfDay: () -> Int = { localMinutesOfDay(nowMillis()) },
) {

    /**
     * Mirrors the authoritative [SettingsStore.driveModeFlow], so it reflects
     * BOTH controller edge writes and external toggles (the Settings switch).
     * Most consumers already observe `driveModeFlow` directly; this is the same
     * signal re-exposed for callers holding the controller.
     */
    val active: StateFlow<Boolean> = settings.driveModeFlow

    private val mutex = Mutex()

    // The last "any trigger active?" value we saw, so we can detect edges.
    // Seeded false: a controller starting with a trigger already active sees a
    // rising edge on its first evaluation and turns Drive Mode on.
    private var lastTriggerActive = false

    // Decoded triggers cached against the raw JSON blob they came from. The
    // raw settings read stays in every evaluate() pass — it's cheap, and it's
    // what makes edits from the Drive Mode settings screen take effect on the
    // next pass with no invalidation plumbing — but the kotlinx-serialization
    // decode would otherwise run once a minute for the process lifetime, so it
    // only reruns when the blob actually changed. Guarded by [mutex] like the
    // rest of the mutable state. Seeded null so the first pass always decodes
    // (a stored blob is never null).
    private var cachedTriggersJson: String? = null
    private var cachedTriggers: List<DriveModeTrigger> = emptyList()

    private var started = false

    // Collector jobs, tracked so stop() can cancel them and a later start()
    // doesn't stack a second pair on the injected scope.
    private var btJob: Job? = null
    private var tickerJob: Job? = null

    /** Idempotent: starts the watcher and the BT + schedule collectors. */
    fun start() {
        if (started) return
        started = true
        watcher.start()

        // Bluetooth: re-evaluate whenever the connected set changes.
        btJob = scope.launch {
            watcher.connectedDevices.collect { evaluate() }
        }
        // Schedule: re-evaluate on each ticker pass.
        tickerJob = scope.launch {
            ticker.collect { evaluate() }
        }
    }

    /** Idempotent: stops the watcher and cancels the collectors. */
    fun stop() {
        if (!started) return
        started = false
        watcher.stop()
        btJob?.cancel()
        btJob = null
        tickerJob?.cancel()
        tickerJob = null
    }

    /**
     * Recompute the combined trigger signal and act on an edge. Serialized so a
     * BT change and a ticker pass can't interleave and double-fire an edge.
     */
    private suspend fun evaluate() {
        mutex.withLock {
            val json = settings.getDriveTriggers()
            if (json != cachedTriggersJson) {
                cachedTriggers = decodeDriveTriggers(json)
                cachedTriggersJson = json
            }
            val triggers = cachedTriggers

            // No triggers configured and no prior trigger active: the combined
            // signal is constant false, so no edge can fire and the rest of
            // the body is a guaranteed no-op — skip it. The !lastTriggerActive
            // guard matters: when the user deletes the last trigger while one
            // was active, the falling edge below must still run so auto-exit
            // works. Manual toggles are unaffected either way — they never
            // pass through evaluate(); edge-only writes are what keep them
            // sticky (see the class KDoc).
            if (triggers.isEmpty() && !lastTriggerActive) return

            val connectedMacs = watcher.connectedDevices.value.map { it.mac }.toSet()
            val nowMin = nowMinutesOfDay()
            val triggerActive = triggers.any { trigger ->
                when (trigger) {
                    is DriveModeTrigger.BtDevice -> trigger.mac in connectedMacs
                    is DriveModeTrigger.Schedule -> nowMin.isInMinuteWindow(trigger.startMin, trigger.endMin)
                }
            }

            val rising = triggerActive && !lastTriggerActive
            val falling = !triggerActive && lastTriggerActive
            lastTriggerActive = triggerActive

            when {
                // setDriveMode updates driveModeFlow, which [active] re-exposes.
                rising -> settings.setDriveMode(true)
                falling && settings.getDriveAutoExit() -> settings.setDriveMode(false)
                // No edge, or a falling edge with auto-exit disabled: leave the
                // current (possibly manual) state alone.
            }
        }
    }

    companion object {
        /** Emits Unit once per minute. Injectable so tests pump it manually. */
        fun oneMinuteTicker(): Flow<Unit> = flow {
            while (true) {
                delay(60_000L)
                emit(Unit)
            }
        }
    }
}
