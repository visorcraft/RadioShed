package com.visorcraft.radioshed.domain

import com.visorcraft.radioshed.data.RadioShedJson
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DriveModeTriggerTest {

    @Test
    fun mixedList_roundTrip() {
        // One BtDevice + one Schedule with a cross-midnight window.
        val triggers: List<DriveModeTrigger> = listOf(
            DriveModeTrigger.BtDevice(mac = "AA:BB:CC:DD:EE:FF", name = "Car Stereo"),
            DriveModeTrigger.Schedule(startMin = 1380, endMin = 60),
        )

        val json = RadioShedJson.encodeToString(
            ListSerializer(DriveModeTrigger.serializer()),
            triggers,
        )
        assertTrue(
            json.contains("\"type\":\"bt_device\""),
            "polymorphic discriminator bt_device present: $json",
        )
        assertTrue(
            json.contains("\"type\":\"schedule\""),
            "polymorphic discriminator schedule present: $json",
        )

        val decoded = RadioShedJson.decodeFromString(
            ListSerializer(DriveModeTrigger.serializer()),
            json,
        )
        assertEquals(triggers, decoded, "round-trip failed: json=$json")
    }
}
