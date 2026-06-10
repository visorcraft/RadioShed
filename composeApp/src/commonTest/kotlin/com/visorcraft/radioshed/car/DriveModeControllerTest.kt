package com.visorcraft.radioshed.car

import com.russhwolf.settings.Settings
import com.visorcraft.radioshed.data.RadioShedJson
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.domain.DriveModeTrigger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [DriveModeController]. A fake [BluetoothWatcher] over a
 * [MutableStateFlow] lets each test push connection state; a real
 * [SettingsStore] over an in-memory [Settings] exercises the actual JSON
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

    /** Minimal in-memory [Settings], mirroring SettingsStoreBackupTest's helper. */
    private class InMemorySettings : Settings {
        private val data = mutableMapOf<String, Any>()
        override val keys: Set<String> get() = data.keys.toSet()
        override val size: Int get() = data.size
        override fun clear() = data.clear()
        override fun remove(key: String) { data.remove(key) }
        override fun hasKey(key: String): Boolean = key in data
        override fun putInt(key: String, value: Int) { data[key] = value }
        override fun getInt(key: String, defaultValue: Int) = (data[key] as? Int) ?: defaultValue
        override fun getIntOrNull(key: String) = data[key] as? Int
        override fun putLong(key: String, value: Long) { data[key] = value }
        override fun getLong(key: String, defaultValue: Long) = (data[key] as? Long) ?: defaultValue
        override fun getLongOrNull(key: String) = data[key] as? Long
        override fun putString(key: String, value: String) { data[key] = value }
        override fun getString(key: String, defaultValue: String) = (data[key] as? String) ?: defaultValue
        override fun getStringOrNull(key: String) = data[key] as? String
        override fun putFloat(key: String, value: Float) { data[key] = value }
        override fun getFloat(key: String, defaultValue: Float) = (data[key] as? Float) ?: defaultValue
        override fun getFloatOrNull(key: String) = data[key] as? Float
        override fun putDouble(key: String, value: Double) { data[key] = value }
        override fun getDouble(key: String, defaultValue: Double) = (data[key] as? Double) ?: defaultValue
        override fun getDoubleOrNull(key: String) = data[key] as? Double
        override fun putBoolean(key: String, value: Boolean) { data[key] = value }
        override fun getBoolean(key: String, defaultValue: Boolean) = (data[key] as? Boolean) ?: defaultValue
        override fun getBooleanOrNull(key: String) = data[key] as? Boolean
    }

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

    private fun encodeTriggers(triggers: List<DriveModeTrigger>): String =
        RadioShedJson.encodeToString(ListSerializer(DriveModeTrigger.serializer()), triggers)

    @Test
    fun btConnectMatchingTriggerActivates() = runTest {
        val watcher = FakeWatcher()
        val settings = store()
        settings.setDriveTriggers(encodeTriggers(listOf(DriveModeTrigger.BtDevice("AA:BB", "Car"))))
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
        settings.setDriveTriggers(encodeTriggers(listOf(DriveModeTrigger.BtDevice("AA:BB", "Car"))))
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
        settings.setDriveTriggers(encodeTriggers(listOf(DriveModeTrigger.BtDevice("AA:BB", "Car"))))
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
        settings.setDriveTriggers(encodeTriggers(listOf(DriveModeTrigger.Schedule(540, 1020))))
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
        settings.setDriveTriggers(encodeTriggers(listOf(DriveModeTrigger.Schedule(1380, 60))))
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
        settings.setDriveTriggers(encodeTriggers(listOf(DriveModeTrigger.BtDevice("AA:BB", "Car"))))
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
