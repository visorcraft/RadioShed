package com.visorcraft.radioshed.domain

import kotlinx.serialization.Serializable

@Serializable
data class Album(
    val id: Long,
    val title: String,
    val artistId: Long? = null,
    val sortTitle: String,
    val year: Int? = null,
    val musicbrainzId: String? = null,
    val artworkUri: String? = null,
    val trackCount: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
)
