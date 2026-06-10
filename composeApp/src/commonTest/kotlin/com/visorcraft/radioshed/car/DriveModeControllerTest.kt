package com.visorcraft.radioshed.car

import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.domain.DriveModeTrigger
import com.visorcraft.radioshed.domain.encodeDriveTriggers
import com.visorcraft.radioshed.testutil.InMemorySettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [DriveModeController]. A fake [BluetoothWatcher] over a
 * [MutableStateFlow] lets each test push connection state; a real
 * [SettingsStore] over an [InMemorySettings] exercises the actual JSON
 * trigger round-trip; a manually-pumped ticker [MutableSharedFlow] plus a
 * mutable `nowMinutesOfDay` lambda make schedule evaluation fully
 * deterministic under virtual time (no real delays).
 *
 * The controller mirrors its state through [SettingsStore.setDriveMode] so the
 * existing Now Playing / Settings consumers keep working; `active.value` and
 * `getDriveMode()` are therefore always asserted together.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveModeControllerTest {

    /** Watcher backed by a test-controlled flow. */
    private class FakeWatcher : BluetoothWatcher {
        val devices = MutableStateFlow<Set<BtPeer>>(emptySet())
        override val connectedDevices: StateFlow<Set<BtPeer>> = devices.asStateFlow()
        var started = false
        override fun start() { started = true }
        override fun stop() { started = false }
        override fun bondedDevices(): List<BtPeer> = emptyList()
    }

    private fun store(): SettingsStore = SettingsStore(InMemorySettings())

    @Test
    fun btConnectMatchingTriggerActivates() = runTest {
        val watcher = FakeWatcher()
        val settings = store()
        settings.setDriveTriggers(encodeDriveTriggers(listOf(DriveModeTrigger.BtDevice("AA:BB", "Car"))))
        val controller = DriveModeController(
            watcher = watcher,
            settings = settings,
            scope = backgroundScope,
            ticker = MutableSharedFlow(),
            nowMinutesOfDay = { 0 },
        )
        controller.start()
        runCurrent()

        assertFalse(controller.active.value)

        watcher.devices.value = setOf(BtPeer("AA:BB", "Car"))
        runCurrent()

        assertTrue(controller.active.value)
        assertTrue(settings.getDriveMode())
    }

    @Test
    fun btDisconnectWithAutoExitDeactivates() = runTest {
        val watcher = FakeWatcher()
        val settings = store()
        settings.setDriveTriggers(encodeDriveTriggers(listOf(DriveModeTrigger.BtDevice("AA:BB", "Car"))))
        settings.setDriveAutoExit(true)
        val controller = DriveModeController(watcher, settings, backgroundScope, MutableSharedFlow()) { 0 }
        controller.start()
        runCurrent()

        watcher.devices.value = setOf(BtPeer("AA:BB", "Car"))
        runCurrent()
        assertTrue(controller.active.value)

        watcher.devices.value = emptySet()
        runCurrent()
        assertFalse(controller.active.value)
        assertFalse(settings.getDriveMode())
    }

    @Test
    fun autoExitOffStaysActive() = runTest {
        val watcher = FakeWatcher()
        val settings = store()
        settings.setDriveTriggers(encodeDriveTriggers(listOf(DriveModeTrigger.BtDevice("AA:BB", "Car"))))
        settings.setDriveAutoExit(false)
        val controller = DriveModeController(watcher, settings, backgroundScope, MutableSharedFlow()) { 0 }
        controller.start()
        runCurrent()

        watcher.devices.value = setOf(BtPeer("AA:BB", "Car"))
        runCurrent()
        assertTrue(controller.active.value)

        watcher.devices.value = emptySet()
        runCurrent()
        // Auto-exit disabled: a falling edge does NOT exit Drive Mode.
        assertTrue(controller.active.value)
        assertTrue(settings.getDriveMode())
    }

    @Test
    fun scheduleWindowActivates() = runTest {
        val watcher = FakeWatcher()
        val settings = store()
        settings.setDriveTriggers(encodeDriveTriggers(listOf(DriveModeTrigger.Schedule(540, 1020))))
        val ticker = MutableSharedFlow<Unit>()
        var now = 600 // 10:00, inside 09:00..17:00
        val controller = DriveModeController(watcher, settings, backgroundScope, ticker) { now }
        controller.start()
        runCurrent()

        // First tick evaluates the window at now=600 → active.
        ticker.emit(Unit)
        runCurrent()
        assertTrue(controller.active.value)
        assertTrue(settings.getDriveMode())
    }

    @Test
    fun scheduleCrossMidnightActivates() = runTest {
        val watcher = FakeWatcher()
        val settings = store()
        settings.setDriveTriggers(encodeDriveTriggers(listOf(DriveModeTrigger.Schedule(1380, 60))))
        val ticker = MutableSharedFlow<Unit>()
        var now = 30 // 00:30, inside 23:00..01:00 (crosses midnight)
        val controller = DriveModeController(watcher, settings, backgroundScope, ticker) { now }
        controller.start()
        runCurrent()
        ticker.emit(Unit)
        runCurrent()
        assertTrue(controller.active.value)

        // Move outside the window; auto-exit defaults true → falling edge exits.
        now = 720 // 12:00
        ticker.emit(Unit)
        runCurrent()
        assertFalse(controller.active.value)
    }

    @Test
    fun manualToggleOverridesUntilNextEdge() = runTest {
        // Semantics under test: the controller only writes Drive Mode on a
        // trigger EDGE (no-trigger -> trigger, or trigger -> no-trigger). It
        // never continuously stomps the state between edges. So when no trigger
        // is active and the user turns Drive Mode ON manually, the controller
        // leaves it on; a later genuine trigger edge resumes normal control.
        val watcher = FakeWatcher()
        val settings = store()
        settings.setDriveTriggers(encodeDriveTriggers(listOf(DriveModeTrigger.BtDevice("AA:BB", "Car"))))
        settings.setDriveAutoExit(true)
        val ticker = MutableSharedFlow<Unit>()
        val controller = DriveModeController(watcher, settings, backgroundScope, ticker) { 0 }
        controller.start()
        runCurrent()
        assertFalse(controller.active.value)

        // User turns it on by hand while no trigger is active.
        settings.setDriveMode(true)
        // The controller must not immediately stomp it back to false; a ticker
        // pass with no active trigger is NOT a falling edge (state was already
        // "no trigger"), so nothing is written.
        ticker.emit(Unit)
        runCurrent()
        assertTrue(settings.getDriveMode())

        // A genuine rising edge resumes control (re-asserts active).
        watcher.devices.value = setOf(BtPeer("AA:BB", "Car"))
        runCurrent()
        assertTrue(settings.getDriveMode())

        // ...and the matching falling edge now turns it back off.
        watcher.devices.value = emptySet()
        runCurrent()
        assertFalse(settings.getDriveMode())
    }
}
