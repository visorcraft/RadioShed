package com.visorcraft.radioshed.domain

import kotlinx.serialization.Serializable

@Serializable
data class Playlist(
    val id: Long,
    val name: String,
    val description: String? = null,
    val artworkUri: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
