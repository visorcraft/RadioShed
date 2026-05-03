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
) {
    companion object {
        // v2: liked songs added. Older importers reading a v2 bundle
        // see the field via Kotlinx Serialization's default fallthrough
        // and ignore it gracefully — but we still bump so the version
        // gate explicitly accepts it.
        const val CURRENT_VERSION = 2
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
