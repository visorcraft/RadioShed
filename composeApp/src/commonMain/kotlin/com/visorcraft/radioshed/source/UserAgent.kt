package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.BuildInfo

/**
 * Shared HTTP User-Agent for every outbound API client in `source/`
 * (Subsonic, ListenBrainz, MusicBrainz, Cover Art Archive, Wikipedia,
 * LRClib lyrics, Open-Meteo weather).
 *
 * MusicBrainz / ListenBrainz / CAA etiquette asks clients to identify
 * themselves as "AppName/Version (contact)" with an *accurate*
 * version. Deriving the string from the generated [BuildInfo] keeps it
 * locked to version.properties — the previous per-file literals had
 * already drifted ("RadioShed/0.1" vs "RadioShed/1.0" while the app
 * was at 1.3.0). We don't expose contact info; app name + URL +
 * version is sufficient for ToS compliance.
 */
const val USER_AGENT: String =
    "RadioShed/" + BuildInfo.VERSION_NAME + " (https://radioshed.app)"
