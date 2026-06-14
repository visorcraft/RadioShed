package com.visorcraft.radioshed.domain

import com.visorcraft.radioshed.data.RadioShedJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Describes a condition that automatically activates Drive Mode.
 * Persisted as a JSON list under [com.visorcraft.radioshed.data.SettingsStore]
 * KEY_DRIVE_TRIGGERS — callers own encode/decode; SettingsStore stays dumb.
 *
 * Discriminator key is `"type"` (matches [com.visorcraft.radioshed.data.RadioShedJson]'s
 * `classDiscriminator`), same pattern as [SourceRule].
 */
@Serializable
sealed interface DriveModeTrigger {
    /** Activate Drive Mode when a Bluetooth device with the given [mac] connects. */
    @Serializable
    @SerialName("bt_device")
    data class BtDevice(val mac: String, val name: String) : DriveModeTrigger

    /**
     * Activate Drive Mode during a scheduled time window.
     *
     * [startMin] and [endMin] are minutes from midnight in local time (0..1439).
     * The window may cross midnight — e.g. startMin=1380 (23:00) with
     * endMin=60 (01:00) spans midnight. Callers detect cross-midnight by
     * checking `startMin > endMin`.
     */
    @Serializable
    @SerialName("schedule")
    data class Schedule(val startMin: Int, val endMin: Int) : DriveModeTrigger

    /**
     * Activate Drive Mode when ground speed reaches [enterKmh]; the trigger
     * stays active until speed drops below [exitKmh] (hysteresis prevents
     * flicker at the threshold). Requires a platform [SpeedWatcher].
     */
    @Serializable
    @SerialName("speed")
    data class Speed(val enterKmh: Float, val exitKmh: Float) : DriveModeTrigger
}

/** Serializer for the persisted trigger list. */
private val DRIVE_TRIGGERS_SERIALIZER = ListSerializer(DriveModeTrigger.serializer())

/**
 * Decodes the persisted trigger JSON blob into a list of triggers.
 *
 * Returns an empty list when [json] is blank or fails to decode, so a corrupt
 * or empty blob degrades to "no auto triggers" through a single shared decision
 * point. This is the canonical parser used by both
 * [com.visorcraft.radioshed.car.DriveModeController] (runtime evaluation) and
 * the Drive Mode settings editor (display + edit).
 */
fun decodeDriveTriggers(json: String): List<DriveModeTrigger> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        RadioShedJson.decodeFromString(DRIVE_TRIGGERS_SERIALIZER, json)
    }.getOrDefault(emptyList())
}

/** Serializes a trigger list back into the persisted JSON blob form. */
fun encodeDriveTriggers(triggers: List<DriveModeTrigger>): String =
    RadioShedJson.encodeToString(DRIVE_TRIGGERS_SERIALIZER, triggers)
