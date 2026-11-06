package com.visorcraft.radioshed.domain

import kotlinx.serialization.Serializable

@Serializable
data class Folder(
    val id: Long,
    val displayName: String,
    val uri: String,
    val recursive: Boolean = true,
    val includeGlobs: List<String> = emptyList(),
    val excludeGlobs: List<String> = emptyList(),
    val lastScannedAt: Long? = null,
    val trackCount: Long = 0,
    val isAvailable: Boolean = true,
    /**
     * Optional per-folder accent color (ARGB packed), used for visual
     * organization in LibrarySettings. Null = no tag, default surface
     * styling.
     */
    val accentColor: Int? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
