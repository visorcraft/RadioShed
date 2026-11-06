package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.domain.ConnectivityPolicy

interface ConnectivityChecker {
    fun isOnline(): Boolean

    fun isUnmetered(): Boolean
}

/**
 * Narrow surface the gate needs from the settings store. Production
 * implementation is [SettingsStore] (see the implicit conversion
 * below); tests use a tiny fake so they don't have to spin up the
 * multiplatform-settings backend.
 */
interface PolicySettingsSource {
    fun getConnectivityPolicy(): ConnectivityPolicy

    fun getServicePolicyOverride(service: NetworkService): ServicePolicyOverride
}

/**
 * Online-fetch gate consulted by every network-touching path
 * (artwork, lyrics, MusicBrainz, ListenBrainz, Subsonic). The
 * global [ConnectivityPolicy] setting (Always / Wi-Fi only /
 * Never / Manual) is the default; per-service overrides let the
 * user say "artwork over cellular is fine but never fetch lyrics
 * outside Wi-Fi" without flipping the whole gate.
 */
class NetworkPolicyGate(
    private val settings: PolicySettingsSource,
    private val checker: ConnectivityChecker,
) {
    /**
     * Convenience constructor — production code passes the concrete
     * [SettingsStore]; the [PolicySettingsSource] adapter wraps it
     * with the two methods the gate needs.
     */
    constructor(settingsStore: SettingsStore, checker: ConnectivityChecker) :
        this(adaptSettings(settingsStore), checker)

    fun allowAutomaticFetch(): Boolean =
        allow(
            policy = settings.getConnectivityPolicy(),
            userInitiated = false,
        )

    fun allowUserInitiatedFetch(): Boolean =
        allow(
            policy = settings.getConnectivityPolicy(),
            userInitiated = true,
        )

    /**
     * Service-aware check. Resolves the per-service override (if
     * the user set one) against the global policy via
     * [resolveEffectivePolicy], then evaluates connectivity.
     */
    fun allowAutomaticFetch(service: NetworkService): Boolean =
        allow(
            policy = resolveEffectivePolicy(service),
            userInitiated = false,
        )

    fun allowUserInitiatedFetch(service: NetworkService): Boolean =
        allow(
            policy = resolveEffectivePolicy(service),
            userInitiated = true,
        )

    /**
     * The effective policy for [service] after applying the per-
     * service override. `Inherit` (or no override stored) returns
     * the global policy; any other override replaces it.
     */
    fun resolveEffectivePolicy(service: NetworkService): ConnectivityPolicy {
        val override = settings.getServicePolicyOverride(service)
        return when (override) {
            ServicePolicyOverride.Inherit -> settings.getConnectivityPolicy()
            ServicePolicyOverride.Always -> ConnectivityPolicy.Always
            ServicePolicyOverride.WiFiOnly -> ConnectivityPolicy.WiFiOnly
            ServicePolicyOverride.Never -> ConnectivityPolicy.Never
        }
    }

    private fun allow(
        policy: ConnectivityPolicy,
        userInitiated: Boolean,
    ): Boolean =
        when (policy) {
            ConnectivityPolicy.Always -> checker.isOnline()
            ConnectivityPolicy.WiFiOnly -> checker.isOnline() && checker.isUnmetered()
            ConnectivityPolicy.Never -> false
            // Manual blocks background/automatic fetches but the user
            // explicitly tapping a "fetch now" affordance is allowed.
            ConnectivityPolicy.Manual -> userInitiated && checker.isOnline()
        }
}

/**
 * Online services the user can independently gate. Adding a new
 * service: extend this enum, add a `KEY_NETPOLICY_OVERRIDE_<X>`
 * to `SettingsStore`, surface a row in App Settings → Online,
 * and pass the new entry into the gate from the caller.
 */
enum class NetworkService {
    /** Cover Art Archive lookups (album cover backfill). */
    CoverArt,

    /** LRClib lyrics fetches. */
    Lyrics,
}

/**
 * Per-service override values. `Inherit` defers to the global
 * `ConnectivityPolicy` setting; the other three replace it for
 * that specific service. Stored as a String value in
 * [SettingsStore] so future additions don't require a schema
 * migration.
 */
enum class ServicePolicyOverride {
    Inherit,
    Always,
    WiFiOnly,
    Never,
    ;

    companion object {
        fun fromStorage(value: String): ServicePolicyOverride = entries.firstOrNull { it.name == value } ?: Inherit
    }
}

/**
 * Adapter that lets a real [SettingsStore] play the
 * [PolicySettingsSource] role without making SettingsStore
 * implement the interface (it lives in `data/`, while the
 * interface lives next to its only consumer, NetworkPolicyGate).
 */
private fun adaptSettings(store: SettingsStore): PolicySettingsSource =
    object : PolicySettingsSource {
        override fun getConnectivityPolicy(): ConnectivityPolicy = store.getConnectivityPolicy()

        override fun getServicePolicyOverride(service: NetworkService): ServicePolicyOverride =
            store.getServicePolicyOverride(service)
    }
