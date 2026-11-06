package com.visorcraft.radioshed.ui.car

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Drive Mode control sizing for the current viewport. Phone layouts keep the
 * fixed 88/108 dp targets; landscape car consoles (1024×600, 1920×720, …) scale
 * buttons up toward the SPEC's 120 dp minimum so the transport row fills the
 * wider, shorter viewport without shrinking below at-a-glance hit targets.
 */
data class DriveLayoutMetrics(
    val buttonSize: Dp,
    val emphasizedButtonSize: Dp,
    val iconSize: Dp,
    val emphasizedIconSize: Dp,
)

private val PHONE_BUTTON = 88.dp
private val PHONE_EMPHASIZED = 108.dp
private val PHONE_ICON = 48.dp
private val PHONE_EMPHASIZED_ICON = 64.dp
private val CAR_CONSOLE_MIN_BUTTON = 120.dp
private val CAR_CONSOLE_MAX_BUTTON = 168.dp

/**
 * True for landscape viewports whose aspect ratio matches typical head-unit
 * consoles (wider than tall, not ultra-wide phone landscape).
 */
fun isCarConsoleLayout(
    maxWidth: Dp,
    maxHeight: Dp,
): Boolean {
    if (maxWidth < 600.dp || maxHeight >= maxWidth) return false
    val ratio = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
    return ratio in 1.25f..2.75f
}

fun driveLayoutMetrics(
    maxWidth: Dp,
    maxHeight: Dp,
): DriveLayoutMetrics {
    if (!isCarConsoleLayout(maxWidth, maxHeight)) {
        return DriveLayoutMetrics(
            buttonSize = PHONE_BUTTON,
            emphasizedButtonSize = PHONE_EMPHASIZED,
            iconSize = PHONE_ICON,
            emphasizedIconSize = PHONE_EMPHASIZED_ICON,
        )
    }
    // Scale from viewport height — car consoles are height-constrained.
    val targetButton = (maxHeight * 0.24f).coerceIn(CAR_CONSOLE_MIN_BUTTON, CAR_CONSOLE_MAX_BUTTON)
    val emphasized = (targetButton * 1.18f).coerceAtMost(maxHeight * 0.30f)
    val iconScale = targetButton / PHONE_BUTTON
    return DriveLayoutMetrics(
        buttonSize = targetButton,
        emphasizedButtonSize = emphasized,
        iconSize = (PHONE_ICON * iconScale).coerceAtMost(targetButton * 0.55f),
        emphasizedIconSize = (PHONE_EMPHASIZED_ICON * iconScale).coerceAtMost(emphasized * 0.58f),
    )
}

@Composable
fun rememberDriveLayoutMetrics(
    maxWidth: Dp,
    maxHeight: Dp,
): DriveLayoutMetrics = remember(maxWidth, maxHeight) { driveLayoutMetrics(maxWidth, maxHeight) }
