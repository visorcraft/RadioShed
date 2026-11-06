package com.visorcraft.radioshed.domain

import kotlinx.serialization.Serializable

@Serializable
enum class ConnectivityPolicy { Always, WiFiOnly, Never, Manual }

@Serializable
enum class ResumePolicy { LastStation, Silence, SpecificStation }

@Serializable
data class SleepTimer(
    val mode: SleepTimerMode = SleepTimerMode.Off,
    val durationMinutes: Int? = null,
    val fadeOutSeconds: Int = 30,
)

@Serializable
enum class SleepTimerMode { Off, AfterMinutes, EndOfStation, EndOfTrack }
