package com.visorcraft.radioshed.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Playback-side of the Station model (SPEC §4.2).
 */
@Serializable
data class PlaybackRule(
    val order: PlayOrder = PlayOrder.SmartShuffle,
    val noRepeatDepth: Int = 25,
    val artistSpacing: Int = 5,
    val albumSpacing: Int = 3,
    val weighting: Weighting = Weighting.Uniform,
    val crossfade: Crossfade = Crossfade.Smart,
    val gapless: Boolean = true,
    val skipOnDislike: SkipOnDislike = SkipOnDislike.Off,
    val repeat: Boolean = true,
    val eqPresetId: Long? = null,
    val replayGainOverride: ReplayGainMode? = null,
    val dj: DjConfig = DjConfig(),
    val autoRotateRefreshHours: Int? = null,
)

@Serializable
enum class PlayOrder { Shuffle, Sequential, WeightedRandom, SmartShuffle }

@Serializable
enum class Weighting { Uniform, FavorUnderPlayed, FavorHigherRated, FavorRecent }

@Serializable
enum class CrossfadeCurve { EqualPower, Linear, SCurve }

@Serializable
sealed interface Crossfade {
    @Serializable
    @SerialName("off")
    object Off : Crossfade

    @Serializable
    @SerialName("fixed")
    data class Fixed(
        val seconds: Int,
        val curve: CrossfadeCurve = CrossfadeCurve.EqualPower,
    ) : Crossfade

    @Serializable
    @SerialName("smart")
    object Smart : Crossfade
}

@Serializable
sealed interface SkipOnDislike {
    @Serializable
    @SerialName("off")
    object Off : SkipOnDislike

    @Serializable
    @SerialName("artist_for_minutes")
    data class ArtistForMinutes(val minutes: Int) : SkipOnDislike

    @Serializable
    @SerialName("album")
    object Album : SkipOnDislike

    @Serializable
    @SerialName("track")
    object TrackOnly : SkipOnDislike
}

@Serializable
enum class ReplayGainMode { Track, Album, Off }

@Serializable
data class DjConfig(
    val enabled: Boolean = false,
    val frequency: DjFrequency = DjFrequency.StationChangeOnly,
    val quietHoursStartHour: Int? = null,
    val quietHoursEndHour: Int? = null,
)

@Serializable
sealed interface DjFrequency {
    @Serializable
    @SerialName("off")
    object Off : DjFrequency

    @Serializable
    @SerialName("every_track")
    object EveryTrack : DjFrequency

    @Serializable
    @SerialName("every_n")
    data class EveryN(val n: Int) : DjFrequency

    @Serializable
    @SerialName("every_x_minutes")
    data class EveryXMinutes(val minutes: Int) : DjFrequency

    @Serializable
    @SerialName("station_change_only")
    object StationChangeOnly : DjFrequency
}
