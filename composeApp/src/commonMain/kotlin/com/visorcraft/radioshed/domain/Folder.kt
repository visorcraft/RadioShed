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
    val createdAt: Long,
    val updatedAt: Long,
)
