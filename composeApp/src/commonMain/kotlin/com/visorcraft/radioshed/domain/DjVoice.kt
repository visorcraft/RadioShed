package com.visorcraft.radioshed.domain

import kotlinx.serialization.Serializable

@Serializable
enum class DjEngine { System, ElevenLabs, Custom }

@Serializable
data class DjVoice(
    val id: Long,
    val name: String,
    val engine: DjEngine,
    val voiceId: String,
    val rate: Double = 1.0,
    val pitch: Double = 1.0,
    val isDefault: Boolean = false,
    val createdAt: Long,
)
