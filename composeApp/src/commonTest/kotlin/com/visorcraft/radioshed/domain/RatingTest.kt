package com.visorcraft.radioshed.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RatingTest {
    @Test
    fun stars_in_valid_range() {
        Rating(trackId = 1, stars = 0.0, ratedAt = 0)
        Rating(trackId = 1, stars = 2.5, ratedAt = 0)
        Rating(trackId = 1, stars = 5.0, ratedAt = 0)
    }

    @Test
    fun stars_below_zero_rejected() {
        assertFailsWith<IllegalArgumentException> { Rating(trackId = 1, stars = -0.5, ratedAt = 0) }
    }

    @Test
    fun stars_above_five_rejected() {
        assertFailsWith<IllegalArgumentException> { Rating(trackId = 1, stars = 5.5, ratedAt = 0) }
    }

    @Test
    fun copy_preserves_validation() {
        val r = Rating(trackId = 1, stars = 4.5, ratedAt = 100)
        assertEquals(4.5, r.copy().stars)
        assertFailsWith<IllegalArgumentException> { r.copy(stars = 9.0) }
    }
}
