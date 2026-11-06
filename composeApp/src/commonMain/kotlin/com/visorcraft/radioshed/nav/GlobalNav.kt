package com.visorcraft.radioshed.nav

import androidx.compose.runtime.compositionLocalOf

/**
 * Composition-local accessors to the app's navigation actions, used
 * by deep widgets like [com.visorcraft.radioshed.ui.playlists.TrackContextSheet]
 * that get rendered from many call sites and don't have a clean
 * ancestor route to plumb explicit callbacks through.
 *
 * App.kt installs the real implementations once at the root of the
 * composition. Defaults are no-ops so hosts that don't install
 * (tests, previews) silently drop the navigation requests rather
 * than crashing on `LocalProvidableCompositionLocal currentValue`.
 */
val LocalNavigateToAlbum = compositionLocalOf<(Long) -> Unit> { {} }
val LocalNavigateToArtist = compositionLocalOf<(Long) -> Unit> { {} }

/**
 * Navigate to the Lyrics screen for the given track. Hosts wire this
 * to "play the track first, then navigate to lyrics" — the Lyrics
 * screen displays lyrics for whatever is currently playing, so the
 * caller has to drive playback to get meaningful results.
 */
val LocalNavigateToLyrics = compositionLocalOf<(Long) -> Unit> { {} }

/** Whether the host has actually installed handlers. The
 *  TrackContextSheet hides the Go-to-album/artist rows when no
 *  navigator is wired so users don't see disabled actions that
 *  appear broken. */
val LocalNavInstalled = compositionLocalOf { false }
