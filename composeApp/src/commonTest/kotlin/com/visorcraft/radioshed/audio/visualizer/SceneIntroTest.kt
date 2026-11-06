package com.visorcraft.radioshed.audio.visualizer

import kotlin.test.Test
import kotlin.test.assertEquals

class SceneIntroTest {
    @Test
    fun introProgressIsBoundedAndReachesFullVisibility() {
        assertEquals(0f, sceneIntroProgress(ageMs = 0L, reduceMotion = false))
        assertEquals(0.5f, sceneIntroProgress(ageMs = 210L, reduceMotion = false))
        assertEquals(1f, sceneIntroProgress(ageMs = 420L, reduceMotion = false))
        assertEquals(1f, sceneIntroProgress(ageMs = 900L, reduceMotion = false))
    }

    @Test
    fun pausedSceneSkipsIntroInsteadOfStayingBlack() {
        assertEquals(1f, sceneIntroProgress(ageMs = 0L, reduceMotion = false, playing = false))
    }

    @Test
    fun reducedMotionUsesShorterIntro() {
        assertEquals(1f, sceneIntroProgress(ageMs = 150L, reduceMotion = true))
    }
}
