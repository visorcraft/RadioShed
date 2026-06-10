package com.visorcraft.radioshed.source

import kotlin.test.Test
import kotlin.test.assertEquals

class WeatherFormatTest {

    @Test
    fun describeWmoCode_mapsEachBucket() {
        // One representative code per documented WMO bucket plus the boundary
        // values, so a future range edit can't silently shift a code into the
        // wrong bucket.
        val cases = listOf(
            0 to "clear",
            1 to "partly cloudy",
            2 to "partly cloudy",
            3 to "partly cloudy",
            45 to "foggy",
            48 to "foggy",
            51 to "rainy",
            61 to "rainy",
            67 to "rainy",
            71 to "snowy",
            77 to "snowy",
            86 to "snowy",
            95 to "stormy",
            99 to "stormy",
        )
        for ((code, expected) in cases) {
            assertEquals(expected, describeWmoCode(code), "weathercode $code")
        }
    }

    @Test
    fun describeWmoCode_unknownFallsBackToUnsettled() {
        // Gaps between buckets and out-of-range values both default.
        assertEquals("unsettled", describeWmoCode(4))
        assertEquals("unsettled", describeWmoCode(44))
        assertEquals("unsettled", describeWmoCode(70))
        assertEquals("unsettled", describeWmoCode(100))
        assertEquals("unsettled", describeWmoCode(-1))
    }

    @Test
    fun formatWeather_roundsToNearestDegree() {
        assertEquals("12 degrees and clear", formatWeather(WeatherSnapshot(11.6, "clear")))
        assertEquals("11 degrees and clear", formatWeather(WeatherSnapshot(11.4, "clear")))
        assertEquals("0 degrees and snowy", formatWeather(WeatherSnapshot(0.0, "snowy")))
        // roundToInt rounds half toward +infinity, so -2.5 -> -2 (not -3).
        assertEquals("-2 degrees and stormy", formatWeather(WeatherSnapshot(-2.5, "stormy")))
        assertEquals("-3 degrees and stormy", formatWeather(WeatherSnapshot(-2.6, "stormy")))
    }
}
