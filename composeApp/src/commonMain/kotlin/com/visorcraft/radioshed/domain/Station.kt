package com.visorcraft.radioshed.domain

import kotlinx.serialization.Serializable

@Serializable
data class Station(
    val id: Long,
    val name: String,
    val color: Int = 0,
    val icon: String? = null,
    val artworkUri: String? = null,
    val sortPosition: Int = 0,
    val customSubtitle: String? = null,
    val source: SourceRule,
    val playback: PlaybackRule = PlaybackRule(),
    val stats: StationStats = StationStats(),
    val isPinned: Boolean = false,
    val isBuiltIn: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class StationStats(
    val playCount: Long = 0,
    val lastPlayedAt: Long? = null,
    val totalListenMs: Long = 0,
    val skipRate: Double = 0.0,
)
