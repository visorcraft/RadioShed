package com.visorcraft.radioshed.nav

/**
 * Lightweight navigation state for v1. We're deliberately not pulling in a
 * full navigation library yet — the surface is small and we want to avoid
 * coupling to any specific KMP nav library while Compose Multiplatform
 * navigation conventions are still settling.
 *
 * Promote to `androidx.navigation:navigation-compose` (KMP) when:
 *   - back-stack semantics get non-trivial (deep links, conditional routes)
 *   - we need predictive-back gesture handling on Android
 *   - station detail / now-playing routes need argument passing
 */
sealed interface AppScreen {

    /** First-launch flow: welcome → permissions → folder → scan → done. */
    data object Onboarding : AppScreen

    /** Post-onboarding home. Replaced by Stations Home in Phase 6. */
    data object Home : AppScreen

    /** Library settings — folder management, scan triggers. */
    data object LibrarySettings : AppScreen

    /** Full-text search across the library. */
    data object Search : AppScreen

    /** Stations Home — list of stations with tap-to-play. */
    data object Stations : AppScreen

    /** Full-screen Now Playing: art, scrubber, transport, rating. */
    data object NowPlaying : AppScreen

    /**
     * Station Editor — create a new station from scratch.
     * The editor itself is the same screen used for [EditStation] but
     * with no pre-filled values; we use a distinct route for clarity.
     */
    data object NewStation : AppScreen

    /**
     * Station Editor — edit an existing station by id.
     * Built-in stations open a constrained editor (cosmetic fields only:
     * name, subtitle, color); user-created stations get the full surface.
     */
    data class EditStation(val stationId: Long) : AppScreen
}
