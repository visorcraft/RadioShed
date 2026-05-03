package com.visorcraft.radioshed.source

import kotlinx.serialization.Serializable

/**
 * On-disk backup format. Versioned so we can evolve the schema without
 * breaking older files; importers must reject bundles with a future
 * version they don't know how to interpret.
 *
 * v1 scope:
 *   - User stations (built-ins are re-seeded on launch, no need to dump)
 *   - Playlists (tracks identified by file path)
 *   - Ratings (by track path)
 *
 * Folders are intentionally NOT backed up: they're already SAF tree
 * URIs that need re-permissioning on a new install / device. Settings
 * (theme, RG, EQ) survive Android's normal app-data backup, so we don't
 * duplicate them here.
 */
@Serializable
data class BackupBundle(
    val version: Int = CURRENT_VERSION,
    val exportedAt: Long,
    val stations: List<BackupStation>,
    val playlists: List<BackupPlaylist>,
    val ratings: List<BackupRating>,
    /**
     * Liked-song marks keyed by track path. Optional + defaulted for
     * forward-compat: v1 bundles don't have it, importers fall through
     * to an empty list and treat the absence as "no hearts to restore".
     */
    val likedTracks: List<BackupLikedTrack> = emptyList(),
    /**
     * App settings (theme, RG, drive mode, etc.) keyed by the same
     * setting key strings used internally. Restored verbatim into the
     * Settings store so a fresh install picks up the user's prefs.
     * Empty/absent on v1/v2 bundles.
     */
    val settings: Map<String, BackupSettingValue> = emptyMap(),
    /**
     * Custom EQ presets the user saved. Built-in presets aren't
     * dumped — they re-seed automatically.
     */
    val customEqPresets: List<BackupEqPreset> = emptyList(),
) {
    companion object {
        // v3: settings + custom EQ presets added.
        // v2: liked songs added.
        // Older importers see new fields via Kotlinx default
        // fallthrough; we bump so the explicit version gate
        // accepts the new payload.
        const val CURRENT_VERSION = 3
    }
}

@Serializable
data class BackupStation(
    val name: String,
    val color: Int,
    val icon: String? = null,
    val customSubtitle: String? = null,
    val sourceJson: String,
    val playbackJson: String,
    val isPinned: Boolean = false,
    val sortPosition: Int = 0,
)

@Serializable
data class BackupPlaylist(
    val name: String,
    val description: String? = null,
    val trackPaths: List<String>,
)

@Serializable
data class BackupRating(
    val trackPath: String,
    val stars: Double,
    val ratedAt: Long,
)

@Serializable
data class BackupLikedTrack(
    val trackPath: String,
    val likedAt: Long,
)

/**
 * Tagged union for settings values — the multiplatform-settings store
 * is type-aware (Boolean / String / Int / Long / Float), and we
 * preserve types on round-trip to avoid coercion surprises (e.g.
 * "false" string vs false boolean wouldn't round-trip cleanly).
 */
@Serializable
sealed interface BackupSettingValue {
    @Serializable @kotlinx.serialization.SerialName("bool")
    data class Bool(val value: Boolean) : BackupSettingValue
    @Serializable @kotlinx.serialization.SerialName("str")
    data class Str(val value: String) : BackupSettingValue
    @Serializable @kotlinx.serialization.SerialName("int")
    data class IntValue(val value: Int) : BackupSettingValue
    @Serializable @kotlinx.serialization.SerialName("long")
    data class LongValue(val value: Long) : BackupSettingValue
    @Serializable @kotlinx.serialization.SerialName("float")
    data class FloatValue(val value: Float) : BackupSettingValue
}

@Serializable
data class BackupEqPreset(
    val name: String,
    val gainsDb: List<Float>,
)
