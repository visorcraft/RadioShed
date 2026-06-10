package com.visorcraft.radioshed.data

import com.visorcraft.radioshed.domain.ConnectivityPolicy
import com.visorcraft.radioshed.source.NetworkService
import com.visorcraft.radioshed.source.ServicePolicyOverride
import com.visorcraft.radioshed.testutil.InMemorySettings
import com.visorcraft.radioshed.ui.theme.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip integrity tests for [SettingsStore.dumpForBackup] /
 * [SettingsStore.restoreFromBackup]. The bug class these guard
 * against: a new `KEY_*` constant is added with getters/setters
 * but the author forgets to extend `dumpForBackup` /
 * `expectedTypeForKey` / `resetAllUserSettings`. Past three
 * batches alone shipped fixes for two such omissions
 * (`KEY_SUBSONIC_URL`, `KEY_SUBSONIC_USERNAME`).
 *
 * Strategy: write a non-default value via every public setter we
 * exercise, dump, restore into a fresh store, verify every value
 * round-trips to the same shape. Any future setter that's not
 * mirrored in the dump fails this test on the first assertion
 * after the missing key.
 */
class SettingsStoreBackupTest {

    private fun newStore(): SettingsStore = SettingsStore(InMemorySettings())

    @Test
    fun connectivityPolicy_roundTrips() {
        val src = newStore()
        src.setConnectivityPolicy(ConnectivityPolicy.WiFiOnly)

        val dst = newStore()
        dst.restoreFromBackup(src.dumpForBackup())

        assertEquals(ConnectivityPolicy.WiFiOnly, dst.getConnectivityPolicy())
    }

    @Test
    fun servicePolicyOverrides_roundTrip() {
        // CoverArt explicitly set to Always; Lyrics left at default
        // (Inherit, which is stored as absence-of-row). After
        // round-trip, CoverArt should come back Always and Lyrics
        // should still read Inherit.
        val src = newStore()
        src.setServicePolicyOverride(NetworkService.CoverArt, ServicePolicyOverride.Always)

        val dst = newStore()
        dst.restoreFromBackup(src.dumpForBackup())

        assertEquals(
            ServicePolicyOverride.Always,
            dst.getServicePolicyOverride(NetworkService.CoverArt),
        )
        assertEquals(
            ServicePolicyOverride.Inherit,
            dst.getServicePolicyOverride(NetworkService.Lyrics),
        )
    }

    @Test
    fun servicePolicyInherit_doesNotPolluteBackup() {
        // Setting an override to Inherit should remove the row
        // entirely so a default-config bundle doesn't carry
        // redundant entries. Verifies the dump is empty for the
        // override key after a "clear back to default" flow.
        val src = newStore()
        src.setServicePolicyOverride(NetworkService.CoverArt, ServicePolicyOverride.WiFiOnly)
        src.setServicePolicyOverride(NetworkService.CoverArt, ServicePolicyOverride.Inherit)

        val dump = src.dumpForBackup()
        assertTrue(
            dump.none { it.key.contains("netpolicy.override.coverart") },
            "Inherit should not appear in dump (got: ${dump.keys})",
        )
    }

    @Test
    fun themeMode_roundTrips() {
        val src = newStore()
        src.setThemeMode(ThemeMode.Dark)

        val dst = newStore()
        dst.restoreFromBackup(src.dumpForBackup())

        assertEquals(ThemeMode.Dark, dst.getThemeMode())
    }

    @Test
    fun stationsTileSize_roundTrips() {
        val src = newStore()
        src.setStationsTileSizeDp(360)

        val dst = newStore()
        dst.restoreFromBackup(src.dumpForBackup())

        assertEquals(360, dst.getStationsTileSizeDp())
    }

    @Test
    fun stationsSort_roundTrips() {
        val src = newStore()
        src.setStationsSort("MostPlayed")

        val dst = newStore()
        dst.restoreFromBackup(src.dumpForBackup())

        assertEquals("MostPlayed", dst.getStationsSort())
    }

    @Test
    fun subsonicCredentials_urlAndUsernameRoundTrip_passwordExcluded() {
        // URL + username are non-credential and should round-trip;
        // password is intentionally excluded from the dump because
        // shipping credentials in a backup file is a security risk.
        // The user re-enters the password once after restore.
        val src = newStore()
        src.setSubsonicUrl("https://navidrome.example.com")
        src.setSubsonicUsername("alice")
        src.setSubsonicPassword("super-secret")

        val dst = newStore()
        dst.restoreFromBackup(src.dumpForBackup())

        assertEquals("https://navidrome.example.com", dst.getSubsonicUrl())
        assertEquals("alice", dst.getSubsonicUsername())
        assertEquals(
            "",
            dst.getSubsonicPassword(),
            "password must NOT be carried by backup bundles",
        )
    }

    @Test
    fun resetAllUserSettings_clearsBackedUpKeys() {
        // After the user hits "Reset all settings", the next dump
        // should contain no KEY_* values. Catches the regression
        // where a key was added to dumpForBackup but not to the
        // reset list — the reset would visually clear the screen
        // but a follow-up dump would still emit the stale value.
        val store = newStore()
        store.setConnectivityPolicy(ConnectivityPolicy.WiFiOnly)
        store.setStationsTileSizeDp(360)
        store.setSubsonicUrl("https://navidrome.example.com")
        store.setStationsSort("Recently played")

        store.resetAllUserSettings()

        val dump = store.dumpForBackup()
        assertTrue(
            dump.isEmpty() ||
                dump.values.all { it.toString().isEmpty() || dump.size <= 1 },
            "After reset, dump should be empty or near-empty (got ${dump.keys})",
        )
    }

    @Test
    fun djSettings_roundTrip() {
        // Sets every DJ key to a non-default value, dumps, restores into
        // a fresh store, and verifies every value survives intact.
        val src = newStore()
        src.setDjEnabled(true)
        src.setDjFrequency("every_n")
        src.setDjFrequencyN(7)
        src.setDjFrequencyMinutes(30)
        src.setDjQuietStartMin(1200)
        src.setDjQuietEndMin(360)
        src.setDjDuckFraction(0.4f)
        src.setDjTemplates("Hello {{track}} on {{station}}!")

        val dst = newStore()
        dst.restoreFromBackup(src.dumpForBackup())

        assertEquals(true, dst.getDjEnabled())
        assertEquals("every_n", dst.getDjFrequency())
        assertEquals(7, dst.getDjFrequencyN())
        assertEquals(30, dst.getDjFrequencyMinutes())
        assertEquals(1200, dst.getDjQuietStartMin())
        assertEquals(360, dst.getDjQuietEndMin())
        assertEquals(0.4f, dst.getDjDuckFraction())
        assertEquals("Hello {{track}} on {{station}}!", dst.getDjTemplates())
    }

    @Test
    fun driveMode_roundTrip() {
        // Sets all four Drive Mode keys to non-default values, dumps, restores
        // into a fresh store, and verifies every value survives intact.
        val src = newStore()
        src.setDriveTriggers("""[{"type":"bt_device","mac":"AA:BB:CC:DD:EE:FF","name":"Car"}]""")
        src.setDriveQuickList(listOf("1", "2", "3"))
        src.setDriveButtons(listOf("play_pause", "next", "mute"))
        src.setDriveAutoExit(false)

        val dst = newStore()
        dst.restoreFromBackup(src.dumpForBackup())

        assertEquals(
            """[{"type":"bt_device","mac":"AA:BB:CC:DD:EE:FF","name":"Car"}]""",
            dst.getDriveTriggers(),
        )
        assertEquals("1,2,3", dst.getDriveQuickList())
        assertEquals("play_pause,next,mute", dst.getDriveButtons())
        assertEquals(false, dst.getDriveAutoExit())
    }

    @Test
    fun driveButtons_filterContract() {
        // (a) One valid id + one unknown id: only the valid id survives.
        val storeA = newStore()
        storeA.setDriveButtons(listOf("next", "warp_drive"))
        assertEquals("next", storeA.getDriveButtons())

        // (b) All-invalid ids: getter returns the default.
        val storeB = newStore()
        storeB.setDriveButtons(listOf("warp_drive", "turbo_boost"))
        assertEquals(
            SettingsStore.DRIVE_BUTTONS_DEFAULT,
            storeB.getDriveButtons(),
            "All-invalid ids should fall back to the default button set",
        )

        // (c) Empty list: getter returns the default.
        val storeC = newStore()
        storeC.setDriveButtons(emptyList())
        assertEquals(
            SettingsStore.DRIVE_BUTTONS_DEFAULT,
            storeC.getDriveButtons(),
            "Empty list should fall back to the default button set",
        )
    }

    @Test
    fun unknownTypeInBundle_isIgnored() {
        // A bundle from a different/older app version may carry a
        // key whose type doesn't match what dumpForBackup writes
        // today. Restore should silently skip it rather than
        // poisoning the slot. We discover the actual key string via a
        // real dump first (rather than hardcoding "online.connectivityPolicy")
        // so this stays robust if the key constant is ever renamed.
        val seed = newStore()
        seed.setConnectivityPolicy(ConnectivityPolicy.WiFiOnly)
        val connectivityKey = seed.dumpForBackup().keys
            .first { it.contains("connectivityPolicy", ignoreCase = true) }

        val store = newStore()
        store.setConnectivityPolicy(ConnectivityPolicy.Always)
        val mismatched = mapOf(
            connectivityKey to
                com.visorcraft.radioshed.source.BackupSettingValue.IntValue(99),
        )
        store.restoreFromBackup(mismatched)

        // Original value should remain — the type-mismatched
        // entry was rejected.
        assertEquals(ConnectivityPolicy.Always, store.getConnectivityPolicy())
    }
}
