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

    /** Library Browser — Songs / Artists / Albums. */
    data object Library : AppScreen

    /** Global app settings — theme, accessibility, about. */
    data object AppSettings : AppScreen

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

    /**
     * Station detail — read-only-ish view of source rule, candidate
     * count, pinned tracks, top played, recently played for a single
     * station. Opened from the long-press action sheet.
     */
    data class StationDetail(val stationId: Long) : AppScreen

    /** Drag-to-reorder station list — focused screen, no carousels. */
    data object ReorderStations : AppScreen

    /** Album detail — cover art, track list, play / shuffle all. */
    data class AlbumDetail(val albumId: Long) : AppScreen

    /** Artist detail — albums + tracks for an artist, drill-in to album. */
    data class ArtistDetail(val artistId: Long) : AppScreen

    /** Genre detail — track list for a single genre tag. */
    data class GenreDetail(val genre: String) : AppScreen

    /** Full-screen synced lyrics, opened from Now Playing. */
    data object Lyrics : AppScreen

    /** Equalizer — 5-band sliders + presets. */
    data object Equalizer : AppScreen

    /** Backup & restore — list saved JSON bundles, create/restore/delete. */
    data object BackupRestore : AppScreen

    /** Listening stats — total time, top tracks. */
    data object Stats : AppScreen

    /** Listening history — chronological play log. */
    data object History : AppScreen

    /** Crash log — local-only triage of recent ANRs / crashes. */
    data object CrashLog : AppScreen

    /** Syncthing setup wizard — walks the user through pairing the
     *  phone with a PC and registering the synced folder. */
    data object SyncthingWizard : AppScreen

    /** Playlist library — list of all playlists with create button. */
    data object Playlists : AppScreen

    /** Single playlist detail — track list with play/shuffle/remove. */
    data class PlaylistDetail(val playlistId: Long) : AppScreen

    /** Auto-populated Liked Songs view; not a regular playlist. */
    data object LikedSongs : AppScreen

    /**
     * In-app folder browser. Opened from Track Info → Open folder so
     * the user can navigate the parent directory without leaving
     * RadioShed (and without depending on a third-party file manager,
     * which is unreliable across OEMs — the AOSP DocumentsContract
     * intent the prior implementation used resolves to nothing on
     * Samsung's stock My Files app).
     */
    data class FolderBrowser(val initialPath: String) : AppScreen
}
