package com.visorcraft.radioshed.domain

import kotlinx.serialization.Serializable

@Serializable
enum class CastTargetType { Chromecast, AirPlay, UPnP, Sonos, Plex, Kodi, Other }

@Serializable
data class CastTarget(
    val id: Long,
    val type: CastTargetType,
    val name: String,
    val address: String,
    val capabilitiesJson: String = "{}",
    val lastSeenAt: Long,
    val isPaired: Boolean = false,
    val autoCast: Boolean = false,
    val transcodingBitrateKbps: Int? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
