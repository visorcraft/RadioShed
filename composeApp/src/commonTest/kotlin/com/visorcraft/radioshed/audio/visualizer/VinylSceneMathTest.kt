package com.visorcraft.radioshed.audio.visualizer

import kotlin.test.Test
import kotlin.test.assertEquals

class VinylSceneMathTest {
    @Test
    fun tonearmTracksClampedProgress() {
        assertEquals(110f, vinylTonearmAngle(-0.2f))
        assertEquals(117.5f, vinylTonearmAngle(0.5f))
        assertEquals(125f, vinylTonearmAngle(1.2f))
    }

    @Test
    fun recordRotationHonorsReducedMotion() {
        assertEquals(200f, vinylRotationDegrees(timeMs = 1_000L, reduceMotion = false))
        assertEquals(2f, vinylRotationDegrees(timeMs = 1_000L, reduceMotion = true))
    }

    @Test
    fun progressArcUsesFullCircleAtRunOut() {
        assertEquals(180f, vinylProgressSweep(0.5f))
        assertEquals(360f, vinylProgressSweep(1f))
    }
}
