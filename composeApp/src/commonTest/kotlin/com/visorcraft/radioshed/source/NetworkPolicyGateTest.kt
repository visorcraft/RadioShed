package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.domain.ConnectivityPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkPolicyGateTest {
    private class FakeSettings(
        var policy: ConnectivityPolicy = ConnectivityPolicy.Always,
        val overrides: MutableMap<NetworkService, ServicePolicyOverride> = mutableMapOf(),
    ) : PolicySettingsSource {
        override fun getConnectivityPolicy(): ConnectivityPolicy = policy

        override fun getServicePolicyOverride(service: NetworkService): ServicePolicyOverride =
            overrides[service] ?: ServicePolicyOverride.Inherit
    }

    private class FakeChecker(
        var online: Boolean = true,
        var unmetered: Boolean = true,
    ) : ConnectivityChecker {
        override fun isOnline(): Boolean = online

        override fun isUnmetered(): Boolean = unmetered
    }

    // ---- Global policy ----------------------------------------------------

    @Test
    fun always_allowsBothFetchKinds_whenOnline() {
        val gate =
            NetworkPolicyGate(
                FakeSettings(policy = ConnectivityPolicy.Always),
                FakeChecker(online = true),
            )
        assertTrue(gate.allowAutomaticFetch())
        assertTrue(gate.allowUserInitiatedFetch())
    }

    @Test
    fun always_blocksBothFetchKinds_whenOffline() {
        val gate =
            NetworkPolicyGate(
                FakeSettings(policy = ConnectivityPolicy.Always),
                FakeChecker(online = false),
            )
        assertFalse(gate.allowAutomaticFetch())
        assertFalse(gate.allowUserInitiatedFetch())
    }

    @Test
    fun wifiOnly_blocksMeteredConnections() {
        val gate =
            NetworkPolicyGate(
                FakeSettings(policy = ConnectivityPolicy.WiFiOnly),
                FakeChecker(online = true, unmetered = false),
            )
        assertFalse(gate.allowAutomaticFetch())
        assertFalse(gate.allowUserInitiatedFetch())
    }

    @Test
    fun wifiOnly_allowsWhenUnmetered() {
        val gate =
            NetworkPolicyGate(
                FakeSettings(policy = ConnectivityPolicy.WiFiOnly),
                FakeChecker(online = true, unmetered = true),
            )
        assertTrue(gate.allowAutomaticFetch())
        assertTrue(gate.allowUserInitiatedFetch())
    }

    @Test
    fun never_blocksEverything() {
        val gate =
            NetworkPolicyGate(
                FakeSettings(policy = ConnectivityPolicy.Never),
                FakeChecker(online = true, unmetered = true),
            )
        assertFalse(gate.allowAutomaticFetch())
        assertFalse(gate.allowUserInitiatedFetch())
    }

    @Test
    fun manual_blocksAutomatic_butAllowsUserInitiated() {
        // Manual is "ask each time" — automatic background fetches
        // shouldn't fire, but the user explicitly hitting a
        // refresh button should go through. This was a regression
        // path before the per-service work — the old code blocked
        // both equally.
        val gate =
            NetworkPolicyGate(
                FakeSettings(policy = ConnectivityPolicy.Manual),
                FakeChecker(online = true),
            )
        assertFalse(gate.allowAutomaticFetch())
        assertTrue(gate.allowUserInitiatedFetch())
    }

    @Test
    fun manual_blocksUserInitiated_whenOffline() {
        val gate =
            NetworkPolicyGate(
                FakeSettings(policy = ConnectivityPolicy.Manual),
                FakeChecker(online = false),
            )
        assertFalse(gate.allowAutomaticFetch())
        assertFalse(gate.allowUserInitiatedFetch())
    }

    // ---- Per-service overrides --------------------------------------------

    @Test
    fun coverArtOverride_neverBlocksCoverArt_evenWhenGlobalAllows() {
        // Global says fetch everything, but the user has
        // explicitly capped CoverArt with a Never override.
        val gate =
            NetworkPolicyGate(
                FakeSettings(
                    policy = ConnectivityPolicy.Always,
                    overrides =
                        mutableMapOf(
                            NetworkService.CoverArt to ServicePolicyOverride.Never,
                        ),
                ),
                FakeChecker(online = true),
            )
        assertFalse(gate.allowUserInitiatedFetch(NetworkService.CoverArt))
        assertFalse(gate.allowAutomaticFetch(NetworkService.CoverArt))
        // Lyrics still inherits the global policy so it's allowed.
        assertTrue(gate.allowUserInitiatedFetch(NetworkService.Lyrics))
    }

    @Test
    fun lyricsOverride_alwaysBypassesGlobalNever() {
        // Global Never, but the user wants lyrics specifically.
        // Override should win.
        val gate =
            NetworkPolicyGate(
                FakeSettings(
                    policy = ConnectivityPolicy.Never,
                    overrides =
                        mutableMapOf(
                            NetworkService.Lyrics to ServicePolicyOverride.Always,
                        ),
                ),
                FakeChecker(online = true),
            )
        assertTrue(gate.allowUserInitiatedFetch(NetworkService.Lyrics))
        assertTrue(gate.allowAutomaticFetch(NetworkService.Lyrics))
        // CoverArt still inherits the Never global.
        assertFalse(gate.allowUserInitiatedFetch(NetworkService.CoverArt))
    }

    @Test
    fun inheritOverride_followsGlobalPolicy() {
        val settings =
            FakeSettings(
                policy = ConnectivityPolicy.WiFiOnly,
                overrides =
                    mutableMapOf(
                        NetworkService.CoverArt to ServicePolicyOverride.Inherit,
                    ),
            )
        val gate = NetworkPolicyGate(settings, FakeChecker(online = true, unmetered = false))
        assertFalse(gate.allowUserInitiatedFetch(NetworkService.CoverArt))
        // Switch global to Always — inherit should reflect it.
        settings.policy = ConnectivityPolicy.Always
        assertTrue(gate.allowUserInitiatedFetch(NetworkService.CoverArt))
    }

    @Test
    fun resolveEffectivePolicy_returnsOverride_whenSet() {
        val gate =
            NetworkPolicyGate(
                FakeSettings(
                    policy = ConnectivityPolicy.Always,
                    overrides =
                        mutableMapOf(
                            NetworkService.CoverArt to ServicePolicyOverride.WiFiOnly,
                        ),
                ),
                FakeChecker(),
            )
        assertEquals(
            ConnectivityPolicy.WiFiOnly,
            gate.resolveEffectivePolicy(NetworkService.CoverArt),
        )
        assertEquals(
            ConnectivityPolicy.Always,
            gate.resolveEffectivePolicy(NetworkService.Lyrics),
        )
    }

    @Test
    fun servicePolicyOverride_fromStorage_unknownString_returnsInherit() {
        // Forward-compat — a future override value name read from
        // an older app version should fall back to the safe default.
        assertEquals(
            ServicePolicyOverride.Inherit,
            ServicePolicyOverride.fromStorage("FutureValueXyz"),
        )
    }
}
