package com.visorcraft.radioshed.domain

import kotlinx.serialization.Serializable

@Serializable
data class Rating(
    val trackId: Long,
    val stars: Double,
    val ratedAt: Long,
) {
    init {
        require(stars in 0.0..5.0) { "stars must be in [0,5], was $stars" }
    }
}
