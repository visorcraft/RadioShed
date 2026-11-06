package com.visorcraft.radioshed.car

import com.visorcraft.radioshed.domain.DriveKeyBinding
import com.visorcraft.radioshed.domain.decodeDriveKeyBindings

/**
 * Resolves a captured hardware key code to a Drive Mode transport action id
 * using the user's persisted [DriveKeyBinding] list.
 */
fun resolveDriveKeyAction(
    bindingsJson: String,
    keyCode: Int,
): String? =
    decodeDriveKeyBindings(bindingsJson)
        .firstOrNull { it.keyCode == keyCode }
        ?.actionId

/** Human-readable labels for common media / steering-wheel key codes (Android). */
fun driveKeyCodeLabel(keyCode: Int): String =
    when (keyCode) {
        85 -> "Play / Pause"
        87 -> "Next"
        88 -> "Previous"
        86 -> "Stop"
        164 -> "Headset hook"
        24 -> "Volume up"
        25 -> "Volume down"
        else -> "Key $keyCode"
    }

/** Key codes offered in the binding editor (media + volume). */
val COMMON_DRIVE_KEY_CODES: List<Int> = listOf(85, 87, 88, 86, 164, 24, 25)

/**
 * Drive Mode actions that [com.visorcraft.radioshed.MainActivity] can dispatch
 * from hardware keys. Transport actions run in the Activity; UI actions route
 * through [DriveKeyDispatcher] into [com.visorcraft.radioshed.ui.car.DriveTransportRow].
 */
val HARDWARE_DRIVE_KEY_ACTIONS: List<String> = listOf("previous", "play_pause", "next", "mute")

/** UI actions dispatched via [DriveKeyDispatcher] from hardware key bindings. */
val UI_DRIVE_KEY_ACTIONS: List<String> = listOf("station_picker", "voice_search")
