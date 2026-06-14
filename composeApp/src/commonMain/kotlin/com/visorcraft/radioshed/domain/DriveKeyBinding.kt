package com.visorcraft.radioshed.domain

import com.visorcraft.radioshed.data.RadioShedJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** Maps an Android [android.view.KeyEvent] keyCode to a Drive Mode action. */
@Serializable
data class DriveKeyBinding(
    val keyCode: Int,
    /** One of [com.visorcraft.radioshed.data.SettingsStore.DRIVE_BUTTON_ID_ORDER] ids. */
    val actionId: String,
)

private val DRIVE_KEY_BINDINGS_SERIALIZER = ListSerializer(DriveKeyBinding.serializer())

fun decodeDriveKeyBindings(json: String): List<DriveKeyBinding> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        RadioShedJson.decodeFromString(DRIVE_KEY_BINDINGS_SERIALIZER, json)
    }.getOrDefault(emptyList())
}

fun encodeDriveKeyBindings(bindings: List<DriveKeyBinding>): String =
    RadioShedJson.encodeToString(DRIVE_KEY_BINDINGS_SERIALIZER, bindings)
