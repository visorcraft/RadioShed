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
) {
    companion object {
        const val CURRENT_VERSION = 1
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
