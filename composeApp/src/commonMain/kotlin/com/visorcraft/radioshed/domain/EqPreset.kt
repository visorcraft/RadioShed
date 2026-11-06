package com.visorcraft.radioshed.domain

import kotlinx.serialization.Serializable

@Serializable
data class EqBand(
    val frequencyHz: Int,
    val gainDb: Double,
    val q: Double = 1.0,
)

@Serializable
data class EqPreset(
    val id: Long,
    val name: String,
    val bands: List<EqBand>,
    val preAmpDb: Double = 0.0,
    val isBuiltIn: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class EqOutputBinding(
    val outputKey: String,
    val eqPresetId: Long?,
    val boundAt: Long,
)
