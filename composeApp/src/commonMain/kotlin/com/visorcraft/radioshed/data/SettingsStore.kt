package com.visorcraft.radioshed.data

import com.russhwolf.settings.Settings
import com.visorcraft.radioshed.domain.ConnectivityPolicy
import com.visorcraft.radioshed.ui.theme.ThemeMode

/**
 * Type-safe wrapper around multiplatform-settings for app preferences.
 * Each setting has a stable string key and a default value. Unknown stored
 * values fall back to defaults rather than throwing.
 */
class SettingsStore(private val settings: Settings) {

    // ---- UI / theme ----

    fun getThemeMode(): ThemeMode {
        val raw = settings.getStringOrNull(KEY_THEME_MODE) ?: return ThemeMode.System
        return runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.System)
    }

    fun setThemeMode(mode: ThemeMode) {
        settings.putString(KEY_THEME_MODE, mode.name)
    }

    fun getMaterialYouEnabled(): Boolean = settings.getBoolean(KEY_MATERIAL_YOU, false)
    fun setMaterialYouEnabled(enabled: Boolean) = settings.putBoolean(KEY_MATERIAL_YOU, enabled)

    fun getReduceMotion(): Boolean = settings.getBoolean(KEY_REDUCE_MOTION, false)
    fun setReduceMotion(enabled: Boolean) = settings.putBoolean(KEY_REDUCE_MOTION, enabled)

    // ---- Connectivity ----

    fun getConnectivityPolicy(): ConnectivityPolicy {
        val raw = settings.getStringOrNull(KEY_CONNECTIVITY_POLICY)
            ?: return ConnectivityPolicy.Manual
        return runCatching { ConnectivityPolicy.valueOf(raw) }.getOrDefault(ConnectivityPolicy.Manual)
    }

    fun setConnectivityPolicy(policy: ConnectivityPolicy) {
        settings.putString(KEY_CONNECTIVITY_POLICY, policy.name)
    }

    // ---- Onboarding state ----

    fun isOnboardingComplete(): Boolean = settings.getBoolean(KEY_ONBOARDING_DONE, false)
    fun setOnboardingComplete(done: Boolean) = settings.putBoolean(KEY_ONBOARDING_DONE, done)

    // ---- Last station ----

    fun getLastStationId(): Long? =
        if (settings.hasKey(KEY_LAST_STATION_ID)) settings.getLong(KEY_LAST_STATION_ID, -1L)
            .takeIf { it >= 0 } else null

    fun setLastStationId(id: Long?) {
        if (id == null) settings.remove(KEY_LAST_STATION_ID)
        else settings.putLong(KEY_LAST_STATION_ID, id)
    }

    // ---- File-mode browser opt-in ----

    fun getFileModeBrowserEnabled(): Boolean =
        settings.getBoolean(KEY_FILE_MODE_BROWSER, false)

    fun setFileModeBrowserEnabled(enabled: Boolean) =
        settings.putBoolean(KEY_FILE_MODE_BROWSER, enabled)

    private companion object {
        const val KEY_THEME_MODE = "ui.themeMode"
        const val KEY_MATERIAL_YOU = "ui.materialYou"
        const val KEY_REDUCE_MOTION = "ui.reduceMotion"
        const val KEY_CONNECTIVITY_POLICY = "online.connectivityPolicy"
        const val KEY_ONBOARDING_DONE = "onboarding.complete"
        const val KEY_LAST_STATION_ID = "playback.lastStationId"
        const val KEY_FILE_MODE_BROWSER = "library.fileModeBrowser"
    }
}
