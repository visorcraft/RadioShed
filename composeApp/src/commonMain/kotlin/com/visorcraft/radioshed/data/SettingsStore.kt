package com.visorcraft.radioshed.data

import com.russhwolf.settings.Settings
import com.visorcraft.radioshed.domain.ConnectivityPolicy
import com.visorcraft.radioshed.ui.theme.ThemeMode

/**
 * Type-safe wrapper around multiplatform-settings for app preferences.
 * Each setting has a stable string key and a default value. Unknown stored
 * values fall back to defaults rather than throwing.
 */
/** A user-named EQ preset captured from the current band gain values. */
data class CustomEqPreset(val name: String, val gainsDb: List<Float>)

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

    /**
     * Drive Mode bumps transport / rating tap targets to be safer
     * to hit while driving. Off by default; users opt in once and
     * the setting follows them across sessions.
     *
     * The `_driveModeFlow` mirror gives consumers (NowPlayingScreen)
     * a live signal so toggling it from another tab takes effect
     * immediately, without the user having to close + reopen Now
     * Playing.
     */
    private val _driveModeFlow =
        kotlinx.coroutines.flow.MutableStateFlow(settings.getBoolean(KEY_DRIVE_MODE, false))
    val driveModeFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _driveModeFlow

    fun getDriveMode(): Boolean = settings.getBoolean(KEY_DRIVE_MODE, false)
    fun setDriveMode(enabled: Boolean) {
        settings.putBoolean(KEY_DRIVE_MODE, enabled)
        _driveModeFlow.value = enabled
    }

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

    /** Auto-resume the last station on app launch. Off by default. */
    fun getAutoResumeLastStation(): Boolean =
        settings.getBoolean(KEY_AUTO_RESUME_STATION, false)

    fun setAutoResumeLastStation(enabled: Boolean) =
        settings.putBoolean(KEY_AUTO_RESUME_STATION, enabled)

    // ---- Auto-backup ----

    /**
     * If enabled, the app fires an export-to-JSON pass at most once
     * every [AUTO_BACKUP_INTERVAL_MS] (7 days) on first launch
     * crossing that threshold. A safety net for users who forget to
     * back up manually. Off by default — silent background writes
     * shouldn't be opt-out.
     */
    fun getAutoBackupEnabled(): Boolean = settings.getBoolean(KEY_AUTO_BACKUP, false)
    fun setAutoBackupEnabled(enabled: Boolean) = settings.putBoolean(KEY_AUTO_BACKUP, enabled)

    fun getLastAutoBackupAt(): Long = settings.getLong(KEY_AUTO_BACKUP_LAST, 0L)
    fun setLastAutoBackupAt(ts: Long) = settings.putLong(KEY_AUTO_BACKUP_LAST, ts)

    // ---- One-shot migrations (set on first run, never re-run) ----

    /**
     * Marks whether the GENRE tag-case canonicalization pass has run
     * for this install. The pass merges "Rock" / "rock" / "ROCK" into
     * one canonical "Rock" so the Genres tab doesn't show duplicates.
     */
    fun getGenresCanonicalized(): Boolean = settings.getBoolean(KEY_GENRES_CANON, false)
    fun setGenresCanonicalized(done: Boolean) = settings.putBoolean(KEY_GENRES_CANON, done)

    // ---- File-mode browser opt-in ----

    fun getFileModeBrowserEnabled(): Boolean =
        settings.getBoolean(KEY_FILE_MODE_BROWSER, false)

    fun setFileModeBrowserEnabled(enabled: Boolean) =
        settings.putBoolean(KEY_FILE_MODE_BROWSER, enabled)

    // ---- ReplayGain ----

    fun getReplayGainEnabled(): Boolean = settings.getBoolean(KEY_REPLAY_GAIN, true)
    fun setReplayGainEnabled(enabled: Boolean) = settings.putBoolean(KEY_REPLAY_GAIN, enabled)

    /**
     * Pre-amp gain applied to ReplayGain-tagged tracks, in dB. Common
     * range: -6..+12. Combined with the track's RG value before
     * conversion to a 0..1 volume fraction (clamped to ≤1.0 to avoid
     * clipping). Untagged tracks ignore this value.
     */
    fun getReplayGainPreampDb(): Float = settings.getFloat(KEY_REPLAY_GAIN_PREAMP, 0f)
    fun setReplayGainPreampDb(dB: Float) = settings.putFloat(KEY_REPLAY_GAIN_PREAMP, dB)

    // ---- Equalizer ----

    fun getEqEnabled(): Boolean = settings.getBoolean(KEY_EQ_ENABLED, false)
    fun setEqEnabled(enabled: Boolean) = settings.putBoolean(KEY_EQ_ENABLED, enabled)

    /** Per-band gain in dB. Returns 0f for any band that hasn't been set. */
    fun getEqBandGain(bandIndex: Int): Float =
        settings.getFloat("$KEY_EQ_BAND_PREFIX$bandIndex", 0f)

    fun setEqBandGain(bandIndex: Int, gainDb: Float) =
        settings.putFloat("$KEY_EQ_BAND_PREFIX$bandIndex", gainDb)

    /**
     * User-saved EQ presets, stored as a JSON array of
     * `{ name, gainsDb: [...] }` objects. Empty by default. The
     * pipe-separated encoding is intentionally hand-rolled (rather
     * than full kotlinx-serialization) because it sidesteps the
     * need to import the json module here — values are simple
     * enough that escaping is unnecessary aside from `|` and `\n`
     * which we strip from names at save time.
     */
    fun getCustomEqPresets(): List<CustomEqPreset> {
        val raw = settings.getStringOrNull(KEY_EQ_CUSTOM_PRESETS) ?: return emptyList()
        return raw.split('\n')
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size < 2) return@mapNotNull null
                val name = parts[0]
                val gains = parts.drop(1).mapNotNull { it.toFloatOrNull() }
                if (name.isBlank() || gains.isEmpty()) null
                else CustomEqPreset(name, gains)
            }
    }

    fun saveCustomEqPreset(preset: CustomEqPreset) {
        // Replace by name (case-insensitive) so a re-save with the
        // same name updates rather than duplicates.
        val sanitized = preset.copy(name = preset.name.replace('|', '/').replace('\n', ' '))
        val current = getCustomEqPresets().filter { !it.name.equals(sanitized.name, ignoreCase = true) }
        val updated = current + sanitized
        writeCustomEqPresets(updated)
    }

    fun deleteCustomEqPreset(name: String) {
        val updated = getCustomEqPresets().filter { !it.name.equals(name, ignoreCase = true) }
        writeCustomEqPresets(updated)
    }

    private fun writeCustomEqPresets(presets: List<CustomEqPreset>) {
        val raw = presets.joinToString("\n") { p ->
            buildString {
                append(p.name)
                p.gainsDb.forEach { append('|').append(it.toString()) }
            }
        }
        if (raw.isEmpty()) settings.remove(KEY_EQ_CUSTOM_PRESETS)
        else settings.putString(KEY_EQ_CUSTOM_PRESETS, raw)
    }

    // ---- Library Browser persisted state ----

    /** Tab index restored on entry. 0 = Songs, 1 = Artists, 2 = Albums. */
    fun getLibraryTabIndex(): Int = settings.getInt(KEY_LIB_TAB, 0).coerceIn(0, 2)
    fun setLibraryTabIndex(index: Int) = settings.putInt(KEY_LIB_TAB, index)

    /** Songs tab sort order — string-encoded enum name. */
    fun getLibrarySongSort(): String = settings.getStringOrNull(KEY_LIB_SONG_SORT) ?: "TitleAsc"
    fun setLibrarySongSort(sort: String) = settings.putString(KEY_LIB_SONG_SORT, sort)

    // ---- Search history ----

    /**
     * Recent search queries, newest first. Stored as a single
     * tab-delimited string so we don't need a JSON parser hop for
     * what's at most 10 short strings. Tab is reserved (not allowed in
     * actual queries since it's not typeable on most keyboards) and
     * keeps each entry intact under split.
     */
    fun getRecentSearches(): List<String> {
        val raw = settings.getStringOrNull(KEY_SEARCH_HISTORY) ?: return emptyList()
        return raw.split('\t').filter { it.isNotBlank() }
    }

    fun pushRecentSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val current = getRecentSearches().filter { !it.equals(trimmed, ignoreCase = true) }
        val updated = (listOf(trimmed) + current).take(SEARCH_HISTORY_CAP)
        settings.putString(KEY_SEARCH_HISTORY, updated.joinToString("\t"))
    }

    fun clearRecentSearches() = settings.remove(KEY_SEARCH_HISTORY)

    private companion object {
        const val KEY_THEME_MODE = "ui.themeMode"
        const val KEY_MATERIAL_YOU = "ui.materialYou"
        const val KEY_REDUCE_MOTION = "ui.reduceMotion"
        const val KEY_CONNECTIVITY_POLICY = "online.connectivityPolicy"
        const val KEY_ONBOARDING_DONE = "onboarding.complete"
        const val KEY_LAST_STATION_ID = "playback.lastStationId"
        const val KEY_FILE_MODE_BROWSER = "library.fileModeBrowser"
        const val KEY_GENRES_CANON = "library.migrations.genresCanonicalized"
        const val KEY_LIB_TAB = "library.browser.tab"
        const val KEY_LIB_SONG_SORT = "library.browser.songSort"
        const val KEY_EQ_ENABLED = "audio.eq.enabled"
        const val KEY_EQ_BAND_PREFIX = "audio.eq.band."
        const val KEY_EQ_CUSTOM_PRESETS = "audio.eq.customPresets"
        const val KEY_REPLAY_GAIN = "audio.replayGain.enabled"
        const val KEY_REPLAY_GAIN_PREAMP = "audio.replayGain.preampDb"
        const val KEY_AUTO_RESUME_STATION = "playback.autoResumeLastStation"
        const val KEY_AUTO_BACKUP = "backup.autoEnabled"
        const val KEY_AUTO_BACKUP_LAST = "backup.autoLastAt"
        const val KEY_DRIVE_MODE = "ui.driveMode"
        /** Cadence for the auto-backup background export. 7 days = once
         *  per week, which matches typical user back-up cadence
         *  expectations (Google's auto-backup, Time Machine, etc.). */
        const val AUTO_BACKUP_INTERVAL_MS = 7L * 24L * 60L * 60L * 1_000L
        const val KEY_SEARCH_HISTORY = "search.recentQueries"
        const val SEARCH_HISTORY_CAP = 10
    }
}
