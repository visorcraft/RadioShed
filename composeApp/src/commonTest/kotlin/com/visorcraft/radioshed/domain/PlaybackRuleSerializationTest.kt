package com.visorcraft.radioshed.domain

import com.visorcraft.radioshed.data.RadioShedJson
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackRuleSerializationTest {

    @Test
    fun defaults_round_trip() {
        val original = PlaybackRule()
        val json = RadioShedJson.encodeToString(PlaybackRule.serializer(), original)
        val decoded = RadioShedJson.decodeFromString(PlaybackRule.serializer(), json)
        assertEquals(original, decoded)
        assertEquals(PlayOrder.SmartShuffle, decoded.order)
        assertEquals(25, decoded.noRepeatDepth)
        assertEquals(Crossfade.Smart, decoded.crossfade)
    }

    @Test
    fun fixed_crossfade_round_trip() {
        val original = PlaybackRule(crossfade = Crossfade.Fixed(seconds = 6, curve = CrossfadeCurve.SCurve))
        val decoded = RadioShedJson.decodeFromString(
            PlaybackRule.serializer(),
            RadioShedJson.encodeToString(PlaybackRule.serializer(), original),
        )
        assertEquals(original, decoded)
    }

    @Test
    fun dj_config_with_quiet_hours_round_trip() {
        val original = PlaybackRule(
            dj = DjConfig(
                enabled = true,
                frequency = DjFrequency.EveryN(3),
                quietHoursStartHour = 22,
                quietHoursEndHour = 6,
            ),
        )
        val decoded = RadioShedJson.decodeFromString(
            PlaybackRule.serializer(),
            RadioShedJson.encodeToString(PlaybackRule.serializer(), original),
        )
        assertEquals(original, decoded)
    }
}
