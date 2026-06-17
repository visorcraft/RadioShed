package com.visorcraft.radioshed.ui.util

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    // Regression: values in (-1, 0) must keep their minus sign. Integer
    // division truncates toward zero, so a naive intPart would drop it and a
    // -0.5 dB EQ cut / ReplayGain / preamp would render as a "0.5" boost.
    @Test
    fun formatFixedKeepsSignForSubUnitNegatives() {
        assertEquals("-0.5", (-0.5).formatFixed(1))
        assertEquals("-0.50", (-0.5).formatFixed(2))
        assertEquals("-0.07", (-0.07).formatFixed(2))
    }

    @Test
    fun formatFixedKeepsSignForLargerNegatives() {
        assertEquals("-6.50", (-6.5).formatFixed(2))
        assertEquals("-12.0", (-12.0).formatFixed(1))
    }

    @Test
    fun formatFixedPositiveAndZero() {
        assertEquals("0.0", 0.0.formatFixed(1))
        assertEquals("3.1", 3.14.formatFixed(1))
        assertEquals("1.23", 1.234.formatFixed(2))
        assertEquals("2.0", 1.96.formatFixed(1))
    }

    @Test
    fun formatFixedZeroDecimalsRoundsToInteger() {
        assertEquals("4", 3.6.formatFixed(0))
        assertEquals("3", 3.4.formatFixed(0))
        // Negative that rounds to a non-zero magnitude keeps its sign.
        assertEquals("-1", (-0.6).formatFixed(0))
    }

    // A value that rounds away to zero magnitude must never render as
    // "-0" / "-0.0" — and a genuine sub-unit negative that rounds UP to
    // magnitude 1 must keep its sign.
    @Test
    fun formatFixedNeverRendersNegativeZero() {
        assertEquals("0.0", (-0.0).formatFixed(1))
        assertEquals("0.0", (-0.04).formatFixed(1))
        assertEquals("0", (-0.4).formatFixed(0))
        assertEquals("-1.0", (-0.96).formatFixed(1))
    }

    @Test
    fun formatSignedFixedAlwaysCarriesExactlyOneSign() {
        assertEquals("+0.5", (0.5).formatSignedFixed(1))
        assertEquals("-0.5", (-0.5).formatSignedFixed(1))
        assertEquals("+0.0", (0.0).formatSignedFixed(1))
    }

    @Test
    fun formatFileSizeTiers() {
        assertEquals("—", formatFileSize(0))
        assertEquals("512 B", formatFileSize(512))
        assertEquals("1 KB", formatFileSize(1024))
        assertEquals("1.0 MB", formatFileSize(1_048_576))
        assertEquals("1.00 GB", formatFileSize(1_073_741_824))
    }

    @Test
    fun formatDurationMsRendersMinutesAndSeconds() {
        assertEquals("—", formatDurationMs(0))
        assertEquals("3:09", formatDurationMs(189_000))
        // showHours = false keeps minutes flat even past an hour.
        assertEquals("75:00", formatDurationMs(4_500_000))
        // showHours = true splits the hour out.
        assertEquals("1:15:00", formatDurationMs(4_500_000, showHours = true))
    }

    @Test
    fun formatDurationSecondsMatchesMillisHelper() {
        assertEquals("4:05", formatDurationSeconds(245))
    }
}
