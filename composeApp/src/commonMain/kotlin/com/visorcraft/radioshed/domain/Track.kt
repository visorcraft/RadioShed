package com.visorcraft.radioshed.domain

import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val id: Long,
    val folderId: Long,
    val path: String,
    val fileName: String,
    val fileSize: Long,
    val fileCtime: Long,
    val fileMtime: Long,
    val title: String,
    val albumId: Long? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationMs: Long,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val format: String,
    val replayGainTrack: Double? = null,
    val replayGainAlbum: Double? = null,
    val replayGainTrackPeak: Double? = null,
    val bpm: Double? = null,
    val musicbrainzId: String? = null,
    val acoustidFingerprint: String? = null,
    val cueOffsetStartMs: Long? = null,
    val cueOffsetEndMs: Long? = null,
    val isAvailable: Boolean = true,
    val addedAt: Long,
    val updatedAt: Long,
)

@Serializable
data class Tag(val key: String, val value: String, val position: Int = 0)

@Serializable
data class Personnel(val role: String, val name: String, val position: Int = 0)
