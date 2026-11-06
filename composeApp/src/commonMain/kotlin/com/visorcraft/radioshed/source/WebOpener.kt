package com.visorcraft.radioshed.source

/**
 * Opens [url] in the user's preferred browser. Returns true on
 * success; false when the platform refused (no browser installed,
 * url malformed, intent canceled). Used by About → "Source" and
 * Track Info → Wikipedia search; deliberately not a full WebView
 * because RadioShed wants to stay narrow and a system browser
 * gives the user their cookies / login state.
 */
internal expect fun openWebUrl(url: String): Boolean
