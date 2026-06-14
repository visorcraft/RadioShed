package com.visorcraft.radioshed.ui.car

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DriveLayoutMetricsTest {

    @Test
    fun phoneLayoutKeepsDefaultButtonSizes() {
        val metrics = driveLayoutMetrics(maxWidth = 400.dp, maxHeight = 800.dp)
        assertEquals(88.dp, metrics.buttonSize)
        assertEquals(108.dp, metrics.emphasizedButtonSize)
    }

    @Test
    fun carConsoleScalesUp() {
        assertTrue(isCarConsoleLayout(maxWidth = 1280.dp, maxHeight = 720.dp))
        val metrics = driveLayoutMetrics(maxWidth = 1280.dp, maxHeight = 720.dp)
        assertTrue(metrics.buttonSize >= 120.dp)
    }

    @Test
    fun portraitPhoneIsNotCarConsole() {
        assertFalse(isCarConsoleLayout(maxWidth = 400.dp, maxHeight = 800.dp))
    }
}
