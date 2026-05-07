package com.visorcraft.radioshed

import android.Manifest
import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.Playable
import com.visorcraft.radioshed.audio.toPlayable
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.SearchRepository
import com.visorcraft.radioshed.stations.StationPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val audioEngine: AudioEngine by inject()
    private val searchRepo: SearchRepository by inject()
    private val libraryRepo: LibraryRepository by inject()
    private val stationPlayback: StationPlayback by inject()

    // Search-intent dispatch runs DB queries; do them off the main
    // thread. Tied to a SupervisorJob so a single search failure
    // doesn't tear down sibling work.
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * On Android 13+ (API 33), the foreground media notification posted by
     * [androidx.media3.session.MediaSessionService] requires an explicit
     * `POST_NOTIFICATIONS` runtime grant. Without it, playback works but
     * the lock-screen / notification / Bluetooth-AVRCP transport surface
     * is invisible. Result ignored — if the user denies, music still plays
     * (just without the lock-screen UI), and Library Settings re-shows the
     * grant flow only for storage.
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Intentionally no-op: Media3 will skip posting the notification
            // gracefully if the permission isn't granted.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPostNotificationsIfNeeded()

        setContent {
            App()
        }

        // Cold-start path: the activity was launched via VIEW intent (e.g.
        // user tapped an .mp3 in a file manager) or via the Google Assistant
        // voice-search bridge. Handle here because setContent ⇢ Koin ⇢
        // AudioEngine is now ready.
        handleViewIntent(intent)
        handleSearchIntent(intent)
    }

    /**
     * Warm path: RadioShed was already running and the user tapped another
     * audio file. With launchMode=singleTask the existing instance is reused
     * and onNewIntent fires instead of onCreate. Without this, subsequent
     * "Open with RadioShed" taps would no-op.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
        handleSearchIntent(intent)
    }

    /**
     * If the activity was launched/resumed via [Intent.ACTION_VIEW] with an
     * audio URI (file:// or content://), play it ad-hoc. The track isn't
     * added to the library — that's a Library Settings → scan flow. This is
     * just "tap a file → it plays in RadioShed."
     *
     * For content:// URIs we need temporary read access; the system grants
     * it for the lifetime of the activity automatically because of the
     * `<intent-filter>` declaration. Media3's underlying ExoPlayer reads via
     * ContentResolver, which respects that grant.
     */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri: Uri = intent.data ?: return

        // Best-effort title from the URI's last path segment, dropping the
        // file extension. Real metadata would require a sync read of the
        // file's tags — skip for ad-hoc playback to keep tap-to-play instant.
        val title = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "Unknown"

        audioEngine.play(
            Playable(
                trackId = AD_HOC_TRACK_ID,
                uri = uri.toString(),
                title = title,
                artist = null,
                album = null,
                durationMs = 0L,
            ),
        )
    }

    /**
     * Google Assistant / Voice Actions entry point. When the user says
     * "Hey Google, play X on RadioShed" the system fires
     * [Intent.ACTION_MEDIA_PLAY_FROM_SEARCH] at this activity with the
     * spoken query in [SearchManager.QUERY] plus a structured extras
     * Bundle (artist / album / title / focus, per the long-standing
     * MediaStore contract).
     *
     * We mirror the same dispatch logic as the in-session voice-search
     * branch in RadioShedMediaSessionService.onSetMediaItems: prefer
     * structured extras over the noisy raw transcript, fall back to a
     * stripped free-text search if no structured fields are set.
     */
    private fun handleSearchIntent(intent: Intent?) {
        // The action constant lives on android.provider.MediaStore as
        // INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH; we hardcode the string
        // so this file stays decoupled from the MediaStore API surface
        // (the contract is the string value, not the constant).
        if (intent?.action != "android.media.action.MEDIA_PLAY_FROM_SEARCH") return

        val rawQuery = intent.getStringExtra(SearchManager.QUERY).orEmpty()
        val focus = intent.getStringExtra("android.intent.extra.focus")
        val artistExtra = intent.getStringExtra("android.intent.extra.artist")
        val albumExtra = intent.getStringExtra("android.intent.extra.album")
        val titleExtra = intent.getStringExtra("android.intent.extra.title")
        val genreExtra = intent.getStringExtra("android.intent.extra.genre")
        Log.i(
            TAG,
            "MEDIA_PLAY_FROM_SEARCH: query='$rawQuery' focus='$focus' " +
                "artist='$artistExtra' album='$albumExtra' " +
                "title='$titleExtra' genre='$genreExtra'",
        )

        searchScope.launch {
            runCatching {
                val handled = when {
                    focus == FOCUS_ARTIST && !artistExtra.isNullOrBlank() ->
                        playArtistByName(artistExtra)
                    focus == FOCUS_ALBUM && !albumExtra.isNullOrBlank() ->
                        playAlbumByTitle(albumExtra)
                    focus == FOCUS_AUDIO && !titleExtra.isNullOrBlank() ->
                        playTrackByTitle(titleExtra)
                    focus == FOCUS_GENRE && !genreExtra.isNullOrBlank() ->
                        playGenre(genreExtra)
                    !artistExtra.isNullOrBlank() -> playArtistByName(artistExtra)
                    !albumExtra.isNullOrBlank() -> playAlbumByTitle(albumExtra)
                    !titleExtra.isNullOrBlank() -> playTrackByTitle(titleExtra)
                    rawQuery.isNotBlank() -> playFreeText(rawQuery)
                    else -> false
                }
                if (!handled) {
                    Log.i(TAG, "voice search: no match for query='$rawQuery'")
                }
            }.onFailure {
                Log.e(TAG, "voice search dispatch failed", it)
            }
        }
    }

    private suspend fun playArtistByName(name: String): Boolean {
        val artist = searchRepo.searchArtists(name, limit = 5).firstOrNull()
            ?: return false
        val tracks = libraryRepo.tracksForArtist(artist.id)
        if (tracks.isEmpty()) return false
        stationPlayback.playTracks(tracks, startIndex = 0)
        return true
    }

    private suspend fun playAlbumByTitle(name: String): Boolean {
        val album = searchRepo.searchAlbums(name, limit = 5).firstOrNull()
            ?: return false
        val tracks = libraryRepo.tracksForAlbum(album.id)
        if (tracks.isEmpty()) return false
        stationPlayback.playTracks(tracks, startIndex = 0)
        return true
    }

    private suspend fun playTrackByTitle(name: String): Boolean {
        val tracks = searchRepo.searchTracks(name, limit = 5)
        if (tracks.isEmpty()) return false
        stationPlayback.playTracks(tracks, startIndex = 0)
        return true
    }

    private suspend fun playGenre(genre: String): Boolean {
        val tracks = searchRepo.searchTracks("genre:$genre", limit = 50)
        if (tracks.isEmpty()) return false
        stationPlayback.playTracks(tracks, startIndex = 0)
        return true
    }

    private suspend fun playFreeText(rawQuery: String): Boolean {
        // Strip leading verb/article tokens that Assistant transcribes
        // verbatim — "play a song by Black Pink" carries forward in
        // SearchManager.QUERY as the literal phrase, and the noisy
        // tokens otherwise pollute LIKE matching.
        val cleaned = listOf(
            "play", "listen to",
            "a song by", "song by", "songs by", "music by",
            "the song", "a song", "song",
        ).fold(rawQuery) { acc, token -> acc.removePrefixToken(token) }
            .trim()
            .ifBlank { rawQuery }

        val results = searchRepo.searchAll(cleaned, limitPerCategory = 20L)
        // Artist match first: free-text "Black Pink" without a focus
        // hint should still play the artist's tracks rather than a
        // noisy title-LIKE match against unrelated songs.
        if (results.artists.isNotEmpty()) {
            val tracks = libraryRepo.tracksForArtist(results.artists.first().id)
            if (tracks.isNotEmpty()) {
                stationPlayback.playTracks(tracks, startIndex = 0)
                return true
            }
        }
        if (results.tracks.isNotEmpty()) {
            stationPlayback.playTracks(results.tracks, startIndex = 0)
            return true
        }
        if (results.albums.isNotEmpty()) {
            val tracks = libraryRepo.tracksForAlbum(results.albums.first().id)
            if (tracks.isNotEmpty()) {
                stationPlayback.playTracks(tracks, startIndex = 0)
                return true
            }
        }
        return false
    }

    private fun String.removePrefixToken(token: String): String {
        val trimmed = this.trimStart()
        if (!trimmed.startsWith(token, ignoreCase = true)) return this
        val rest = trimmed.substring(token.length)
        if (rest.isNotEmpty() && !rest[0].isWhitespace()) return this
        return rest.trimStart()
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val alreadyGranted = ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
        if (!alreadyGranted) {
            notificationPermissionLauncher.launch(permission)
        }
    }

    private companion object {
        /**
         * Sentinel track id for ad-hoc playback (a file opened via
         * Intent.ACTION_VIEW that isn't in the indexed library). Negative so
         * it can never collide with a real `track.id` (auto-increment > 0).
         * Repos that take a track id (Rating, Skip, etc.) should treat this
         * as a no-op or "not in library."
         */
        const val AD_HOC_TRACK_ID = -1L

        const val TAG = "RadioShedMS"

        // EXTRA_MEDIA_FOCUS values per the legacy MediaStore contract.
        // Hardcoded as strings (instead of MediaStore.EXTRA_*) to keep
        // this file independent of the MediaStore API surface.
        const val FOCUS_ARTIST = "vnd.android.cursor.item/artist"
        const val FOCUS_ALBUM = "vnd.android.cursor.item/album"
        const val FOCUS_AUDIO = "vnd.android.cursor.item/audio"
        const val FOCUS_GENRE = "vnd.android.cursor.item/genre"
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
