package com.visorcraft.radioshed.audio

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.visorcraft.radioshed.R
import com.visorcraft.radioshed.data.RatingRepository
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.stations.StationPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Foreground media-playback service. Hosts the actual [ExoPlayer] and a
 * [MediaLibraryService.MediaLibrarySession]; the in-app [AudioEngineAndroid]
 * talks to this service via a `MediaController`, so playback survives
 * configuration changes and the OS handles foreground promotion +
 * notification automatically.
 *
 * Because we extend `MediaLibraryService` (rather than the simpler
 * `MediaSessionService`), this service ALSO exposes a browse hierarchy
 * that Android Auto, Bluetooth media browsers, and Google Assistant can
 * query. The browse tree is:
 *
 *   root
 *    └─ "Stations"
 *        ├─ Latest Hits        (playable)
 *        ├─ All Music          (playable)
 *        ├─ Discover           (playable)
 *        └─ ...                (playable)
 *
 * When a client (Auto, in-app, etc.) requests playback of a station media
 * item, [RadioShedLibraryCallback.onSetMediaItems] intercepts and routes
 * the request through [StationPlayback], which builds the actual track
 * queue from the station's source rule and starts playback on this same
 * service's player. The browser then sees real track metadata in its UI.
 *
 * Audio attributes:
 *  - USAGE_MEDIA + CONTENT_TYPE_MUSIC so the system treats us as a music
 *    app (matters for ducking, BT routing, Do-Not-Disturb interaction).
 *  - handleAudioFocus = true so we honor incoming calls / alarms.
 *  - handleAudioBecomingNoisy = true so unplugging headphones pauses
 *    instead of blasting through the speaker.
 *
 * Manifest: this service is registered with intent-filter actions
 * `androidx.media3.session.MediaSessionService` (in-app controller) AND
 * `android.media.browse.MediaBrowserService` (Auto / system browsers),
 * with foreground-service type `mediaPlayback`, plus an
 * `automotive_app_desc.xml` meta-data declaration on the application.
 */
class RadioShedMediaSessionService : MediaLibraryService() {

    private var librarySession: MediaLibrarySession? = null

    /**
     * Service-scoped supervisor. Used by the library callback to bridge
     * between Media3's `ListenableFuture`-based API and our coroutine-based
     * repos. Cancelled in [onDestroy].
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Wrap ExoPlayer in StationAwarePlayer so external surfaces
        // (Android Auto, lock-screen, Bluetooth AVRCP) see skip-next /
        // skip-previous as always available, and pressing those buttons
        // routes through StationPlayback's queue logic instead of
        // ExoPlayer's default seek-within-current-queue behavior. See
        // [StationAwarePlayer] kdoc for the full rationale.
        val player = StationAwarePlayer(
            underlying = exoPlayer,
            onSkipNext = {
                runCatching {
                    GlobalContext.get().get<StationPlayback>().skipNext()
                }
            },
            onSkipPrevious = {
                // Route through StationPlayback for full Spotify-style
                // semantics: > 3s into the track restarts; otherwise pops
                // history. Previously this was a `seekTo(0)` shortcut
                // that broke "back to actually previous" on Auto / lock
                // screen / Bluetooth — they got a restart-only behaviour
                // that didn't match the in-app NowPlaying experience.
                runCatching {
                    GlobalContext.get().get<StationPlayback>().skipPrevious()
                }
            },
        )

        // Forward ExoPlayer's audio session id to the equalizer so it can
        // (re)attach. The session id is 0 until AudioSink binds on first
        // playback; subsequent track transitions can re-issue the event.
        // We listen on the underlying ExoPlayer (not the StationAwarePlayer
        // wrapper) so the wrapper's command-override logic stays separate
        // from effects routing.
        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                runCatching {
                    val ctrl = GlobalContext.get()
                        .get<com.visorcraft.radioshed.audio.EqualizerController>()
                    if (ctrl is com.visorcraft.radioshed.audio.AndroidEqualizerController) {
                        ctrl.onAudioSessionIdChanged(audioSessionId)
                    }
                }
            }

            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int,
            ) {
                // Push a fresh paint to any home-screen widget
                // instances so they catch up to the new track.
                // Cheap (no-op when zero widgets exist).
                runCatching {
                    com.visorcraft.radioshed.widget.NowPlayingWidget
                        .pushUpdate(this@RadioShedMediaSessionService)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Same reason as onMediaItemTransition — flip the
                // widget's play/pause glyph when state changes.
                runCatching {
                    com.visorcraft.radioshed.widget.NowPlayingWidget
                        .pushUpdate(this@RadioShedMediaSessionService)
                }
            }
        })

        librarySession = MediaLibrarySession.Builder(this, player, RadioShedLibraryCallback())
            .build()

        // Override the default media notification provider so the
        // status-bar small icon is the RadioShed antenna silhouette
        // rather than the system-default media-playback icon (which
        // is generic across all media apps and looks unprofessional).
        // Default channel id / name / button order are preserved.
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(NOTIFICATION_ID)
            .setChannelId(NOTIFICATION_CHANNEL_ID)
            .setChannelName(R.string.notification_channel_playback)
            .build()
            .apply {
                setSmallIcon(R.drawable.ic_radioshed_notification)
            }
        setMediaNotificationProvider(notificationProvider)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        librarySession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // Standard music-app behavior: if music is actively playing when the
        // user swipes the task from recents, leave the service running so
        // playback continues with the notification as the entry-point back
        // in. If playback is paused (or there's no player), tear down so we
        // don't leak a non-functional notification.
        val player = librarySession?.player
        if (player == null || !player.playWhenReady) {
            // Cancel the sleep timer ticker before stopping the player —
            // otherwise the fade ramp keeps calling into a controller
            // that's about to be released.
            runCatching {
                GlobalContext.get().get<com.visorcraft.radioshed.audio.SleepTimer>().cancel()
            }
            player?.stop()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Tear down the equalizer + sleep timer FIRST. The equalizer
        // holds a native AudioEffect reference scoped to ExoPlayer's
        // audio session; releasing the player without first releasing
        // the effect can leak the native handle. The sleep timer's
        // ticker calls into `audioEngine.setVolume`, which would throw
        // IllegalStateException on a released MediaController if we
        // didn't cancel it here.
        runCatching {
            val ctrl = GlobalContext.get().get<com.visorcraft.radioshed.audio.EqualizerController>()
            ctrl.release()
        }
        runCatching {
            GlobalContext.get().get<com.visorcraft.radioshed.audio.SleepTimer>().cancel()
        }
        librarySession?.run {
            player.release()
            release()
        }
        librarySession = null
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Browse-hierarchy + playback-intercept callback for the
     * MediaLibrarySession. Android Auto and other system media browsers
     * call into this to populate their UI and to request playback.
     */
    private inner class RadioShedLibraryCallback : MediaLibrarySession.Callback {

        // Multi-click state for Bluetooth headset button. Standard Media3
        // ships single-click (play/pause) and double-click (next), but
        // triple-click (previous) is non-standard and must be implemented
        // here. The window starts on the first KEY_DOWN; subsequent
        // KEY_DOWN events within the window increment the count and
        // reschedule the resolution. When the window elapses with no new
        // press, the accumulated count maps to an action.
        private var clickCount = 0
        private var clickRunnable: Runnable? = null
        private val clickHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private val clickWindowMs = 350L

        /**
         * Bluetooth headset multi-click. Routes the standard headset hook
         * codes (HEADSETHOOK / MEDIA_PLAY_PAUSE) through a click-count
         * debouncer so:
         *  - 1 press → play/pause toggle
         *  - 2 press → next
         *  - 3 press → previous
         *
         * Other media keys (NEXT / PREVIOUS / STOP / FAST_FORWARD / REWIND)
         * fall through to Media3's default handler so dedicated buttons on
         * multi-button remotes behave as the user expects.
         *
         * Returning true here tells Media3 we handled the event; returning
         * false lets the default path run.
         */
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                ?: return false
            if (key.action != KeyEvent.ACTION_DOWN || key.repeatCount != 0) return false
            return when (key.keyCode) {
                KeyEvent.KEYCODE_HEADSETHOOK,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    handleMultiClick()
                    true
                }
                else -> false
            }
        }

        private fun handleMultiClick() {
            clickCount += 1
            clickRunnable?.let { clickHandler.removeCallbacks(it) }
            val nextRunnable = Runnable {
                val resolved = clickCount
                clickCount = 0
                clickRunnable = null
                when (resolved) {
                    1 -> {
                        val player = librarySession?.player ?: return@Runnable
                        if (player.isPlaying) player.pause() else player.play()
                    }
                    2 -> runCatching {
                        GlobalContext.get().get<StationPlayback>().skipNext()
                    }
                    else -> runCatching {
                        // 3+ clicks: treat as previous. Power-users sometimes
                        // tap fast enough to trigger >3, but mapping them all
                        // to "previous" is more useful than dropping the
                        // event silently.
                        GlobalContext.get().get<StationPlayback>().skipPrevious()
                    }
                }
            }
            clickRunnable = nextRunnable
            clickHandler.postDelayed(nextRunnable, clickWindowMs)
        }

        /**
         * Explicitly grant SEEK_TO_NEXT/PREVIOUS to every connecting
         * controller. Why this is needed despite [StationAwarePlayer]
         * already overriding `getAvailableCommands`:
         *
         * `MediaSession` doesn't directly forward our player's commands
         * to controllers. Internally it routes through a `PlayerWrapper`
         * that re-derives commands from the player's actual queue state.
         * With single-item queues (our station model), it strips
         * SEEK_TO_NEXT_MEDIA_ITEM regardless of what our `Player.Commands`
         * override returns. Setting `availablePlayerCommands` here on the
         * `ConnectionResult` overrides PlayerWrapper's filtering and
         * exposes the commands to MediaController + Auto + Bluetooth +
         * lock-screen — wherever a controller is looking.
         *
         * The actual command execution still goes through
         * [StationAwarePlayer]'s overrides → [StationPlayback.skipNext].
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                .buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

            // Custom session commands: rating + dislike, surfaced in
            // Android Auto's Now Playing template via setCustomLayout.
            // Auto's media layout reserves a couple of slots beside the
            // transport controls for app-defined CommandButtons; we
            // populate them with a star toggle and a thumb-down so the
            // user can rate / dislike without taking eyes off the road.
            val starCommand = SessionCommand(ACTION_TOGGLE_STAR, Bundle.EMPTY)
            val dislikeCommand = SessionCommand(ACTION_DISLIKE_ON_STATION, Bundle.EMPTY)
            val heartCommand = SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY)

            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(starCommand)
                .add(dislikeCommand)
                .add(heartCommand)
                .build()

            val starButton = CommandButton.Builder()
                .setSessionCommand(starCommand)
                .setDisplayName("Star")
                .setIconResId(R.drawable.ic_radioshed_star)
                .build()
            val dislikeButton = CommandButton.Builder()
                .setSessionCommand(dislikeCommand)
                .setDisplayName("Dislike")
                .setIconResId(R.drawable.ic_radioshed_thumb_down)
                .build()
            val heartButton = CommandButton.Builder()
                .setSessionCommand(heartCommand)
                .setDisplayName("Like")
                .setIconResId(R.drawable.ic_radioshed_heart)
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(playerCommands)
                .setAvailableSessionCommands(sessionCommands)
                .setCustomLayout(ImmutableList.of(heartButton, starButton, dislikeButton))
                .build()
        }

        /**
         * Handle taps on our custom Auto buttons. The star button toggles
         * the current track between 5★ and 0★ — fast, one-finger
         * interaction suitable for driving. The thumb-down hits the
         * per-station dislike list (so the next time the queue picks for
         * this station, it skips this track) and immediately advances.
         */
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val player = session.player
            val trackId = player.currentMediaItem?.mediaId?.toLongOrNull()
                ?: return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE),
                )
            return when (customCommand.customAction) {
                ACTION_TOGGLE_STAR -> {
                    scope.launch {
                        runCatching {
                            val ratingRepo = GlobalContext.get().get<RatingRepository>()
                            val current = ratingRepo.getRating(trackId) ?: 0.0
                            val next = if (current >= 4.5) 0.0 else 5.0
                            ratingRepo.setRating(trackId, next)
                        }
                    }
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_DISLIKE_ON_STATION -> {
                    scope.launch {
                        runCatching {
                            val ratingRepo = GlobalContext.get().get<RatingRepository>()
                            val playback = GlobalContext.get().get<StationPlayback>()
                            val stationId = playback.currentStation.first()?.id
                            if (stationId != null) {
                                ratingRepo.dislikeForStation(trackId, stationId)
                            }
                            playback.skipNext()
                        }
                    }
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_TOGGLE_LIKE -> {
                    // Heart toggle from notification / Auto. Idempotent
                    // — re-tapping unlikes, matching the in-app heart
                    // button's behaviour. No skip; the user explicitly
                    // wants to keep listening.
                    scope.launch {
                        runCatching {
                            val ratingRepo = GlobalContext.get().get<RatingRepository>()
                            if (ratingRepo.isLiked(trackId)) {
                                ratingRepo.unlike(trackId)
                            } else {
                                ratingRepo.like(trackId)
                            }
                        }
                    }
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED),
                )
            }
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = browseableItem(
                mediaId = MEDIA_ID_ROOT,
                title = "RadioShed",
            )
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = when (parentId) {
            MEDIA_ID_ROOT -> {
                // Single category for now: "Stations". Library / Recently
                // Played / Search lands in a follow-up.
                val stations = browseableItem(
                    mediaId = MEDIA_ID_STATIONS,
                    title = "Stations",
                )
                Futures.immediateFuture(
                    LibraryResult.ofItemList(ImmutableList.of(stations), params),
                )
            }
            MEDIA_ID_STATIONS -> {
                // Snapshot the station list from the repo. observeAll() is a
                // Flow; .first() takes one snapshot which is enough — Auto
                // re-queries onGetChildren when the user re-enters the
                // category, so live-updating isn't required for v1.
                val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                scope.launch {
                    runCatching {
                        val repo = GlobalContext.get().get<StationRepository>()
                        val stations = repo.observeAll().first()
                        val items = stations.map { station ->
                            playableItem(
                                mediaId = MEDIA_ID_STATION_PREFIX + station.id,
                                title = station.name,
                                subtitle = station.customSubtitle,
                            )
                        }
                        future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                    }.onFailure {
                        future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                    }
                }
                future
            }
            else ->
                Futures.immediateFuture(
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE),
                )
        }

        /**
         * Intercept playback of station media items. When Android Auto (or
         * any other browser) sets a station as the player's media item,
         * we route the request through [StationPlayback] so the queue,
         * weighting, dislike-filtering, and SmartShuffle policies all
         * apply — instead of the player trying to literally play a URI of
         * `station_<id>` (which has no actual audio data behind it).
         *
         * Returning an empty list with `INDEX_UNSET` tells the player not
         * to alter its current item set; [StationPlayback] then calls
         * `audioEngine.play(...)` with a real track which arrives at the
         * player via the in-app `MediaController` and updates Auto's UI
         * with proper track metadata.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val firstStationId = mediaItems
                .firstOrNull { it.mediaId.startsWith(MEDIA_ID_STATION_PREFIX) }
                ?.mediaId
                ?.removePrefix(MEDIA_ID_STATION_PREFIX)
                ?.toLongOrNull()

            if (firstStationId != null) {
                scope.launch {
                    runCatching {
                        GlobalContext.get().get<StationPlayback>().playStation(firstStationId)
                    }
                }
                // Keep the player's queue untouched — StationPlayback will
                // populate it via the in-app controller momentarily.
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        ImmutableList.of(),
                        C.INDEX_UNSET,
                        C.TIME_UNSET,
                    ),
                )
            }

            // Default behavior for non-station items (e.g. ad-hoc playback
            // from the audio file VIEW intent): hand them straight to the
            // player.
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    ImmutableList.copyOf(mediaItems),
                    startIndex,
                    startPositionMs,
                ),
            )
        }
    }

    private fun browseableItem(mediaId: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()

    private fun playableItem(
        mediaId: String,
        title: String,
        subtitle: String?,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle ?: "")
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .build(),
        )
        .build()

    private companion object {
        const val MEDIA_ID_ROOT = "root"
        const val MEDIA_ID_STATIONS = "stations"
        const val MEDIA_ID_STATION_PREFIX = "station_"

        // Custom action ids used by the Android Auto layout. Values are
        // arbitrary but must round-trip exactly through SessionCommand.
        const val ACTION_TOGGLE_STAR = "com.visorcraft.radioshed.RATE_STAR"
        const val ACTION_DISLIKE_ON_STATION = "com.visorcraft.radioshed.DISLIKE"
        const val ACTION_TOGGLE_LIKE = "com.visorcraft.radioshed.LIKE"

        // Notification config for the foreground media-playback service.
        // Channel id is referenced from the manifest's auto-created
        // foreground-service channel; OS uses it for "Now playing"
        // notifications. Notification id is unique within our app.
        const val NOTIFICATION_ID = 0xCAFE  // 51966
        const val NOTIFICATION_CHANNEL_ID = "radioshed.playback"
    }
}
