package com.visorcraft.radioshed.data

import kotlinx.serialization.json.Json

/** Shared JSON serializer used for SourceRule/PlaybackRule/glob-list/etc. columns. */
internal val RadioShedJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }
