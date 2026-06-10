package com.visorcraft.radioshed.stations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DjTemplateTest {

    private val vars = DjTemplateVars(
        station = "Morning Drive",
        track = "Blue Monday",
        artist = "New Order",
        nextTrack = "Age of Consent",
        time = "7:45 am",
        weather = null,
    )

    @Test
    fun substitutesAllKnownVariables() {
        val out = DjTemplate.render("Now on {station}: {track} by {artist}", vars)
        assertEquals("Now on Morning Drive: Blue Monday by New Order", out)
    }

    @Test
    fun nullVariableDropsItsClause() {
        // Sentences containing an unresolved variable are dropped entirely
        // rather than reading "the weather is null".
        val out = DjTemplate.render(
            "That was {track}. Outside it's {weather}. Up next, {next_track}.",
            vars,
        )
        assertEquals("That was Blue Monday. Up next, Age of Consent.", out)
    }

    @Test
    fun unknownVariableIsLeftVerbatim() {
        val out = DjTemplate.render("Hello {nope}", vars)
        assertEquals("Hello {nope}", out)
    }

    @Test
    fun pickVariantIsDeterministicForSeed() {
        val variants = listOf("a {track}", "b {track}", "c {track}")
        val first = DjTemplate.pickVariant(variants, seed = 42L)
        val second = DjTemplate.pickVariant(variants, seed = 42L)
        assertEquals(first, second)
        assertTrue(variants.contains(first))
    }

    @Test
    fun consecutivePicksAvoidImmediateRepeat() {
        val variants = listOf("a", "b")
        val first = DjTemplate.pickVariant(variants, seed = 1L)
        val next = DjTemplate.pickVariant(variants, seed = 2L, avoid = first)
        assertTrue(next != first)
    }
}
