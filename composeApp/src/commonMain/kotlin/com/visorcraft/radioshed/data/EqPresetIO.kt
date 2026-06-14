package com.visorcraft.radioshed.data

/**
 * JSON round-trip for custom EQ presets — used by Equalizer's
 * Export / Import actions so users can move presets between
 * installs or share favourites with friends.
 *
 * Bundle envelope is the same shape as [com.visorcraft.radioshed.source.BackupBundle]:
 * a version field that future readers gate on, plus the payload.
 * Importers reject unknown versions rather than silently dropping
 * fields they don't understand.
 */
object EqPresetIO {
    fun toJson(presets: List<CustomEqPreset>): String {
        val bundle = EqPresetBundle(presets = presets)
        return RadioShedJson.encodeToString(EqPresetBundle.serializer(), bundle)
    }

    /**
     * Parse a JSON bundle. Returns null when the input isn't valid
     * JSON, fails to decode as [EqPresetBundle], or has a version
     * we don't know how to interpret. Caller treats null as "import
     * failed" and surfaces a generic error message.
     */
    fun fromJson(json: String): EqPresetBundle? {
        val bundle =
            runCatching {
                RadioShedJson.decodeFromString(EqPresetBundle.serializer(), json)
            }.getOrNull() ?: return null
        if (bundle.version < 1 || bundle.version > EqPresetBundle.CURRENT_VERSION) return null
        return bundle
    }
}
