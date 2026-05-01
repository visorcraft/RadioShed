package com.visorcraft.radioshed.domain

import kotlinx.serialization.Serializable

@Serializable
data class Artist(
    val id: Long,
    val name: String,
    val sortName: String,
    val musicbrainzId: String? = null,
    val imageUri: String? = null,
    val bio: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
