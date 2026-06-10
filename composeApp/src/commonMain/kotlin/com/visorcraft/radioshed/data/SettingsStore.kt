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
@kotlinx.serialization.Serializable
data class CustomEqPreset(val name: String, val gainsDb: List<Float>)

/**
 * Where the app draws its Material 3 ColorScheme from. Three modes:
 *  - [Brand]      : the static amber/orange tube-radio palette baked
 *                   into `ui/theme/Color.kt`. The original look.
 *  - [Custom]     : user-picked seed color(s) → ColorScheme derived
 *                   via materialKolor's `rememberDynamicColorScheme`.
 *                   Same algorithm Material You uses on wallpaper
 *                   extraction; the user picks the seed instead.
 *  - [MaterialYou]: Android 12+ wallpaper-extracted palette via the
 *                   platform `dynamicColorScheme(...)` actual.
 *                   Equivalent to the old `useMaterialYou = true` path.
 *
 * Persisted as the enum's name string. Pre-existing builds carrying
 * the legacy `KEY_MATERIAL_YOU` boolean are migrated implicitly: when
 * the new key is unset, [SettingsStore.getThemeColorSource] returns
 * [MaterialYou] iff that bool was true, otherwise [Brand].
 */
enum class ThemeColorSource { Brand, Custom, MaterialYou }

/**
 * Versioned envelope for the EQ preset import/export JSON. Bumped
 * whenever the format breaks compatibility — readers reject any
 * version they don't know how to interpret rather than silently
 * dropping fields.
 */
@kotlinx.serialization.Serializable
data class EqPresetBundle(
    val version: Int = CURRENT_VERSION,
    val presets: List<CustomEqPreset>,
) {
    companion object { const val CURRENT_VERSION = 1 }
}

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

    /**
     * Where the ColorScheme comes from — see [ThemeColorSource] for the
     * three options. When the new key is absent (fresh install OR
     * upgrade from a build that only knew `KEY_MATERIAL_YOU`), fall
     * back to the legacy bool: `true` → [ThemeColorSource.MaterialYou],
     * `false` → [ThemeColorSource.Brand]. This makes the upgrade
     * silent — users who had Material You on stay on Material You.
     */
    fun getThemeColorSource(): ThemeColorSource {
        val raw = settings.getStringOrNull(KEY_THEME_COLOR_SOURCE)
        if (raw != null) {
            return runCatching { ThemeColorSource.valueOf(raw) }
                .getOrDefault(ThemeColorSource.Brand)
        }
        return if (settings.getBoolean(KEY_MATERIAL_YOU, false)) {
            ThemeColorSource.MaterialYou
        } else {
            ThemeColorSource.Brand
        }
    }

    fun setThemeColorSource(source: ThemeColorSource) {
        settings.putString(KEY_THEME_COLOR_SOURCE, source.name)
        // Keep the legacy bool in sync so any code path still reading
        // it (and any pre-migration backup) sees a consistent value.
        // Once `KEY_MATERIAL_YOU` is removed, drop this line.
        settings.putBoolean(KEY_MATERIAL_YOU, source == ThemeColorSource.MaterialYou)
    }

    /**
     * Primary seed color for [ThemeColorSource.Custom]. Stored as the
     * 32-bit ARGB int — the high byte is the alpha channel and is
     * always 0xFF for an opaque seed. Default is the brand orange
     * (`LightPrimary` in `ui/theme/Color.kt`) so a user who flips to
     * Custom without picking sees the original colour, not a sudden
     * white-on-white surprise.
     */
    fun getThemeSeedArgb(): Int =
        settings.getInt(KEY_THEME_SEED, DEFAULT_THEME_SEED_ARGB)

    fun setThemeSeedArgb(argb: Int) {
        settings.putInt(KEY_THEME_SEED, argb)
    }

    /**
     * Accent seed for [ThemeColorSource.Custom]. Drives Material's
     * secondary + tertiary roles (chip selection, FAB tint, secondary
     * surfaces). Default is the brand secondary brown so the
     * out-of-box Custom look mirrors Brand until the user picks.
     */
    fun getAccentSeedArgb(): Int =
        settings.getInt(KEY_ACCENT_SEED, DEFAULT_ACCENT_SEED_ARGB)

    fun setAccentSeedArgb(argb: Int) {
        settings.putInt(KEY_ACCENT_SEED, argb)
    }

    /**
     * In-app user volume scalar (0..1). Multiplies on top of any
     * ReplayGain attenuation when a track is loaded — effective
     * engine volume = userVolume * replayGain. Defaults to 1.0.
     */
    fun getUserVolume(): Float =
        settings.getFloat(KEY_USER_VOLUME, 1f).coerceIn(0f, 1f)

    fun setUserVolume(value: Float) {
        settings.putFloat(KEY_USER_VOLUME, value.coerceIn(0f, 1f))
    }

    fun getReduceMotion(): Boolean = settings.getBoolean(KEY_REDUCE_MOTION, false)
    fun setReduceMotion(enabled: Boolean) = settings.putBoolean(KEY_REDUCE_MOTION, enabled)

    /**
     * App-wide typography scale, applied across every Material text
     * style by `RadioShedTheme`. Range 0.85–1.30 (clamped on read so
     * a corrupted preference can't blow up the UI). 1.0 = the
     * Material default; 0.85 ≈ "XS"; 1.30 ≈ "XXL". Distinct from the
     * Lyrics-only font adjuster, which still operates inside its own
     * scope on top of this.
     */
    fun getFontScale(): Float =
        settings.getFloat(KEY_FONT_SCALE, 1f).coerceIn(0.85f, 1.30f)

    fun setFontScale(value: Float) {
        settings.putFloat(KEY_FONT_SCALE, value.coerceIn(0.85f, 1.30f))
    }

    /**
     * Stations Home tile-size preference. Persisted as the minimum
     * adaptive cell width in dp — the grid still flexes column count
     * to whatever the screen affords, the user just nudges the
     * lower bound. Compact (single-column) layout below 600dp width
     * ignores this and always shows one tile per row; the setting
     * only kicks in on medium+ widths. Default 280dp matches the
     * pre-setting hard-coded value.
     */
    fun getStationsTileSizeDp(): Int =
        settings.getInt(KEY_STATIONS_TILE_SIZE, 280).coerceIn(160, 480)

    fun setStationsTileSizeDp(value: Int) {
        settings.putInt(KEY_STATIONS_TILE_SIZE, value.coerceIn(160, 480))
    }

    /**
     * Stations Home sort preference. String-encoded enum name so
     * adding a new sort doesn't require a schema migration. Default
     * "Custom" preserves the user's pin / drag-reorder order, which
     * is the legacy behavior before the sort menu shipped.
     */
    fun getStationsSort(): String =
        settings.getStringOrNull(KEY_STATIONS_SORT) ?: "Custom"

    fun setStationsSort(value: String) {
        settings.putString(KEY_STATIONS_SORT, value)
    }

    /**
     * Hides the seeded preset stations from the Stations Home grid.
     * Off by default — built-ins are part of v1's first-run UX. Power
     * users with a dozen custom stations may toggle this on so the
     * grid scrolls less.
     */
    fun getStationsHideBuiltIns(): Boolean =
        settings.getBoolean(KEY_STATIONS_HIDE_BUILTINS, false)

    fun setStationsHideBuiltIns(value: Boolean) {
        settings.putBoolean(KEY_STATIONS_HIDE_BUILTINS, value)
    }

    /**
     * Album/Artist/Genre detail screen track-sort prefs. Each
     * detail screen has its own enum (`AlbumTrackSort`,
     * `ArtistTrackSort`, `GenreTrackSort`); we store the enum's
     * name and let the screen `valueOf()` it back at composition
     * time. Default falls through whenever the enum has shifted
     * (a removed/renamed sort key is silently ignored).
     */
    fun getAlbumTrackSort(): String? = settings.getStringOrNull(KEY_ALBUM_TRACK_SORT)
    fun setAlbumTrackSort(name: String) = settings.putString(KEY_ALBUM_TRACK_SORT, name)

    fun getArtistTrackSort(): String? = settings.getStringOrNull(KEY_ARTIST_TRACK_SORT)
    fun setArtistTrackSort(name: String) = settings.putString(KEY_ARTIST_TRACK_SORT, name)

    fun getGenreTrackSort(): String? = settings.getStringOrNull(KEY_GENRE_TRACK_SORT)
    fun setGenreTrackSort(name: String) = settings.putString(KEY_GENRE_TRACK_SORT, name)

    /**
     * Lyrics sync offset in milliseconds. Positive = lyrics are
     * delayed (positionMs is decreased before line lookup),
     * negative = lyrics are early. Clamped to ±5000 ms — past
     * that the user is dealing with a fundamentally wrong file
     * and should re-fetch via the manual retry dialog.
     */
    fun getLyricsSyncOffsetMs(): Int =
        settings.getInt(KEY_LYRICS_SYNC_OFFSET, 0).coerceIn(-5000, 5000)

    fun setLyricsSyncOffsetMs(value: Int) {
        settings.putInt(KEY_LYRICS_SYNC_OFFSET, value.coerceIn(-5000, 5000))
    }

    /**
     * Stats screen window in days. 7 / 30 / 90 / 365 are the
     * common stops; default 30 matches the prior hardcoded value.
     */
    fun getStatsWindowDays(): Int =
        settings.getInt(KEY_STATS_WINDOW_DAYS, 30).coerceIn(1, 365)

    fun setStatsWindowDays(days: Int) {
        settings.putInt(KEY_STATS_WINDOW_DAYS, days.coerceIn(1, 365))
    }

    /**
     * How many backup files BackupService keeps before pruning.
     * Default 10 matches the prior hardcoded cap. Range 3..30 — past
     * 30 the user is better off using the system file manager to
     * archive specific snapshots manually. Re-read on every export
     * via the BackupService.maxBackupsToRetain getter so a slider
     * tweak takes effect immediately.
     */
    fun getBackupRetention(): Int =
        settings.getInt(KEY_BACKUP_RETENTION, 10).coerceIn(3, 30)

    fun setBackupRetention(value: Int) {
        settings.putInt(KEY_BACKUP_RETENTION, value.coerceIn(3, 30))
    }

    /**
     * If true, Now Playing keeps the screen on while a track is
     * actually playing — useful when the user is mid-cooking / mid-
     * driving and doesn't want the lockscreen to interrupt
     * artwork-as-ambient. Off by default to avoid burning battery
     * for a screen the user isn't looking at.
     */
    /**
     * Mirrors the [_driveModeFlow] pattern. NowPlayingScreen reads
     * this as a `collectAsState` so toggling Keep-screen-on from the
     * Settings tab takes effect on the active session, not just on
     * the next NowPlaying entry. Without the flow, the screen-flag
     * would stay stale until some other state change forced a
     * recompose.
     */
    private val _keepScreenOnFlow =
        kotlinx.coroutines.flow.MutableStateFlow(settings.getBoolean(KEY_KEEP_SCREEN_ON, false))
    val keepScreenOnFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _keepScreenOnFlow

    /**
     * Last-used Search scope chip ("All" / "Tracks" / "Artists" /
     * "Albums"). Persisted so a user who consistently searches one
     * scope doesn't have to re-pick it on every Search-screen entry.
     * Stored as the enum name string; an unknown / typo'd value
     * falls back to All.
     */
    fun getSearchScope(): String = settings.getStringOrNull(KEY_SEARCH_SCOPE) ?: "All"
    fun setSearchScope(name: String) = settings.putString(KEY_SEARCH_SCOPE, name)

    /**
     * Now Playing layout override. "Auto" picks based on width
     * (Compact <600dp, TwoPane 600-1100dp, TriplePane >=1100dp);
     * the other values force a specific layout regardless of
     * available space — useful when the user mounts a phone in
     * landscape on a dashboard and wants the SiriusXM-style
     * three-column view even though the breakpoint hasn't been
     * crossed. Stored as enum name string; unknown values fall
     * back to Auto. The TriplePane choice is intentionally
     * available on every width — it'll squeeze badly on a phone
     * portrait but the user asked for the option.
     */
    /**
     * Mirror flow for the layout preference. NowPlayingScreen
     * subscribes via `collectAsState()` so a layout change in
     * AppSettings propagates without requiring the user to leave
     * and re-enter Now Playing. Same pattern as `_driveModeFlow`
     * and `_keepScreenOnFlow`.
     */
    private val _nowPlayingLayoutFlow =
        kotlinx.coroutines.flow.MutableStateFlow(
            settings.getStringOrNull(KEY_NOW_PLAYING_LAYOUT) ?: "Auto",
        )
    val nowPlayingLayoutFlow: kotlinx.coroutines.flow.StateFlow<String> = _nowPlayingLayoutFlow

    fun getNowPlayingLayout(): String =
        settings.getStringOrNull(KEY_NOW_PLAYING_LAYOUT) ?: "Auto"
    fun setNowPlayingLayout(name: String) {
        settings.putString(KEY_NOW_PLAYING_LAYOUT, name)
        _nowPlayingLayoutFlow.value = name
    }

    fun getKeepScreenOn(): Boolean = settings.getBoolean(KEY_KEEP_SCREEN_ON, false)
    fun setKeepScreenOn(enabled: Boolean) {
        settings.putBoolean(KEY_KEEP_SCREEN_ON, enabled)
        _keepScreenOnFlow.value = enabled
    }

    /**
     * Whether LyricsService is allowed to hit LRClib (or other future
     * web sources). Local sidecar + embedded extraction stay on
     * regardless. On by default — most users want lyrics, and the
     * connectivity policy already gates by metered/unmetered.
     */
    fun getAutoFetchLyrics(): Boolean = settings.getBoolean(KEY_AUTO_FETCH_LYRICS, true)
    fun setAutoFetchLyrics(enabled: Boolean) =
        settings.putBoolean(KEY_AUTO_FETCH_LYRICS, enabled)

    /**
     * Master haptic-feedback gate. When off, gesture/long-press
     * vibration confirmations are suppressed app-wide. On by default
     * so the gestures feel responsive out of the box; users who
     * find vibration distracting can disable in one place.
     */
    fun getHapticsEnabled(): Boolean = settings.getBoolean(KEY_HAPTICS_ENABLED, true)
    fun setHapticsEnabled(enabled: Boolean) =
        settings.putBoolean(KEY_HAPTICS_ENABLED, enabled)

    // ListenBrainz scrobbling — opt-in. Token comes from
    // listenbrainz.org/profile/ and is opaque to us; we just round-
    // trip it as the Authorization header on submit-listens calls.
    // Stored as a plain string in shared prefs — same trust boundary
    // as every other user secret on the device. Documented in
    // FAQ.md and USER_GUIDE.md.
    fun getListenBrainzEnabled(): Boolean =
        settings.getBoolean(KEY_LISTENBRAINZ_ENABLED, false)
    fun setListenBrainzEnabled(enabled: Boolean) =
        settings.putBoolean(KEY_LISTENBRAINZ_ENABLED, enabled)
    fun getListenBrainzToken(): String =
        settings.getStringOrNull(KEY_LISTENBRAINZ_TOKEN).orEmpty()
    fun setListenBrainzToken(token: String) {
        if (token.isBlank()) settings.remove(KEY_LISTENBRAINZ_TOKEN)
        else settings.putString(KEY_LISTENBRAINZ_TOKEN, token.trim())
    }

    // Subsonic server config — URL/username get backed up so a new
    // device round-trips the connection setup; password is treated
    // as a credential and excluded from the bundle. The user
    // re-enters it once after restore. Same trust posture as the
    // ListenBrainz token.
    fun getSubsonicUrl(): String = settings.getStringOrNull(KEY_SUBSONIC_URL).orEmpty()
    fun setSubsonicUrl(url: String) {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isBlank()) settings.remove(KEY_SUBSONIC_URL)
        else settings.putString(KEY_SUBSONIC_URL, trimmed)
    }
    fun getSubsonicUsername(): String =
        settings.getStringOrNull(KEY_SUBSONIC_USERNAME).orEmpty()
    fun setSubsonicUsername(username: String) {
        val trimmed = username.trim()
        if (trimmed.isBlank()) settings.remove(KEY_SUBSONIC_USERNAME)
        else settings.putString(KEY_SUBSONIC_USERNAME, trimmed)
    }
    fun getSubsonicPassword(): String =
        settings.getStringOrNull(KEY_SUBSONIC_PASSWORD).orEmpty()
    fun setSubsonicPassword(password: String) {
        if (password.isBlank()) settings.remove(KEY_SUBSONIC_PASSWORD)
        else settings.putString(KEY_SUBSONIC_PASSWORD, password)
    }
    /** True when the user has filled in URL + username + password. */
    fun hasSubsonicConfig(): Boolean =
        getSubsonicUrl().isNotBlank() &&
            getSubsonicUsername().isNotBlank() &&
            getSubsonicPassword().isNotBlank()

    /**
     * Playback rate multiplier. Persisted across sessions so audiobook
     * / podcast users don't have to reset to their preferred speed
     * after every relaunch. Range 0.5..2.0; UI exposes 0.75 / 1.0 /
     * 1.25 / 1.5 / 2.0 as common stops. Default 1.0 (normal speed).
     */
    fun getPlaybackSpeed(): Float =
        settings.getFloat(KEY_PLAYBACK_SPEED, 1f).coerceIn(0.5f, 2f)

    fun setPlaybackSpeed(value: Float) {
        settings.putFloat(KEY_PLAYBACK_SPEED, value.coerceIn(0.5f, 2f))
    }

    /**
     * When the sleep timer expires, should the engine pause (default,
     * holds the queue exactly where it is) or stop (clears the
     * playable from the engine, taking the MediaSession off the
     * lock screen)? Pause is friendlier for "I want to keep listening
     * tomorrow"; stop is friendlier for "I want my phone fully quiet".
     */
    fun getSleepStopOnExpire(): Boolean =
        settings.getBoolean(KEY_SLEEP_STOP_ON_EXPIRE, false)

    fun setSleepStopOnExpire(value: Boolean) {
        settings.putBoolean(KEY_SLEEP_STOP_ON_EXPIRE, value)
    }

    // ---- DJ (automated station-voice) ----

    /** Whether the DJ feature is active at all. Off by default. */
    fun getDjEnabled(): Boolean = settings.getBoolean(KEY_DJ_ENABLED, false)
    fun setDjEnabled(value: Boolean) = settings.putBoolean(KEY_DJ_ENABLED, value)

    /**
     * Cadence at which the DJ speaks. Allowed values: `off`, `every`,
     * `every_n`, `minutes`, `station_change`. Unknown stored values are
     * coerced back to `off` so a future rename can never leave the UI in
     * an undefined state.
     */
    fun getDjFrequency(): String {
        val raw = settings.getStringOrNull(KEY_DJ_FREQUENCY) ?: return "off"
        return if (raw in DJ_FREQUENCY_VALUES) raw else "off"
    }

    fun setDjFrequency(value: String) {
        settings.putString(KEY_DJ_FREQUENCY, if (value in DJ_FREQUENCY_VALUES) value else "off")
    }

    /**
     * When [getDjFrequency] is `every_n`, the DJ speaks every N tracks.
     * Coerced to 2..20 on read and write so invalid stored data can't
     * break the scheduling logic.
     */
    fun getDjFrequencyN(): Int =
        settings.getInt(KEY_DJ_FREQUENCY_N, 4).coerceIn(2, 20)

    fun setDjFrequencyN(value: Int) {
        settings.putInt(KEY_DJ_FREQUENCY_N, value.coerceIn(2, 20))
    }

    /**
     * When [getDjFrequency] is `minutes`, the DJ speaks every N minutes.
     * Coerced to 5..120.
     */
    fun getDjFrequencyMinutes(): Int =
        settings.getInt(KEY_DJ_FREQUENCY_MINUTES, 15).coerceIn(5, 120)

    fun setDjFrequencyMinutes(value: Int) {
        settings.putInt(KEY_DJ_FREQUENCY_MINUTES, value.coerceIn(5, 120))
    }

    /**
     * Start of the DJ quiet window, in minutes since midnight (0..1439).
     * Default 1320 = 22:00. The DJ will not speak between
     * [getDjQuietStartMin] and [getDjQuietEndMin].
     */
    fun getDjQuietStartMin(): Int =
        settings.getInt(KEY_DJ_QUIET_START_MIN, 1320).coerceIn(0, 1439)

    fun setDjQuietStartMin(value: Int) {
        settings.putInt(KEY_DJ_QUIET_START_MIN, value.coerceIn(0, 1439))
    }

    /**
     * End of the DJ quiet window, in minutes since midnight (0..1439).
     * Default 420 = 07:00.
     */
    fun getDjQuietEndMin(): Int =
        settings.getInt(KEY_DJ_QUIET_END_MIN, 420).coerceIn(0, 1439)

    fun setDjQuietEndMin(value: Int) {
        settings.putInt(KEY_DJ_QUIET_END_MIN, value.coerceIn(0, 1439))
    }

    /**
     * Volume fraction (0.0..0.6) the music is ducked to while the DJ
     * is speaking. 0.2 = music at 20 % volume during TTS. Coerced on
     * read and write.
     */
    fun getDjDuckFraction(): Float =
        settings.getFloat(KEY_DJ_DUCK_FRACTION, 0.2f).coerceIn(0.0f, 0.6f)

    fun setDjDuckFraction(value: Float) {
        settings.putFloat(KEY_DJ_DUCK_FRACTION, value.coerceIn(0.0f, 0.6f))
    }

    /**
     * Raw DJ template blob. Empty string (the default) means "use
     * `DEFAULT_DJ_TEMPLATES` from `stations/DjTemplate.kt`". The
     * empty-string ↔ use-defaults contract is intentional — DjTemplate
     * is not imported here to keep the data layer independent of the
     * stations layer.
     */
    fun getDjTemplates(): String =
        settings.getStringOrNull(KEY_DJ_TEMPLATES).orEmpty()

    fun setDjTemplates(value: String) {
        if (value.isEmpty()) settings.remove(KEY_DJ_TEMPLATES)
        else settings.putString(KEY_DJ_TEMPLATES, value)
    }

    // ---- Drive Mode trigger / quick-access settings ----
    // JSON encode/decode of DriveModeTrigger lives with callers, NOT here.
    // SettingsStore stays dumb (plain String round-trip), same convention as
    // KEY_DJ_TEMPLATES. The data layer must not import domain serialization logic.

    /**
     * JSON-serialized list of [com.visorcraft.radioshed.domain.DriveModeTrigger]
     * values. Empty string (the default) means no automatic triggers are
     * configured — Drive Mode is only activated manually. Callers encode/
     * decode the list using RadioShedJson; this key stores the raw JSON blob.
     */
    fun getDriveTriggers(): String =
        settings.getStringOrNull(KEY_DRIVE_TRIGGERS).orEmpty()

    fun setDriveTriggers(value: String) {
        if (value.isEmpty()) settings.remove(KEY_DRIVE_TRIGGERS)
        else settings.putString(KEY_DRIVE_TRIGGERS, value)
    }

    /**
     * Comma-joined station ids for the Drive Mode quick-access list, ordered
     * by the user's chosen sequence. At most 6 ids are stored; the setter
     * enforces this by calling `take(6)` on the incoming list before joining.
     * Default "" means the quick-list is empty (not configured).
     */
    fun getDriveQuickList(): String =
        settings.getStringOrNull(KEY_DRIVE_QUICKLIST).orEmpty()

    fun setDriveQuickList(ids: List<String>) {
        val joined = ids.take(6).joinToString(",")
        if (joined.isEmpty()) settings.remove(KEY_DRIVE_QUICKLIST)
        else settings.putString(KEY_DRIVE_QUICKLIST, joined)
    }

    /**
     * Comma-joined transport action ids shown in the Drive Mode button bar.
     * Allowed ids: `previous`, `play_pause`, `next`, `station_picker`,
     * `voice_search`, `mute`. The getter filters out any id not in the
     * allowed set so stale stored values can't surface unknown buttons.
     * When the stored value is blank the getter returns the default set
     * `"previous,play_pause,next,station_picker"`.
     */
    fun getDriveButtons(): String {
        val raw = settings.getStringOrNull(KEY_DRIVE_BUTTONS)
        if (raw.isNullOrBlank()) return DRIVE_BUTTONS_DEFAULT
        val filtered = raw.split(',')
            .filter { it.trim() in DRIVE_BUTTON_IDS }
            .joinToString(",")
        return filtered.ifBlank { DRIVE_BUTTONS_DEFAULT }
    }

    fun setDriveButtons(ids: List<String>) {
        val filtered = ids.map { it.trim() }.filter { it in DRIVE_BUTTON_IDS }
        if (filtered.isEmpty()) settings.remove(KEY_DRIVE_BUTTONS)
        else settings.putString(KEY_DRIVE_BUTTONS, filtered.joinToString(","))
    }

    /**
     * When true, Drive Mode exits automatically once none of the configured
     * [getDriveTriggers] conditions remain active (e.g. Bluetooth disconnects,
     * schedule window closes). True by default — users who forget to tap "exit"
     * shouldn't stay stuck in the enlarged-tap-target layout indefinitely.
     */
    fun getDriveAutoExit(): Boolean =
        settings.getBoolean(KEY_DRIVE_AUTO_EXIT, true)

    fun setDriveAutoExit(value: Boolean) =
        settings.putBoolean(KEY_DRIVE_AUTO_EXIT, value)

    /**
     * Wipe every user-portable preference key — used by the Settings
     * → "Reset settings to defaults" action. Library, ratings,
     * playlists, station definitions, listening history, custom EQ
     * presets, and onboarding state are NOT touched (those aren't
     * "settings" in the user's mental model). Per-device flags + the
     * search history are also preserved.
     */
    fun resetAllUserSettings() {
        listOf(
            KEY_THEME_MODE, KEY_MATERIAL_YOU,
            KEY_THEME_COLOR_SOURCE, KEY_THEME_SEED, KEY_ACCENT_SEED,
            KEY_USER_VOLUME,
            KEY_REDUCE_MOTION, KEY_FONT_SCALE, KEY_STATIONS_TILE_SIZE,
            KEY_STATIONS_HIDE_BUILTINS, KEY_STATIONS_SORT,
            KEY_CONNECTIVITY_POLICY,
            KEY_NETPOLICY_OVERRIDE_COVERART, KEY_NETPOLICY_OVERRIDE_LYRICS,
            KEY_LIB_TAB, KEY_LIB_SONG_SORT, KEY_LIB_ARTIST_SORT,
            KEY_LIB_ALBUM_SORT, KEY_LIB_GENRE_SORT, KEY_FILE_BROWSER_SORT,
            KEY_FILE_BROWSER_PINS,
            KEY_EQ_ENABLED, KEY_REPLAY_GAIN, KEY_REPLAY_GAIN_PREAMP,
            KEY_AUTO_RESUME_STATION, KEY_AUTO_BACKUP,
            KEY_AUTO_RESCAN_ON_LAUNCH, KEY_END_FADE_SEC, KEY_LYRICS_SCALE,
            KEY_DRIVE_MODE, KEY_ALBUM_TRACK_SORT, KEY_ARTIST_TRACK_SORT,
            KEY_GENRE_TRACK_SORT, KEY_LYRICS_SYNC_OFFSET,
            KEY_STATS_WINDOW_DAYS, KEY_BACKUP_RETENTION,
            KEY_SLEEP_STOP_ON_EXPIRE, KEY_PLAYBACK_SPEED,
            KEY_KEEP_SCREEN_ON, KEY_AUTO_FETCH_LYRICS, KEY_HAPTICS_ENABLED,
            KEY_LISTENBRAINZ_ENABLED,
            KEY_SEARCH_SCOPE, KEY_NOW_PLAYING_LAYOUT,
            // Cloud connection setup is part of the user-portable
            // surface (round-trips via backup, see dumpForBackup), so
            // a "reset all settings" wipes it too. Credentials
            // (KEY_SUBSONIC_PASSWORD / KEY_LISTENBRAINZ_TOKEN) are NOT
            // touched here — those are managed via the per-source
            // sign-out affordances in their respective settings rows.
            KEY_SUBSONIC_URL, KEY_SUBSONIC_USERNAME,
            KEY_DJ_ENABLED, KEY_DJ_FREQUENCY, KEY_DJ_FREQUENCY_N,
            KEY_DJ_FREQUENCY_MINUTES, KEY_DJ_QUIET_START_MIN, KEY_DJ_QUIET_END_MIN,
            KEY_DJ_DUCK_FRACTION, KEY_DJ_TEMPLATES,
            KEY_DRIVE_TRIGGERS, KEY_DRIVE_QUICKLIST, KEY_DRIVE_BUTTONS, KEY_DRIVE_AUTO_EXIT,
        ).forEach { settings.remove(it) }
        // Reset wipes the underlying entries; mirror flows must
        // republish so live consumers (NowPlaying for driveMode /
        // keepScreenOn) see the defaulted values immediately.
        republishMirrorFlows()
    }

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

    /**
     * Per-service override for the global connectivity policy.
     * Returns `Inherit` when no override is stored — that's the
     * default for every service so existing user installs don't see
     * any behavior change until they explicitly tap into the
     * per-service rows.
     */
    fun getServicePolicyOverride(
        service: com.visorcraft.radioshed.source.NetworkService,
    ): com.visorcraft.radioshed.source.ServicePolicyOverride {
        val raw = settings.getStringOrNull(keyForServiceOverride(service))
            ?: return com.visorcraft.radioshed.source.ServicePolicyOverride.Inherit
        return com.visorcraft.radioshed.source.ServicePolicyOverride.fromStorage(raw)
    }

    fun setServicePolicyOverride(
        service: com.visorcraft.radioshed.source.NetworkService,
        override: com.visorcraft.radioshed.source.ServicePolicyOverride,
    ) {
        // Inherit is the absence of an override; remove the row
        // entirely so a backup of a default-config install doesn't
        // carry redundant entries.
        if (override == com.visorcraft.radioshed.source.ServicePolicyOverride.Inherit) {
            settings.remove(keyForServiceOverride(service))
        } else {
            settings.putString(keyForServiceOverride(service), override.name)
        }
    }

    private fun keyForServiceOverride(
        service: com.visorcraft.radioshed.source.NetworkService,
    ): String = when (service) {
        com.visorcraft.radioshed.source.NetworkService.CoverArt -> KEY_NETPOLICY_OVERRIDE_COVERART
        com.visorcraft.radioshed.source.NetworkService.Lyrics -> KEY_NETPOLICY_OVERRIDE_LYRICS
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

    /** When on, App.kt rescans folders whose filesystem mtime
     *  changed since the last scan. Off by default — keeps cold
     *  launches snappy on libraries that don't get edited often. */
    fun getAutoRescanOnLaunch(): Boolean =
        settings.getBoolean(KEY_AUTO_RESCAN_ON_LAUNCH, false)
    fun setAutoRescanOnLaunch(enabled: Boolean) =
        settings.putBoolean(KEY_AUTO_RESCAN_ON_LAUNCH, enabled)

    /**
     * Lyrics font-size multiplier. 1.0 is the Material baseline; the
     * UI lets the user step it between 0.7 and 1.6 in 0.1 increments.
     * Persisted so the user's chosen size carries across sessions —
     * users with vision needs shouldn't have to re-pick on every
     * launch. Coerced on read so a corrupted value can't blow up
     * Compose's text-rendering math.
     */
    fun getLyricsScale(): Float =
        settings.getFloat(KEY_LYRICS_SCALE, 1f).coerceIn(0.7f, 1.6f)

    fun setLyricsScale(value: Float) {
        settings.putFloat(KEY_LYRICS_SCALE, value.coerceIn(0.7f, 1.6f))
    }

    /**
     * Length (seconds) of the volume ramp at the end of each track.
     * 0 disables the feature entirely (default). Range 0..10.
     * This is a track-end fade-out, not crossfade — no overlap with
     * the next track. It applies only when a station's crossfade rule
     * is Off. Per-station crossfade (with true overlap) is configured
     * in PlaybackRule.crossfade and handled by the audio engine.
     */
    fun getEndFadeSec(): Int =
        settings.getInt(KEY_END_FADE_SEC, 0).coerceIn(0, 10)
    fun setEndFadeSec(seconds: Int) {
        settings.putInt(KEY_END_FADE_SEC, seconds.coerceIn(0, 10))
    }

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
    /**
     * Persisted tab index for the Library Browser. The upper bound
     * was 2 from the original three-tab layout (Songs / Artists /
     * Albums); when Genres landed it was silently downgraded to
     * Albums on relaunch because of the coerce. The call site
     * (`LibraryBrowserScreen`) already guards with
     * `LibraryTab.entries.getOrElse(...) { LibraryTab.Songs }`, so
     * we only need a generous floor/ceiling here to keep negative
     * or comically-large stored values from blowing up.
     */
    fun getLibraryTabIndex(): Int = settings.getInt(KEY_LIB_TAB, 0).coerceAtLeast(0)
    fun setLibraryTabIndex(index: Int) = settings.putInt(KEY_LIB_TAB, index)

    /** Songs tab sort order — string-encoded enum name. */
    fun getLibrarySongSort(): String = settings.getStringOrNull(KEY_LIB_SONG_SORT) ?: "TitleAsc"
    fun setLibrarySongSort(sort: String) = settings.putString(KEY_LIB_SONG_SORT, sort)

    /** Artists tab sort order — string-encoded enum name. */
    fun getLibraryArtistSort(): String =
        settings.getStringOrNull(KEY_LIB_ARTIST_SORT) ?: "NameAsc"
    fun setLibraryArtistSort(sort: String) =
        settings.putString(KEY_LIB_ARTIST_SORT, sort)

    /** Albums tab sort order — string-encoded enum name. */
    fun getLibraryAlbumSort(): String =
        settings.getStringOrNull(KEY_LIB_ALBUM_SORT) ?: "TitleAsc"
    fun setLibraryAlbumSort(sort: String) =
        settings.putString(KEY_LIB_ALBUM_SORT, sort)

    /** Genres tab sort order — string-encoded enum name. */
    fun getLibraryGenreSort(): String =
        settings.getStringOrNull(KEY_LIB_GENRE_SORT) ?: "NameAsc"
    fun setLibraryGenreSort(sort: String) =
        settings.putString(KEY_LIB_GENRE_SORT, sort)

    /**
     * Sort preference for the in-app File Manager. Persisted so the
     * user's chosen ordering carries between sessions; same shape as
     * [getLibrarySongSort] just under a different key.
     */
    fun getFileBrowserSort(): String =
        settings.getStringOrNull(KEY_FILE_BROWSER_SORT) ?: "NameAsc"
    fun setFileBrowserSort(sort: String) =
        settings.putString(KEY_FILE_BROWSER_SORT, sort)

    /**
     * User-pinned folders for the File Manager bookmark bar. Stored as
     * one "name\tpath" record per line — same delimiter trick as
     * [getRecentSearches] since paths never contain `\n` and `\t` is
     * extraordinarily rare in real-world filenames. Keeps us from
     * pulling in a JSON hop for what's normally a handful of entries.
     */
    fun getFileBrowserPinnedFolders(): List<Pair<String, String>> {
        val raw = settings.getStringOrNull(KEY_FILE_BROWSER_PINS) ?: return emptyList()
        return raw.split('\n')
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0 || tab == line.lastIndex) null
                else line.substring(0, tab) to line.substring(tab + 1)
            }
    }

    fun pinFileBrowserFolder(name: String, path: String) {
        if (path.isBlank()) return
        // Sanitize the delimiter chars out of the display name so a
        // folder whose name happens to contain `\t` or `\n` (rare on
        // Android but possible on cross-mounted volumes from POSIX
        // filesystems) doesn't corrupt the stored blob. Path is
        // platform-canonical and won't contain newlines, but we
        // strip tabs there too as belt-and-suspenders.
        val safeName = name.replace('\t', ' ').replace('\n', ' ')
        val safePath = path.replace('\t', ' ').replace('\n', ' ')
        // Dedupe by path so re-pinning the same folder doesn't grow
        // the list. Newest-first so a freshly-pinned folder appears
        // immediately after the preset chips on the bookmarks row.
        val current = getFileBrowserPinnedFolders().filterNot { it.second == safePath }
        val updated = (listOf(safeName to safePath) + current).take(FILE_BROWSER_PIN_CAP)
        settings.putString(
            KEY_FILE_BROWSER_PINS,
            updated.joinToString("\n") { "${it.first}\t${it.second}" },
        )
    }

    fun unpinFileBrowserFolder(path: String) {
        val current = getFileBrowserPinnedFolders().filterNot { it.second == path }
        if (current.isEmpty()) {
            settings.remove(KEY_FILE_BROWSER_PINS)
        } else {
            settings.putString(
                KEY_FILE_BROWSER_PINS,
                current.joinToString("\n") { "${it.first}\t${it.second}" },
            )
        }
    }

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

    /**
     * Drop a single recent-search entry, leaving the rest. Comparison
     * is case-insensitive to mirror push semantics — typing the same
     * query in a different case shouldn't survive a "remove" tap.
     */
    fun removeRecentSearch(query: String) {
        val survivors = getRecentSearches().filterNot { it.equals(query, ignoreCase = true) }
        if (survivors.isEmpty()) {
            settings.remove(KEY_SEARCH_HISTORY)
        } else {
            settings.putString(KEY_SEARCH_HISTORY, survivors.joinToString("\t"))
        }
    }

    // ---- Backup integration ----

    /**
     * Snapshot of every user-portable setting, keyed by storage key.
     * Per-device state (last station, auto-backup-last-at, library
     * UX state, one-shot migration flags) is intentionally excluded
     * — restoring those on a different device would be wrong.
     */
    fun dumpForBackup(): Map<String, com.visorcraft.radioshed.source.BackupSettingValue> {
        val out = mutableMapOf<String, com.visorcraft.radioshed.source.BackupSettingValue>()
        // Strings
        listOf(
            KEY_THEME_MODE, KEY_CONNECTIVITY_POLICY,
            KEY_LIB_ARTIST_SORT, KEY_LIB_ALBUM_SORT, KEY_LIB_GENRE_SORT,
            KEY_FILE_BROWSER_SORT, KEY_SEARCH_SCOPE,
            KEY_ALBUM_TRACK_SORT, KEY_ARTIST_TRACK_SORT, KEY_GENRE_TRACK_SORT,
            KEY_NOW_PLAYING_LAYOUT,
            // Encoded list of user-pinned File Manager folders.
            // Format is internal to getFileBrowserPinnedFolders /
            // pinFileBrowserFolder; restore round-trips the raw
            // string and the next call decodes it. Without this,
            // backups silently dropped pinned bookmarks.
            KEY_FILE_BROWSER_PINS,
            KEY_THEME_COLOR_SOURCE,
            // Per-service connectivity-policy overrides. Default
            // ("Inherit") is stored as the absence of a row, so a
            // fresh install dumps nothing here and only users who
            // tapped a per-service override carry the entry.
            KEY_NETPOLICY_OVERRIDE_COVERART, KEY_NETPOLICY_OVERRIDE_LYRICS,
            // Stations Home view preferences — sort order plus the
            // existing tile-size + hide-built-ins toggles. `Custom`
            // is the default; non-default rows persist so a restored
            // device matches what the user picked.
            KEY_STATIONS_SORT,
            // Subsonic server URL + username — non-credential
            // connection setup. Round-tripping these saves the user
            // re-typing the server URL and username on a new device;
            // KEY_SUBSONIC_PASSWORD is intentionally NOT in any of
            // the dump buckets because shipping credentials in a
            // backup bundle that lives on disk / sharing UIs is a
            // bad idea. The user re-enters the password once after
            // restore and the rest of the connection works.
            // Same rationale for KEY_LISTENBRAINZ_TOKEN — excluded.
            KEY_SUBSONIC_URL, KEY_SUBSONIC_USERNAME,
            // DJ feature — frequency mode + templates blob. Empty
            // KEY_DJ_TEMPLATES means "use DjTemplate defaults"; the
            // absence of the row in the dump conveys the same thing,
            // so we only persist when the user has stored something.
            KEY_DJ_FREQUENCY, KEY_DJ_TEMPLATES,
            // Drive Mode trigger/quick-list/button-bar blobs. Each is a
            // plain String; JSON decode lives with callers (same convention
            // as KEY_DJ_TEMPLATES). Absent when unset = use defaults.
            KEY_DRIVE_TRIGGERS, KEY_DRIVE_QUICKLIST, KEY_DRIVE_BUTTONS,
        ).forEach { key ->
            settings.getStringOrNull(key)?.let {
                out[key] = com.visorcraft.radioshed.source.BackupSettingValue.Str(it)
            }
        }
        // Booleans — only persist when user has actually toggled away
        // from the default, so a fresh install with default settings
        // produces a small bundle. We can't tell "default vs explicit"
        // without a marker; check `hasKey` to detect any write.
        listOf(
            KEY_MATERIAL_YOU, KEY_REDUCE_MOTION, KEY_DRIVE_MODE,
            KEY_REPLAY_GAIN, KEY_AUTO_RESUME_STATION, KEY_AUTO_BACKUP,
            KEY_AUTO_RESCAN_ON_LAUNCH, KEY_EQ_ENABLED,
            KEY_STATIONS_HIDE_BUILTINS, KEY_SLEEP_STOP_ON_EXPIRE,
            KEY_KEEP_SCREEN_ON, KEY_AUTO_FETCH_LYRICS, KEY_HAPTICS_ENABLED,
            KEY_LISTENBRAINZ_ENABLED,
            KEY_DJ_ENABLED,
            KEY_DRIVE_AUTO_EXIT,
        ).forEach { key ->
            if (settings.hasKey(key)) {
                out[key] = com.visorcraft.radioshed.source.BackupSettingValue.Bool(
                    settings.getBoolean(key, false),
                )
            }
        }
        // Floats
        listOf(
            KEY_USER_VOLUME, KEY_REPLAY_GAIN_PREAMP,
            KEY_FONT_SCALE, KEY_LYRICS_SCALE,
            KEY_PLAYBACK_SPEED,
            KEY_DJ_DUCK_FRACTION,
        ).forEach { key ->
            if (settings.hasKey(key)) {
                out[key] = com.visorcraft.radioshed.source.BackupSettingValue.FloatValue(
                    settings.getFloat(key, 0f),
                )
            }
        }
        // Ints
        listOf(
            KEY_END_FADE_SEC, KEY_STATIONS_TILE_SIZE,
            KEY_LYRICS_SYNC_OFFSET, KEY_STATS_WINDOW_DAYS,
            KEY_BACKUP_RETENTION,
            // ARGB-packed Int seed colours. Persisting them as Int
            // round-trips identically through Settings on both
            // platforms (no float-rounding on the alpha byte).
            KEY_THEME_SEED, KEY_ACCENT_SEED,
            KEY_DJ_FREQUENCY_N, KEY_DJ_FREQUENCY_MINUTES,
            KEY_DJ_QUIET_START_MIN, KEY_DJ_QUIET_END_MIN,
        ).forEach { key ->
            if (settings.hasKey(key)) {
                out[key] = com.visorcraft.radioshed.source.BackupSettingValue.IntValue(
                    settings.getInt(key, 0),
                )
            }
        }
        return out
    }

    /**
     * Restore settings from a backup. Unknown keys are silently
     * ignored — keeps forward compatibility when an older app reads
     * a newer bundle (it just doesn't apply the new keys).
     *
     * Per-key type validation: a key is only written if the bundle's
     * value type matches what the app currently stores at that key.
     * Without this guard, a future app version that promotes
     * `KEY_REPLAY_GAIN_PREAMP` from Float to Int would silently
     * corrupt the prefs slot when reading an older Float-typed
     * bundle, and `getFloat()` would then return the default.
     */
    fun restoreFromBackup(snapshot: Map<String, com.visorcraft.radioshed.source.BackupSettingValue>) {
        for ((key, value) in snapshot) {
            // Block per-device state from sneaking in via a
            // tampered bundle — defence-in-depth even though we
            // never write those keys to the dump.
            if (key in BLOCKED_RESTORE_KEYS) continue
            // Skip when the bundle's type doesn't match what the
            // app's dump would produce for this key today. Unknown
            // keys (introduced in a newer bundle) fall through to
            // the type-match write because there's no expected type
            // recorded — they just go through with whatever type
            // the bundle declared.
            val expected = expectedTypeForKey(key)
            val actual = bundleValueType(value)
            if (expected != null && expected != actual) continue
            when (value) {
                is com.visorcraft.radioshed.source.BackupSettingValue.Bool ->
                    settings.putBoolean(key, value.value)
                is com.visorcraft.radioshed.source.BackupSettingValue.Str ->
                    settings.putString(key, value.value)
                is com.visorcraft.radioshed.source.BackupSettingValue.IntValue ->
                    settings.putInt(key, value.value)
                is com.visorcraft.radioshed.source.BackupSettingValue.LongValue ->
                    settings.putLong(key, value.value)
                is com.visorcraft.radioshed.source.BackupSettingValue.FloatValue ->
                    settings.putFloat(key, value.value)
            }
        }
        // After bulk restore, republish every StateFlow that mirrors
        // a settings key. Without this, screens already collecting
        // the flow (NowPlaying for keepScreenOn / driveMode) see
        // stale values until app restart — the underlying Settings
        // object updates but the flow's cached value doesn't.
        republishMirrorFlows()
    }

    /**
     * Re-reads every flow-backed settings key and pushes the current
     * underlying value through its `MutableStateFlow`. Called after
     * `restoreFromBackup` and `resetAllUserSettings` so live
     * consumers don't drift from the underlying store.
     */
    private fun republishMirrorFlows() {
        _driveModeFlow.value = settings.getBoolean(KEY_DRIVE_MODE, false)
        _keepScreenOnFlow.value = settings.getBoolean(KEY_KEEP_SCREEN_ON, false)
        _nowPlayingLayoutFlow.value =
            settings.getStringOrNull(KEY_NOW_PLAYING_LAYOUT) ?: "Auto"
    }

    private enum class SettingType { BOOL, STR, INT, LONG, FLOAT }

    private fun bundleValueType(value: com.visorcraft.radioshed.source.BackupSettingValue): SettingType =
        when (value) {
            is com.visorcraft.radioshed.source.BackupSettingValue.Bool -> SettingType.BOOL
            is com.visorcraft.radioshed.source.BackupSettingValue.Str -> SettingType.STR
            is com.visorcraft.radioshed.source.BackupSettingValue.IntValue -> SettingType.INT
            is com.visorcraft.radioshed.source.BackupSettingValue.LongValue -> SettingType.LONG
            is com.visorcraft.radioshed.source.BackupSettingValue.FloatValue -> SettingType.FLOAT
        }

    /**
     * Source-of-truth type for each user-portable settings key. Mirrors
     * what [dumpForBackup] writes. Returns null for keys not on the
     * known list — those flow through restore as-is so a newer bundle
     * adding a new key isn't dropped on the floor.
     */
    private fun expectedTypeForKey(key: String): SettingType? = when (key) {
        KEY_THEME_MODE, KEY_CONNECTIVITY_POLICY,
        KEY_LIB_ARTIST_SORT, KEY_LIB_ALBUM_SORT, KEY_LIB_GENRE_SORT,
        KEY_FILE_BROWSER_SORT, KEY_SEARCH_SCOPE,
        KEY_ALBUM_TRACK_SORT, KEY_ARTIST_TRACK_SORT, KEY_GENRE_TRACK_SORT,
        KEY_NOW_PLAYING_LAYOUT,
        KEY_FILE_BROWSER_PINS,
        KEY_THEME_COLOR_SOURCE,
        KEY_NETPOLICY_OVERRIDE_COVERART, KEY_NETPOLICY_OVERRIDE_LYRICS,
        KEY_STATIONS_SORT,
        KEY_SUBSONIC_URL, KEY_SUBSONIC_USERNAME,
        KEY_DJ_FREQUENCY, KEY_DJ_TEMPLATES,
        KEY_DRIVE_TRIGGERS, KEY_DRIVE_QUICKLIST, KEY_DRIVE_BUTTONS -> SettingType.STR
        KEY_MATERIAL_YOU, KEY_REDUCE_MOTION, KEY_DRIVE_MODE,
        KEY_REPLAY_GAIN, KEY_AUTO_RESUME_STATION, KEY_AUTO_BACKUP,
        KEY_AUTO_RESCAN_ON_LAUNCH, KEY_EQ_ENABLED,
        KEY_STATIONS_HIDE_BUILTINS, KEY_SLEEP_STOP_ON_EXPIRE,
        KEY_KEEP_SCREEN_ON, KEY_AUTO_FETCH_LYRICS, KEY_HAPTICS_ENABLED,
        KEY_LISTENBRAINZ_ENABLED,
        KEY_DJ_ENABLED,
        KEY_DRIVE_AUTO_EXIT -> SettingType.BOOL
        KEY_USER_VOLUME, KEY_REPLAY_GAIN_PREAMP,
        KEY_FONT_SCALE, KEY_LYRICS_SCALE, KEY_PLAYBACK_SPEED,
        KEY_DJ_DUCK_FRACTION -> SettingType.FLOAT
        KEY_END_FADE_SEC, KEY_STATIONS_TILE_SIZE,
        KEY_LYRICS_SYNC_OFFSET, KEY_STATS_WINDOW_DAYS,
        KEY_BACKUP_RETENTION,
        KEY_THEME_SEED, KEY_ACCENT_SEED,
        KEY_DJ_FREQUENCY_N, KEY_DJ_FREQUENCY_MINUTES,
        KEY_DJ_QUIET_START_MIN, KEY_DJ_QUIET_END_MIN -> SettingType.INT
        else -> null
    }

    companion object {
        const val KEY_THEME_MODE = "ui.themeMode"
        const val KEY_MATERIAL_YOU = "ui.materialYou"
        const val KEY_THEME_COLOR_SOURCE = "ui.themeColorSource"
        const val KEY_THEME_SEED = "ui.themeSeedArgb"
        const val KEY_ACCENT_SEED = "ui.accentSeedArgb"

        // Brand defaults — match `LightPrimary` / `LightSecondary` in
        // `ui/theme/Color.kt` (Material 3 baseline Blue). Pre-set on
        // the Custom path so flipping the source picker doesn't dump
        // the user onto a stark white theme until they've opened the
        // wheel.
        // The values exceed `Int.MAX_VALUE` when interpreted as
        // unsigned — the explicit `.toInt()` forces sign-extension so
        // we round-trip cleanly through Settings.putInt without a
        // "literal out of range" diagnostic.
        const val DEFAULT_THEME_SEED_ARGB: Int = 0xFF0061A4.toInt()
        const val DEFAULT_ACCENT_SEED_ARGB: Int = 0xFF535F70.toInt()
        const val KEY_USER_VOLUME = "audio.userVolume"
        const val KEY_REDUCE_MOTION = "ui.reduceMotion"
        const val KEY_FONT_SCALE = "ui.fontScale"
        const val KEY_STATIONS_TILE_SIZE = "ui.stations.tileSizeDp"
        const val KEY_STATIONS_SORT = "ui.stations.sort"
        const val KEY_STATIONS_HIDE_BUILTINS = "ui.stations.hideBuiltIns"
        const val KEY_ALBUM_TRACK_SORT = "ui.album.trackSort"
        const val KEY_ARTIST_TRACK_SORT = "ui.artist.trackSort"
        const val KEY_GENRE_TRACK_SORT = "ui.genre.trackSort"
        const val KEY_LYRICS_SYNC_OFFSET = "ui.lyrics.syncOffsetMs"
        const val KEY_STATS_WINDOW_DAYS = "ui.stats.windowDays"
        const val KEY_BACKUP_RETENTION = "backup.retentionCount"
        const val KEY_SLEEP_STOP_ON_EXPIRE = "audio.sleep.stopOnExpire"
        const val KEY_PLAYBACK_SPEED = "audio.playbackSpeed"
        const val KEY_KEEP_SCREEN_ON = "ui.keepScreenOn"
        const val KEY_AUTO_FETCH_LYRICS = "lyrics.autoFetchOverNetwork"
        const val KEY_HAPTICS_ENABLED = "ui.hapticsEnabled"
        const val KEY_LISTENBRAINZ_ENABLED = "scrobble.listenbrainz.enabled"
        const val KEY_LISTENBRAINZ_TOKEN = "scrobble.listenbrainz.token"
        const val KEY_SUBSONIC_URL = "cloud.subsonic.url"
        const val KEY_SUBSONIC_USERNAME = "cloud.subsonic.username"
        const val KEY_SUBSONIC_PASSWORD = "cloud.subsonic.password"
        const val KEY_SEARCH_SCOPE = "ui.search.scope"
        const val KEY_NOW_PLAYING_LAYOUT = "ui.now_playing.layout"
        const val KEY_CONNECTIVITY_POLICY = "online.connectivityPolicy"
        const val KEY_NETPOLICY_OVERRIDE_COVERART = "online.netpolicy.override.coverart"
        const val KEY_NETPOLICY_OVERRIDE_LYRICS = "online.netpolicy.override.lyrics"
        const val KEY_ONBOARDING_DONE = "onboarding.complete"
        const val KEY_LAST_STATION_ID = "playback.lastStationId"
        const val KEY_FILE_MODE_BROWSER = "library.fileModeBrowser"
        const val KEY_GENRES_CANON = "library.migrations.genresCanonicalized"
        const val KEY_LIB_TAB = "library.browser.tab"
        const val KEY_LIB_SONG_SORT = "library.browser.songSort"
        const val KEY_LIB_ARTIST_SORT = "library.browser.artistSort"
        const val KEY_LIB_ALBUM_SORT = "library.browser.albumSort"
        const val KEY_LIB_GENRE_SORT = "library.browser.genreSort"
        const val KEY_FILE_BROWSER_SORT = "library.fileBrowser.sort"
        const val KEY_FILE_BROWSER_PINS = "library.fileBrowser.pinnedFolders"
        const val KEY_EQ_ENABLED = "audio.eq.enabled"
        const val KEY_EQ_BAND_PREFIX = "audio.eq.band."
        const val KEY_EQ_CUSTOM_PRESETS = "audio.eq.customPresets"
        const val KEY_REPLAY_GAIN = "audio.replayGain.enabled"
        const val KEY_REPLAY_GAIN_PREAMP = "audio.replayGain.preampDb"
        const val KEY_AUTO_RESUME_STATION = "playback.autoResumeLastStation"
        const val KEY_AUTO_BACKUP = "backup.autoEnabled"
        const val KEY_AUTO_BACKUP_LAST = "backup.autoLastAt"
        const val KEY_AUTO_RESCAN_ON_LAUNCH = "library.autoRescanOnLaunch"
        const val KEY_END_FADE_SEC = "audio.endFadeSec"
        const val KEY_LYRICS_SCALE = "ui.lyricsScale"
        const val KEY_DRIVE_MODE = "ui.driveMode"
        // ---- DJ ----
        const val KEY_DJ_ENABLED = "dj.enabled"
        const val KEY_DJ_FREQUENCY = "dj.frequency"
        const val KEY_DJ_FREQUENCY_N = "dj.frequencyN"
        const val KEY_DJ_FREQUENCY_MINUTES = "dj.frequencyMinutes"
        const val KEY_DJ_QUIET_START_MIN = "dj.quietStartMin"
        const val KEY_DJ_QUIET_END_MIN = "dj.quietEndMin"
        const val KEY_DJ_DUCK_FRACTION = "dj.duckFraction"
        /** Empty string = use DEFAULT_DJ_TEMPLATES from stations/DjTemplate.kt. */
        const val KEY_DJ_TEMPLATES = "dj.templates"
        /** Valid stored values for KEY_DJ_FREQUENCY. */
        val DJ_FREQUENCY_VALUES = setOf("off", "every", "every_n", "minutes", "station_change")

        // ---- Drive Mode ----
        /** JSON-serialized List<DriveModeTrigger>. Empty = no auto triggers. */
        const val KEY_DRIVE_TRIGGERS = "drive.triggers"
        /** Comma-joined station ids for Drive Mode quick-access (max 6). */
        const val KEY_DRIVE_QUICKLIST = "drive.quickList"
        /** Comma-joined transport action ids for the Drive Mode button bar. */
        const val KEY_DRIVE_BUTTONS = "drive.buttons"
        /** When true, Drive Mode exits automatically when no trigger condition is active. */
        const val KEY_DRIVE_AUTO_EXIT = "drive.autoExit"
        /** Default Drive Mode button bar ids when none are configured. */
        const val DRIVE_BUTTONS_DEFAULT = "previous,play_pause,next,station_picker"
        /**
         * Canonical ordered list of the Drive Mode transport action ids — the
         * single source of truth for both the allowed-id set (below) and the
         * UI's render order. The Drive Mode settings screen maps these ids onto
         * its own (id → label) lookup, failing fast if any id lacks a label, so
         * adding an id here without a matching label breaks visibly.
         */
        val DRIVE_BUTTON_ID_ORDER: List<String> = listOf(
            "previous", "play_pause", "next", "station_picker", "voice_search", "mute",
        )
        /** Allowed action ids for the Drive Mode button bar. Derived from
         *  [DRIVE_BUTTON_ID_ORDER] so the order list stays the one place ids live. */
        val DRIVE_BUTTON_IDS = DRIVE_BUTTON_ID_ORDER.toSet()
        /** Cadence for the auto-backup background export. 7 days = once
         *  per week, which matches typical user back-up cadence
         *  expectations (Google's auto-backup, Time Machine, etc.). */
        const val AUTO_BACKUP_INTERVAL_MS = 7L * 24L * 60L * 60L * 1_000L
        const val KEY_SEARCH_HISTORY = "search.recentQueries"
        const val SEARCH_HISTORY_CAP = 10
        /** Bookmarks-bar capacity. 12 keeps the row scannable at a
         *  glance — beyond that the user is better off using
         *  "Add as music folder" (which makes the folder a first-class
         *  library citizen). */
        const val FILE_BROWSER_PIN_CAP = 12

        /** Keys we never accept from a backup bundle, even if a
         *  malformed import tries. Per-device state + one-shot
         *  migration flags belong here. */
        val BLOCKED_RESTORE_KEYS = setOf(
            KEY_LAST_STATION_ID,
            KEY_AUTO_BACKUP_LAST,
            KEY_GENRES_CANON,
            KEY_LIB_TAB,
            KEY_LIB_SONG_SORT,
            KEY_SEARCH_HISTORY,
            KEY_EQ_CUSTOM_PRESETS,  // restored separately via the EQ presets list
            KEY_ONBOARDING_DONE,    // user should re-grant permissions on a fresh install
            KEY_FILE_MODE_BROWSER,
        )
    }
}
