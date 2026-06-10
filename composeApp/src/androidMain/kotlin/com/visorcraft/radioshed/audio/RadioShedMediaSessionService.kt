package com.visorcraft.radioshed.audio

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
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
import kotlinx.coroutines.withContext
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
     * Dual-ExoPlayer pair powering true crossfade + gapless preload
     * (PLAN 3.2). The session player always wraps [CrossfadePlayerPair.active]
     * in a [StationAwarePlayer]; on a crossfade swap we re-wrap the new active
     * player and call [MediaLibrarySession.setPlayer] so the notification /
     * Auto / lock-screen follow the swap and show the incoming track.
     */
    private var playerPair: CrossfadePlayerPair? = null

    /**
     * Service-scoped supervisor. Used by the library callback to bridge
     * between Media3's `ListenableFuture`-based API and our coroutine-based
     * repos. Cancelled in [onDestroy]. crashGuard prevents an AA voice-
     * search failure or callback exception from killing the service
     * via Android's uncaught-exception handler — without it, the
     * head unit loses media-session connectivity entirely.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main +
            com.visorcraft.radioshed.util.crashGuard("MediaSessionService"),
    )

    /**
     * The library/session callback. Held as a field (rather than
     * inlined into the session builder) so [onDestroy] can call into
     * it for cleanup — specifically to flush the Bluetooth multi-click
     * handler so a posted Runnable doesn't fire after the service is
     * gone and crash with "Can't deliver result for stale callback".
     */
    private val libraryCallback = RadioShedLibraryCallback()

    override fun onCreate() {
        Log.i(TAG, "onCreate: starting service pid=${android.os.Process.myPid()}")
        super.onCreate()
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        fun buildExoPlayer(): ExoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { attachEffectsAndWidgetListeners(it) }

        // Two players: one audible (active), one standby for the crossfade /
        // gapless preload. The pair re-points the MediaSession at whichever is
        // active when a crossfade swap completes.
        val pair = CrossfadePlayerPair(
            primary = buildExoPlayer(),
            secondary = buildExoPlayer(),
            onActivePlayerChanged = { newActive -> swapSessionPlayer(newActive) },
        )
        playerPair = pair

        // Wrap the active ExoPlayer in StationAwarePlayer so external surfaces
        // (Android Auto, lock-screen, Bluetooth AVRCP) see skip-next /
        // skip-previous as always available, and pressing those buttons
        // routes through StationPlayback's queue logic instead of
        // ExoPlayer's default seek-within-current-queue behavior. See
        // [StationAwarePlayer] kdoc for the full rationale.
        val player = wrapAsStationPlayer(pair.active)

        Log.i(TAG, "onCreate: building MediaLibrarySession")
        librarySession = MediaLibrarySession.Builder(this, player, libraryCallback)
            .build()
        Log.i(TAG, "onCreate: MediaLibrarySession built; token=${librarySession?.token}")
        // Hand the live session back to the callback so it can update
        // the custom layout (heart state, repeat/shuffle glyph) when
        // the user taps an action or playback state changes. Using a
        // field on the callback rather than re-deriving from
        // onGetSession lets the callback push setCustomLayout
        // unconditionally (Media3 dedupes redundant updates internally).
        libraryCallback.attach(librarySession)
        // Mirror REPEAT_MODE / shuffleModeEnabled changes back into the
        // custom layout so the notification glyph reflects whatever
        // toggled the state — including in-app NowPlaying buttons,
        // not just the notification's own action taps.
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

    /**
     * Attach the equalizer-routing + widget-update listeners to an ExoPlayer.
     * Applied to BOTH players in the pair (in [CrossfadePlayerPair]'s
     * constructor args via [buildExoPlayer]) so effects re-attach and widgets
     * repaint regardless of which player is currently active after a crossfade
     * swap. The session id differs per player, so the equalizer reattaches on
     * the swap's onAudioSessionIdChanged for the newly-active one.
     */
    private fun attachEffectsAndWidgetListeners(exoPlayer: ExoPlayer) {
        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                // Only the active player should own the global AudioEffect
                // chain; a standby player's session id (it preloads silently)
                // must not steal the equalizer away from what's audible.
                if (exoPlayer !== playerPair?.active) return
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
                if (exoPlayer !== playerPair?.active) return
                // Push a fresh paint to any home-screen widget
                // instances so they catch up to the new track.
                // Cheap (no-op when zero widgets exist).
                runCatching {
                    com.visorcraft.radioshed.widget.NowPlayingWidget
                        .pushUpdate(this@RadioShedMediaSessionService)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (exoPlayer !== playerPair?.active) return
                // Same reason as onMediaItemTransition — flip the
                // widget's play/pause glyph when state changes.
                runCatching {
                    com.visorcraft.radioshed.widget.NowPlayingWidget
                        .pushUpdate(this@RadioShedMediaSessionService)
                }
            }
        })
    }

    /**
     * Wrap [exoPlayer] in a [StationAwarePlayer] and attach the custom-layout
     * refresh listeners (repeat / shuffle / track change). Used both at
     * construction and on every crossfade swap so the new session player keeps
     * routing skip commands through [StationPlayback] and keeps the Auto /
     * notification glyphs in sync.
     */
    private fun wrapAsStationPlayer(exoPlayer: ExoPlayer): StationAwarePlayer {
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
        player.addListener(object : Player.Listener {
            override fun onRepeatModeChanged(repeatMode: Int) {
                libraryCallback.refreshCustomLayout()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                libraryCallback.refreshCustomLayout()
            }

            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int,
            ) {
                // New track → re-fetch its like state so the heart
                // glyph isn't stuck on the previous track's value.
                libraryCallback.refreshCustomLayout()
            }
        })
        return player
    }

    /**
     * Re-point the MediaSession at [newActive] after a crossfade swap. The new
     * active ExoPlayer already holds the incoming track, so the notification /
     * Auto / lock-screen metadata flips to it here. The previous wrapper is
     * NOT released — its underlying ExoPlayer lives on as the pair's standby
     * (reset to silence by [CrossfadePlayerPair]); only [onDestroy] releases
     * the real players.
     */
    private fun swapSessionPlayer(newActive: ExoPlayer) {
        val session = librarySession ?: return
        val wrapper = wrapAsStationPlayer(newActive)
        runCatching { session.setPlayer(wrapper) }
            .onFailure { Log.e(TAG, "swapSessionPlayer failed", it) }
        libraryCallback.refreshCustomLayout()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        val pkg = controllerInfo.packageName
        val session = librarySession
        Log.i(TAG, "onGetSession: caller=$pkg session=${if (session == null) "null" else "ok"}")
        return session
    }

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
        Log.i(TAG, "onDestroy: tearing down service")
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
        // Flush the Bluetooth multi-click debounce handler. Without
        // this, a Runnable posted shortly before service teardown
        // could fire after `librarySession` is released and crash on
        // a NPE from `librarySession?.player`. The callback also
        // detaches the session reference so any in-flight refresh
        // tasks no-op rather than touch released resources.
        libraryCallback.detach()
        // Release the session first (detaches it from the player), then release
        // BOTH ExoPlayers via the pair. We don't call session.player.release()
        // separately — that wrapper's underlying ExoPlayer is one of the two
        // the pair owns, so the pair's release() covers it without a
        // double-release on the active player.
        librarySession?.release()
        playerPair?.release()
        playerPair = null
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

        // Live like-state for the currently-playing track. Drives the
        // notification heart glyph (filled vs outline). Updated by
        // [refreshCustomLayout] after track transitions and after
        // taps on the heart action. Null = unknown / no track loaded.
        @Volatile
        private var currentTrackLiked: Boolean = false

        // Live star rating for the currently-playing track (0..5,
        // rounded from the underlying Double). Drives the AA Star
        // button's displayName so taps cycle visibly through the
        // rating range instead of silently flipping 0↔5.
        @Volatile
        private var currentTrackRating: Int = 0

        // Session reference for setCustomLayout updates. Held weakly
        // through attach/detach so [onDestroy] can null this out
        // before releasing the actual session, preventing late-arriving
        // refresh tasks from touching a released session.
        @Volatile
        private var attachedSession: MediaLibrarySession? = null

        // Cache of search results between [onSearch] (the count-only
        // notification) and the immediately-following
        // [onGetSearchResult] (the actual items pull). Without this
        // cache the two callbacks issue independent searchAll queries
        // against potentially different DB snapshots — a library scan
        // running between the two calls would skew the count vs items.
        //
        // Bounded LinkedHashMap so a controller that fires onSearch
        // without ever calling onGetSearchResult (some Bluetooth
        // browsers do this opportunistically) can't pile up a
        // memory leak: oldest entry evicts when we exceed the cap.
        // Synchronized because the two callbacks run on different
        // dispatcher continuations.
        private val pendingSearchResults: MutableMap<String, List<MediaItem>> =
            java.util.Collections.synchronizedMap(
                object : LinkedHashMap<String, List<MediaItem>>(8, 0.75f, true) {
                    override fun removeEldestEntry(
                        eldest: MutableMap.MutableEntry<String, List<MediaItem>>,
                    ): Boolean = size > MAX_PENDING_SEARCH_RESULTS
                },
            )

        fun attach(session: MediaLibrarySession?) {
            attachedSession = session
            // Intentionally NOT calling refreshCustomLayout() here.
            //
            // Doing so during the service's onCreate (immediately
            // after MediaLibrarySession.Builder().build() returns)
            // pushes a setCustomLayout to the session before any
            // controller has connected, and on Android Auto in
            // particular that left the MediaBrowser bridge in a state
            // where the subsequent MediaBrowserCompat connect from
            // AA's GH.MediaBCConnection failed with onConnectFailed
            // → AA's "RadioShed doesn't seem to be working right now."
            //
            // The initial layout each controller sees is already
            // wired through onConnect's
            // `AcceptedResultBuilder.setCustomLayout(buildCustomLayout())`,
            // so Auto / lock-screen / in-app each get the right
            // layout on connect. Subsequent refreshes (track change,
            // tap toggles) come from onMediaItemTransition /
            // onCustomCommand handlers below — those run after a
            // controller is already in place, which is safe.
        }

        fun detach() {
            attachedSession = null
            clickRunnable?.let { clickHandler.removeCallbacks(it) }
            clickHandler.removeCallbacksAndMessages(null)
            clickRunnable = null
            clickCount = 0
        }

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
            Log.i(
                TAG,
                "onConnect: caller=${controller.packageName} uid=${controller.uid} " +
                    "controllerType=${controller.controllerVersion}",
            )
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                .buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

            // Custom session commands: rating + dislike + like + repeat
            // + shuffle, surfaced in Android Auto's Now Playing template
            // (and the lock-screen / notification expanded layout) via
            // setCustomLayout. Auto's media layout reserves slots beside
            // the transport controls for app-defined CommandButtons.
            //
            // CRUCIAL: this is a MediaLibrarySession, so the base must
            // be DEFAULT_SESSION_AND_LIBRARY_COMMANDS — DEFAULT_SESSION_COMMANDS
            // alone strips the library-access commands like
            // COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT, which the legacy
            // MediaBrowserService bridge to Android Auto checks BEFORE
            // delegating to our onGetLibraryRoot. Stripping that command
            // is what produced AA's "RadioShed doesn't seem to be
            // working right now" — the framework returned "No root for
            // client com.google.android.projection.gearhead" without
            // ever calling our callback.
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(ACTION_TOGGLE_STAR, Bundle.EMPTY))
                .add(SessionCommand(ACTION_DISLIKE_ON_STATION, Bundle.EMPTY))
                .add(SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_CYCLE_REPEAT, Bundle.EMPTY))
                .add(SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY))
                // Crossfade / gapless control channel (PLAN 3.2). The in-app
                // AudioEngineAndroid proxy forwards setUpNext / crossfadeTo down
                // to this service via these custom commands, since the real
                // ExoPlayer pair lives here, not in the proxy.
                .add(SessionCommand(CrossfadeIpc.ACTION_SET_UP_NEXT, Bundle.EMPTY))
                .add(SessionCommand(CrossfadeIpc.ACTION_CROSSFADE_TO, Bundle.EMPTY))
                .add(SessionCommand(CrossfadeIpc.ACTION_CANCEL_CROSSFADE, Bundle.EMPTY))
                .build()

            val layout = runCatching { buildCustomLayout() }
                .onFailure { Log.e(TAG, "onConnect: buildCustomLayout failed", it) }
                .getOrDefault(ImmutableList.of())
            Log.i(TAG, "onConnect: accepting with ${layout.size} custom layout buttons")
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(playerCommands)
                .setAvailableSessionCommands(sessionCommands)
                .setCustomLayout(layout)
                .build()
        }

        /**
         * Build the ordered list of [CommandButton]s shown in the
         * notification / Auto custom layout. Glyphs are state-driven:
         *
         *  - Heart: filled when [currentTrackLiked], outline otherwise
         *  - Repeat: off / all / one based on `Player.repeatMode`
         *  - Shuffle: bright / dim based on `Player.shuffleModeEnabled`
         *
         * Re-emit via [refreshCustomLayout] whenever any of these
         * inputs change so the user sees the result of their tap
         * without having to dismiss & reopen the notification.
         */
        private fun buildCustomLayout(): ImmutableList<CommandButton> {
            val heartIcon = if (currentTrackLiked) R.drawable.ic_radioshed_heart
                            else R.drawable.ic_radioshed_heart_outline
            // Conservative 3-button layout — Android Auto's NowPlaying
            // template renders 2-3 secondary actions reliably; pushing
            // 5 (heart + shuffle + repeat + star + dislike) was
            // correlated with the AA regression where the head unit
            // showed "RadioShed doesn't seem to be working right now"
            // instead of the player surface. Repeat + shuffle remain
            // accessible on AA via the framework's own
            // Player.COMMAND_SET_REPEAT_MODE / SHUFFLE_MODE rendering,
            // so dropping them from the custom layout doesn't lose
            // functionality on the car screen — only the explicit
            // glyph slots we owned in the notification.

            val heartButton = CommandButton.Builder()
                .setSessionCommand(SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY))
                .setDisplayName(if (currentTrackLiked) "Unlike" else "Like")
                .setIconResId(heartIcon)
                .build()
            // Filled-vs-outline star is the cheap way to give visible
            // feedback for the cycling rating without shipping six
            // per-rating drawables. The displayName carries the
            // precise "★ N/5" for TalkBack and the few hosts that do
            // surface labels, but most AA NowPlaying renders only the
            // icon — so the binary filled/outline state is what
            // actually communicates "your tap registered".
            val starIcon = if (currentTrackRating > 0) {
                R.drawable.ic_radioshed_star
            } else {
                R.drawable.ic_radioshed_star_outline
            }
            val starLabel = if (currentTrackRating <= 0) {
                "Rate"
            } else {
                "★ $currentTrackRating/5"
            }
            val starButton = CommandButton.Builder()
                .setSessionCommand(SessionCommand(ACTION_TOGGLE_STAR, Bundle.EMPTY))
                .setDisplayName(starLabel)
                .setIconResId(starIcon)
                .build()
            val dislikeButton = CommandButton.Builder()
                .setSessionCommand(SessionCommand(ACTION_DISLIKE_ON_STATION, Bundle.EMPTY))
                .setDisplayName("Dislike")
                .setIconResId(R.drawable.ic_radioshed_thumb_down)
                .build()

            // Order matters: Auto's Now Playing template caps the
            // visible custom buttons at 2-3 depending on the head
            // unit. Putting the high-utility binary actions (Like,
            // Dislike) first guarantees they survive the cap; the
            // multi-step Star rating is third because it's the least
            // important driving-context action and the easiest to
            // sacrifice when a head unit only renders two slots.
            return ImmutableList.of(
                heartButton,
                dislikeButton,
                starButton,
            )
        }

        /**
         * Refresh the custom layout in-place. Called from:
         *  - track transitions (re-fetches like state for the new track)
         *  - tap handlers (after toggling like, repeat, or shuffle)
         *  - player listeners (mirroring in-app NowPlaying changes)
         *
         * The like-state lookup is async (DB hit), so the refresh
         * happens in two phases — synchronous re-emit with the cached
         * value for snappy mode-toggle feedback, then a follow-up
         * re-emit once the DB returns. setCustomLayout is idempotent
         * when called with equivalent buttons, so the duplicate emit
         * is cheap.
         */
        fun refreshCustomLayout() {
            // Defensive runCatching: a throw here from buildCustomLayout
            // (missing drawable, NPE on attachedSession.player during a
            // tear-down race) was previously bubbling up and killing
            // the MediaLibrarySession before AA could finish its
            // handshake — which is what produced AA's generic
            // "RadioShed doesn't seem to be working right now" instead
            // of a real player surface. Swallowing the throw keeps
            // the session alive; the worst case is the layout doesn't
            // refresh, which is far less bad than the service dying.
            // Logged at error rather than silently swallowed so we
            // can see the actual cause if it ever fires again.
            runCatching {
                val session = attachedSession ?: return@runCatching
                session.setCustomLayout(buildCustomLayout())
                val trackId = session.player.currentMediaItem?.mediaId?.toLongOrNull()
                if (trackId == null || trackId < 0L) {
                    if (currentTrackLiked || currentTrackRating != 0) {
                        currentTrackLiked = false
                        currentTrackRating = 0
                        session.setCustomLayout(buildCustomLayout())
                    }
                    return@runCatching
                }
                scope.launch {
                    runCatching {
                        val ratingRepo = GlobalContext.get().get<RatingRepository>()
                        val liked = ratingRepo.isLiked(trackId)
                        val rating = (ratingRepo.getRating(trackId) ?: 0.0)
                        val ratingInt = kotlin.math.round(rating).toInt().coerceIn(0, 5)
                        val likedChanged = liked != currentTrackLiked
                        val ratingChanged = ratingInt != currentTrackRating
                        if (likedChanged || ratingChanged) {
                            currentTrackLiked = liked
                            currentTrackRating = ratingInt
                            attachedSession?.setCustomLayout(buildCustomLayout())
                        }
                    }.onFailure {
                        Log.e(TAG, "refreshCustomLayout: track-state lookup failed", it)
                    }
                }
            }.onFailure {
                Log.e(TAG, "refreshCustomLayout: failed", it)
            }
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
            // Trace every receipt so future "the AA button did nothing"
            // reports can be diagnosed from logcat without instrumenting
            // each handler individually. caller=packageName disambiguates
            // taps from Auto vs the in-app controller vs Bluetooth AVRCP.
            Log.i(
                TAG,
                "onCustomCommand: action=${customCommand.customAction} " +
                    "caller=${controller.packageName}",
            )
            val player = session.player
            // Repeat/shuffle don't need a track id — they apply to the
            // player as a whole. Handle them before the trackId guard
            // so they still work between songs.
            when (customCommand.customAction) {
                ACTION_CYCLE_REPEAT -> {
                    val nextMode = when (player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    player.repeatMode = nextMode
                    // The Player.Listener registered in onCreate fires
                    // refreshCustomLayout, but pushing one synchronously
                    // here gives the user instant glyph feedback even
                    // if the listener is throttled / queued.
                    refreshCustomLayout()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_TOGGLE_SHUFFLE -> {
                    player.shuffleModeEnabled = !player.shuffleModeEnabled
                    refreshCustomLayout()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CrossfadeIpc.ACTION_SET_UP_NEXT -> {
                    // When crossfade is active, preload the next item onto the
                    // standby player so the upcoming crossfade starts instantly
                    // (buffered, not cold). When crossfade is Off the standby
                    // preload still warms the decoder for a snappier next-track
                    // load; sample-accurate single-player gapless is a deeper
                    // re-architecture tracked separately (SPEC Phase 7).
                    val item = mediaItemFromArgs(args)
                    val pair = playerPair
                    if (item != null && pair != null) {
                        runCatching { pair.preloadUpNext(item) }
                            .onFailure { Log.e(TAG, "preloadUpNext failed", it) }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CrossfadeIpc.ACTION_CANCEL_CROSSFADE -> {
                    // A skip / stop arrived: interrupt any in-flight ramp so it
                    // jump-cuts to a sane end state and the proxy's follow-up
                    // play() doesn't fight a still-running fade.
                    runCatching { playerPair?.cancelActiveCrossfade() }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CrossfadeIpc.ACTION_CROSSFADE_TO -> {
                    val item = mediaItemFromArgs(args)
                    val durationMs = args.getLong(CrossfadeIpc.EXTRA_CROSSFADE_DURATION_MS, 0L)
                    val curveName = args.getString(CrossfadeIpc.EXTRA_CROSSFADE_CURVE)
                    // Effective volume the incoming track should land at (user ×
                    // ReplayGain, computed by StationPlayback). Default 1f when
                    // absent so a legacy / unattenuated caller still plays.
                    val targetVolume = args.getFloat(
                        CrossfadeIpc.EXTRA_CROSSFADE_TARGET_VOLUME,
                        1f,
                    )
                    val pair = playerPair
                    if (item != null && pair != null && durationMs > 0L) {
                        val curve = runCatching {
                            com.visorcraft.radioshed.domain.CrossfadeCurve.valueOf(
                                curveName ?: "EqualPower",
                            )
                        }.getOrDefault(com.visorcraft.radioshed.domain.CrossfadeCurve.EqualPower)
                        // Launch on the service scope (Main) — ExoPlayer needs
                        // its single app thread, and crashGuard keeps a ramp
                        // failure from killing the session.
                        scope.launch {
                            runCatching { pair.crossfadeTo(item, durationMs, curve, targetVolume) }
                                .onFailure { Log.e(TAG, "crossfadeTo failed", it) }
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
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
                            // Cycle through 0 → 1 → 2 → 3 → 4 → 5 →
                            // 0 so each tap moves one star and the
                            // user can dial in any rating from AA
                            // without expanding to the phone app. The
                            // half-star precision the in-app rating
                            // row exposes is intentionally not
                            // surfaced here — fewer states to spin
                            // through while driving.
                            val rounded = kotlin.math.round(current).toInt().coerceIn(0, 5)
                            val next = if (rounded >= 5) 0 else rounded + 1
                            ratingRepo.setRating(trackId, next.toDouble())
                            currentTrackRating = next
                            attachedSession?.setCustomLayout(buildCustomLayout())
                            // Audible confirmation. The icon swap
                            // (filled ↔ outline) covers the binary
                            // state but doesn't tell the driver which
                            // tap of the cycle they're on; a brief
                            // toast does. Auto routes Toasts from the
                            // playing media app to a head-unit-side
                            // notification overlay, which is exactly
                            // the at-a-glance feedback the driver
                            // needs without taking eyes off the road.
                            // Posted on the main thread because Toast
                            // requires a Looper.
                            val msg = if (next == 0) "Rating cleared" else "Rated $next/5"
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
                            }
                        }.onFailure {
                            Log.e(TAG, "ACTION_TOGGLE_STAR: rating update failed", it)
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
                    //
                    // Optimistic toggle: flip the cached state and the
                    // visual glyph synchronously, then persist async.
                    // Reading isLiked() from the DB on every tap meant
                    // a rapid double-tap would read the same stale
                    // value twice and write the same `nextLiked` for
                    // both — net result no toggle, contrary to user
                    // intent. Sourcing the next state from the cache
                    // makes consecutive taps alternate even when the
                    // DB writes are still in flight.
                    val nextLiked = !currentTrackLiked
                    currentTrackLiked = nextLiked
                    attachedSession?.setCustomLayout(buildCustomLayout())
                    scope.launch {
                        runCatching {
                            val ratingRepo = GlobalContext.get().get<RatingRepository>()
                            if (nextLiked) ratingRepo.like(trackId)
                            else ratingRepo.unlike(trackId)
                        }.onFailure {
                            // DB write failed — roll the cache back so
                            // the visual state matches the persisted
                            // truth on the next refresh.
                            Log.e(TAG, "ACTION_TOGGLE_LIKE: DB write failed", it)
                            currentTrackLiked = !nextLiked
                            attachedSession?.setCustomLayout(buildCustomLayout())
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
            Log.i(
                TAG,
                "onGetLibraryRoot: caller=${browser.packageName} " +
                    "isRecent=${params?.isRecent} isOffline=${params?.isOffline} " +
                    "isSuggested=${params?.isSuggested}",
            )
            return runCatching {
                val root = browseableItem(
                    mediaId = MEDIA_ID_ROOT,
                    title = "RadioShed",
                )
                // Declare to Auto / Google Assistant that voice search
                // works against this library. Without
                // SEARCH_SUPPORTED=true Assistant decides up-front
                // that we don't handle voice queries and surfaces
                // "can't play music on Radio Shed because it's not a
                // supported platform" — without ever calling
                // onSetMediaItems with the spoken query. The other
                // flags advertise support for the related
                // browse-/search-style features so AA picks the right
                // surface chrome.
                val rootExtras = android.os.Bundle().apply {
                    putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                    putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
                }
                val outParams = MediaLibraryService.LibraryParams.Builder()
                    .setExtras(rootExtras)
                    .setRecent(params?.isRecent == true)
                    .setOffline(params?.isOffline == true)
                    .setSuggested(params?.isSuggested == true)
                    .build()
                Futures.immediateFuture(LibraryResult.ofItem(root, outParams))
            }.getOrElse {
                Log.e(TAG, "onGetLibraryRoot: failed", it)
                Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
            }
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            Log.i(
                TAG,
                "onGetChildren: caller=${browser.packageName} parentId=$parentId " +
                    "page=$page pageSize=$pageSize",
            )
            return onGetChildrenInner(session, browser, parentId, page, pageSize, params)
        }

        private fun onGetChildrenInner(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = when (parentId) {
            MEDIA_ID_ROOT -> {
                // Three top-level browse buckets, mirroring the
                // phone app's mental model as closely as a flat
                // browse tree allows: Stations (the curated mixes
                // the app is built around), Library (the raw
                // collection — songs / albums / artists), and
                // Recently Played (the just-listened list pulled
                // from station_history).
                //
                // Search is intentionally NOT a separate browse
                // category — Android Auto routes voice queries
                // through Google Assistant which fires
                // onSearch(query) on the MediaLibrarySession.
                // Surfacing a redundant "Search" tile would clutter
                // the surface for no functional gain. Settings
                // also doesn't appear: head-unit users configure
                // RadioShed on the phone, not behind the wheel.
                val stations = browseableItem(MEDIA_ID_STATIONS, "Stations")
                val library = browseableItem(MEDIA_ID_LIBRARY, "Library")
                val recents = browseableItem(MEDIA_ID_RECENTLY_PLAYED, "Recently Played")
                Futures.immediateFuture(
                    LibraryResult.ofItemList(
                        ImmutableList.of(stations, library, recents),
                        params,
                    ),
                )
            }
            MEDIA_ID_LIBRARY -> {
                // Library subcategories. Each is a browseable folder
                // the user drills into. Songs lists tracks
                // alphabetically; Albums and Artists list collections
                // that, when tapped, play the whole album / discography
                // (no drill-down — head-unit users want one tap to
                // music, not three).
                val songs = browseableItem(MEDIA_ID_LIBRARY_SONGS, "Songs")
                val albums = browseableItem(MEDIA_ID_LIBRARY_ALBUMS, "Albums")
                val artists = browseableItem(MEDIA_ID_LIBRARY_ARTISTS, "Artists")
                Futures.immediateFuture(
                    LibraryResult.ofItemList(
                        ImmutableList.of(songs, albums, artists),
                        params,
                    ),
                )
            }
            MEDIA_ID_LIBRARY_SONGS -> {
                // First N tracks alphabetically. Capped because AA's
                // browse list isn't the right surface for a 10k-track
                // library — large libraries should reach for
                // Stations or Recently Played instead, where the
                // smart-query filtering keeps things bounded.
                val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                scope.launch {
                    runCatching {
                        val libraryRepo = GlobalContext.get().get<
                            com.visorcraft.radioshed.data.LibraryRepository
                        >()
                        val tracks = libraryRepo.listAvailableTracks(
                            limit = AA_BROWSE_TRACK_LIMIT,
                            offset = 0L,
                        ).sortedBy { it.title.lowercase() }
                        val items = tracks.map { track ->
                            playableItem(
                                mediaId = MEDIA_ID_TRACK_PREFIX + track.id,
                                title = track.title.ifBlank { track.fileName },
                                subtitle = null,
                            )
                        }
                        future.set(LibraryResult.ofItemList(
                            ImmutableList.copyOf(items),
                            params,
                        ))
                    }.onFailure {
                        Log.e(TAG, "onGetChildren songs: failed", it)
                        future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                    }
                }
                future
            }
            MEDIA_ID_LIBRARY_ALBUMS -> {
                // Each album is a single playable row — tap plays the
                // whole album from track 1 (alphabetical-by-disc-or-
                // track-number ordering owned by tracksForAlbum). No
                // drill-down: AA's UX guideline is to keep "tap → music"
                // to one gesture so the user can keep eyes on the road.
                val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                scope.launch {
                    runCatching {
                        val libraryRepo = GlobalContext.get().get<
                            com.visorcraft.radioshed.data.LibraryRepository
                        >()
                        // Sort + cap on the IO dispatcher rather than
                        // the session callback's Main thread — for a
                        // 2k-album library, the lowercase()/compare
                        // sort is O(n log n) on a thread that also
                        // services UI input. Move it off-Main to
                        // pre-empt an ANR on the Galaxy Fold class
                        // of devices.
                        val items = withContext(
                            com.visorcraft.radioshed.data.IODispatcher,
                        ) {
                            libraryRepo.observeAlbums().first()
                                .sortedBy { it.title.lowercase() }
                                .take(AA_BROWSE_TRACK_LIMIT.toInt())
                                .map { album ->
                                    playableItem(
                                        mediaId = MEDIA_ID_ALBUM_PREFIX + album.id,
                                        title = album.title.ifBlank { "Unknown album" },
                                        subtitle = null,
                                    )
                                }
                        }
                        future.set(LibraryResult.ofItemList(
                            ImmutableList.copyOf(items),
                            params,
                        ))
                    }.onFailure {
                        Log.e(TAG, "onGetChildren albums: failed", it)
                        future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                    }
                }
                future
            }
            MEDIA_ID_LIBRARY_ARTISTS -> {
                // Each artist is a single playable row — tap plays
                // every available track tagged to that artist. Same
                // single-tap rationale as albums.
                val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                scope.launch {
                    runCatching {
                        val libraryRepo = GlobalContext.get().get<
                            com.visorcraft.radioshed.data.LibraryRepository
                        >()
                        val items = withContext(
                            com.visorcraft.radioshed.data.IODispatcher,
                        ) {
                            libraryRepo.observeArtists().first()
                                .sortedBy { it.name.lowercase() }
                                .take(AA_BROWSE_TRACK_LIMIT.toInt())
                                .map { artist ->
                                    playableItem(
                                        mediaId = MEDIA_ID_ARTIST_PREFIX + artist.id,
                                        title = artist.name.ifBlank { "Unknown artist" },
                                        subtitle = null,
                                    )
                                }
                        }
                        future.set(LibraryResult.ofItemList(
                            ImmutableList.copyOf(items),
                            params,
                        ))
                    }.onFailure {
                        Log.e(TAG, "onGetChildren artists: failed", it)
                        future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                    }
                }
                future
            }
            MEDIA_ID_RECENTLY_PLAYED -> {
                // Newest-first, dedup'd by trackId. Pulls from
                // station_history (covers any in-app or AA
                // playback) and resolves each id against
                // LibraryRepository so we render the live track
                // metadata, not a stale snapshot from history.
                val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                scope.launch {
                    runCatching {
                        val stationRepo = GlobalContext.get().get<StationRepository>()
                        val libraryRepo = GlobalContext.get().get<
                            com.visorcraft.radioshed.data.LibraryRepository
                        >()
                        val history = stationRepo.allHistory(limit = 60L)
                        val seen = mutableSetOf<Long>()
                        val tracks = mutableListOf<com.visorcraft.radioshed.domain.Track>()
                        for (entry in history) {
                            if (!seen.add(entry.trackId)) continue
                            libraryRepo.getTrackById(entry.trackId)?.let { tracks += it }
                            if (tracks.size >= AA_RECENTLY_PLAYED_LIMIT) break
                        }
                        val items = tracks.map { track ->
                            playableItem(
                                mediaId = MEDIA_ID_TRACK_PREFIX + track.id,
                                title = track.title.ifBlank { track.fileName },
                                subtitle = null,
                            )
                        }
                        future.set(LibraryResult.ofItemList(
                            ImmutableList.copyOf(items),
                            params,
                        ))
                    }.onFailure {
                        Log.e(TAG, "onGetChildren recently played: failed", it)
                        future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                    }
                }
                future
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
         * Voice search. Android Auto / Google Assistant fires this
         * when the user says "Hey Google, play <X> on RadioShed" or
         * uses the search field on AA's media surface. The contract
         * is two-step: [onSearch] kicks off the work and pings the
         * caller via [MediaLibrarySession.notifySearchResultChanged]
         * when results are ready; the caller then issues
         * [onGetSearchResult] to actually pull the matched
         * MediaItems.
         *
         * We delegate to the in-app [SearchRepository.searchAll]
         * which runs three concurrent SQL queries (tracks / albums /
         * artists). Result ordering on the AA surface puts tracks
         * first because that's the most-likely-correct interpretation
         * of "play X" — voice queries usually target a song.
         */
        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            Log.i(TAG, "onSearch: query='$query' caller=${browser.packageName}")
            val future = SettableFuture.create<LibraryResult<Void>>()
            scope.launch {
                runCatching {
                    val searchRepo = GlobalContext.get().get<
                        com.visorcraft.radioshed.data.SearchRepository
                    >()
                    val results = searchRepo.searchAll(query, limitPerCategory = 20L)
                    val items = buildSearchMediaItems(results)
                    // Cache so the immediately-following onGetSearchResult
                    // call drains the same snapshot. Without this, the
                    // count we advertise here and the items returned
                    // there come from two independent DB reads — a
                    // library scan running between the two would skew
                    // the result and break Auto's pagination invariant.
                    pendingSearchResults[query] = items
                    session.notifySearchResultChanged(browser, query, items.size, params)
                    future.set(LibraryResult.ofVoid(params))
                }.onFailure {
                    Log.e(TAG, "onSearch: failed", it)
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                }
            }
            return future
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            Log.i(
                TAG,
                "onGetSearchResult: query='$query' caller=${browser.packageName} " +
                    "page=$page pageSize=$pageSize",
            )
            // Drain the cached items from the matching onSearch call.
            // Falling back to a fresh searchAll only happens when the
            // controller calls onGetSearchResult without a preceding
            // onSearch — atypical but the spec doesn't forbid it.
            val cached = pendingSearchResults.remove(query)
            if (cached != null) {
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(ImmutableList.copyOf(cached), params),
                )
            }
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch {
                runCatching {
                    val searchRepo = GlobalContext.get().get<
                        com.visorcraft.radioshed.data.SearchRepository
                    >()
                    val results = searchRepo.searchAll(query, limitPerCategory = 20L)
                    val items = buildSearchMediaItems(results)
                    future.set(LibraryResult.ofItemList(
                        ImmutableList.copyOf(items),
                        params,
                    ))
                }.onFailure {
                    Log.e(TAG, "onGetSearchResult: failed", it)
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                }
            }
            return future
        }

        /**
         * Build the ordered MediaItem list shown in Auto's search results.
         * Tracks first (most common voice-search target — "play X"
         * autoplays the first hit), then albums, then artists.
         */
        private fun buildSearchMediaItems(
            results: com.visorcraft.radioshed.data.SearchResults,
        ): List<MediaItem> {
            val items = mutableListOf<MediaItem>()
            for (track in results.tracks) {
                items += playableItem(
                    mediaId = MEDIA_ID_TRACK_PREFIX + track.id,
                    title = track.title.ifBlank { track.fileName },
                    subtitle = null,
                )
            }
            for (album in results.albums) {
                items += playableItem(
                    mediaId = MEDIA_ID_ALBUM_PREFIX + album.id,
                    title = "Album: ${album.title}",
                    subtitle = null,
                )
            }
            for (artist in results.artists) {
                items += playableItem(
                    mediaId = MEDIA_ID_ARTIST_PREFIX + artist.id,
                    title = "Artist: ${artist.name}",
                    subtitle = null,
                )
            }
            return items
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
            // Voice-search route ("Hey Google, play X on RadioShed").
            // Google Assistant builds a single MediaItem whose
            // requestMetadata.searchQuery carries the spoken query
            // string and a structured extras Bundle (per the long-
            // standing MediaStore.EXTRA_MEDIA_FOCUS / _ARTIST /
            // _ALBUM / _TITLE convention forwarded through Media3).
            // Without this branch we'd fall through to the prefix
            // dispatchers, see no station/track/album/artist prefix,
            // and hand the URI-less item to the player — which is
            // what produced Assistant's "check the application for
            // errors" toast pre-fix.
            //
            // Earlier free-text-only handling let "play a song by
            // Black Pink" land on the wrong artist because the noisy
            // verb tokens ("play", "song", "by") matched unrelated
            // titles via LIKE. Switching to the structured extras
            // when present (artist focus → search artists only,
            // album focus → search albums only, etc.) avoids that
            // class of mis-route entirely.
            // Tighter voice-detection: only trigger when searchQuery
            // is non-blank. Triggering on bare extras (no
            // searchQuery) was over-aggressive — AA also sends
            // browse-style setMediaItems with extras-but-no-query,
            // and intercepting those broke the MediaController's
            // expected flow → Assistant surfaced "can't play music
            // on Radio Shed because it's not a supported platform".
            val voiceItem = mediaItems.firstOrNull { item ->
                !item.requestMetadata.searchQuery.isNullOrBlank()
            }
            if (voiceItem != null) {
                val rm = voiceItem.requestMetadata
                val rawQuery = rm.searchQuery?.toString().orEmpty()
                val extras = rm.extras
                val focus = extras?.getString(EXTRA_MEDIA_FOCUS)
                val artistExtra = extras?.getString(EXTRA_MEDIA_ARTIST)
                val albumExtra = extras?.getString(EXTRA_MEDIA_ALBUM)
                val titleExtra = extras?.getString(EXTRA_MEDIA_TITLE)
                val genreExtra = extras?.getString(EXTRA_MEDIA_GENRE)
                Log.i(
                    TAG,
                    "onSetMediaItems: voice search " +
                        "caller=${controller.packageName} " +
                        "query='$rawQuery' focus='$focus' " +
                        "artist='$artistExtra' album='$albumExtra' " +
                        "title='$titleExtra' genre='$genreExtra'",
                )
                scope.launch {
                    runCatching {
                        val searchRepo = GlobalContext.get().get<
                            com.visorcraft.radioshed.data.SearchRepository
                        >()
                        val libraryRepo = GlobalContext.get().get<
                            com.visorcraft.radioshed.data.LibraryRepository
                        >()
                        val playback = GlobalContext.get().get<StationPlayback>()

                        // Focus-driven dispatch. The MIME-type strings
                        // are the legacy MediaStore "vnd.android.cursor.item/<thing>"
                        // tokens that have been the Assistant ↔ media-app
                        // contract since the original Voice Actions API.
                        suspend fun playArtistByName(name: String): Boolean {
                            val artists = searchRepo.searchArtists(name, limit = 5)
                            val artist = artists.firstOrNull() ?: return false
                            val tracks = libraryRepo.tracksForArtist(artist.id)
                            if (tracks.isEmpty()) return false
                            playback.playTracks(tracks, startIndex = 0)
                            return true
                        }
                        suspend fun playAlbumByTitle(name: String): Boolean {
                            val albums = searchRepo.searchAlbums(name, limit = 5)
                            val album = albums.firstOrNull() ?: return false
                            val tracks = libraryRepo.tracksForAlbum(album.id)
                            if (tracks.isEmpty()) return false
                            playback.playTracks(tracks, startIndex = 0)
                            return true
                        }
                        suspend fun playTrackByTitle(name: String): Boolean {
                            val tracks = searchRepo.searchTracks(name, limit = 5)
                            if (tracks.isEmpty()) return false
                            playback.playTracks(tracks, startIndex = 0)
                            return true
                        }

                        val handled = when {
                            // Artist focus: ignore the noisy
                            // free-text query and use the structured
                            // artist name directly.
                            focus == FOCUS_ARTIST && !artistExtra.isNullOrBlank() ->
                                playArtistByName(artistExtra)
                            focus == FOCUS_ALBUM && !albumExtra.isNullOrBlank() ->
                                playAlbumByTitle(albumExtra)
                            // Title focus often comes paired with an
                            // artist hint — searchTracks already
                            // searches across title/artist/album so
                            // a combined phrase still works, but the
                            // bare title is the most precise input.
                            focus == FOCUS_AUDIO && !titleExtra.isNullOrBlank() ->
                                playTrackByTitle(titleExtra)
                            // Genre focus: pick a few tracks tagged
                            // with the genre via the DSL path.
                            focus == FOCUS_GENRE && !genreExtra.isNullOrBlank() -> {
                                val tracks = searchRepo
                                    .searchTracks("genre:${'$'}genreExtra", limit = 50)
                                if (tracks.isEmpty()) {
                                    false
                                } else {
                                    playback.playTracks(tracks, startIndex = 0)
                                    true
                                }
                            }
                            // Common case: structured extras present
                            // even without a focus (e.g. "play music
                            // by X" sometimes only fills artistExtra).
                            !artistExtra.isNullOrBlank() ->
                                playArtistByName(artistExtra)
                            !albumExtra.isNullOrBlank() ->
                                playAlbumByTitle(albumExtra)
                            !titleExtra.isNullOrBlank() ->
                                playTrackByTitle(titleExtra)
                            // Plain free-text fallback. This is the
                            // path that lit up the wrong-artist bug
                            // when "play a song by Black Pink" came
                            // through verbatim. Strip the leading
                            // verb/article tokens before searching so
                            // `searchAll` LIKE-matches against
                            // meaningful nouns only. The list mirrors
                            // the prefixes Assistant transcribes.
                            rawQuery.isNotBlank() -> {
                                val cleaned = rawQuery
                                    .removePrefixToken("play")
                                    .removePrefixToken("listen to")
                                    .removePrefixToken("a song by")
                                    .removePrefixToken("song by")
                                    .removePrefixToken("songs by")
                                    .removePrefixToken("music by")
                                    .removePrefixToken("the song")
                                    .removePrefixToken("a song")
                                    .removePrefixToken("song")
                                    .trim()
                                val results = searchRepo.searchAll(
                                    cleaned.ifBlank { rawQuery },
                                    limitPerCategory = 20L,
                                )
                                when {
                                    results.artists.isNotEmpty() -> {
                                        val tracks = libraryRepo
                                            .tracksForArtist(results.artists.first().id)
                                        if (tracks.isNotEmpty()) {
                                            playback.playTracks(tracks, startIndex = 0)
                                            true
                                        } else false
                                    }
                                    results.tracks.isNotEmpty() -> {
                                        playback.playTracks(results.tracks, startIndex = 0)
                                        true
                                    }
                                    results.albums.isNotEmpty() -> {
                                        val tracks = libraryRepo
                                            .tracksForAlbum(results.albums.first().id)
                                        if (tracks.isNotEmpty()) {
                                            playback.playTracks(tracks, startIndex = 0)
                                            true
                                        } else false
                                    }
                                    else -> false
                                }
                            }
                            else -> false
                        }
                        if (!handled) {
                            Log.i(
                                TAG,
                                "voice search: no match for query='$rawQuery' " +
                                    "artist='$artistExtra' album='$albumExtra' " +
                                    "title='$titleExtra'",
                            )
                        }
                    }.onFailure {
                        Log.e(TAG, "voice search failed", it)
                    }
                }
                // Empty queue + INDEX_UNSET tells the player not to
                // touch its current items — StationPlayback will push
                // real tracks in via the in-app controller in a moment.
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        ImmutableList.of(),
                        C.INDEX_UNSET,
                        C.TIME_UNSET,
                    ),
                )
            }

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

            // Track items from Library / Recently Played: AA tapped
            // a single song. Resolve the trackId, build a one-track
            // queue, and route through StationPlayback so the
            // existing queue / shuffle / repeat machinery owns the
            // session — same intercept pattern as stations above.
            val firstTrackId = mediaItems
                .firstOrNull { it.mediaId.startsWith(MEDIA_ID_TRACK_PREFIX) }
                ?.mediaId
                ?.removePrefix(MEDIA_ID_TRACK_PREFIX)
                ?.toLongOrNull()

            if (firstTrackId != null) {
                scope.launch {
                    runCatching {
                        val libraryRepo = GlobalContext.get().get<
                            com.visorcraft.radioshed.data.LibraryRepository
                        >()
                        val track = libraryRepo.getTrackById(firstTrackId)
                            ?: return@runCatching
                        GlobalContext.get().get<StationPlayback>()
                            .playTracks(listOf(track), startIndex = 0)
                    }
                }
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        ImmutableList.of(),
                        C.INDEX_UNSET,
                        C.TIME_UNSET,
                    ),
                )
            }

            // Album row tap → load every track on the album and
            // play them in their natural disc / track order.
            val firstAlbumId = mediaItems
                .firstOrNull { it.mediaId.startsWith(MEDIA_ID_ALBUM_PREFIX) }
                ?.mediaId
                ?.removePrefix(MEDIA_ID_ALBUM_PREFIX)
                ?.toLongOrNull()

            if (firstAlbumId != null) {
                scope.launch {
                    runCatching {
                        val libraryRepo = GlobalContext.get().get<
                            com.visorcraft.radioshed.data.LibraryRepository
                        >()
                        val tracks = libraryRepo.tracksForAlbum(firstAlbumId)
                        if (tracks.isNotEmpty()) {
                            GlobalContext.get().get<StationPlayback>()
                                .playTracks(tracks, startIndex = 0)
                        }
                    }
                }
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        ImmutableList.of(),
                        C.INDEX_UNSET,
                        C.TIME_UNSET,
                    ),
                )
            }

            // Artist row tap → play every available track tagged
            // to that artist. tracksForArtist returns the existing
            // canonical sort (album → disc → track number); good
            // enough for an artist-level shuffle replacement
            // without us having to ship a "play artist" station.
            val firstArtistId = mediaItems
                .firstOrNull { it.mediaId.startsWith(MEDIA_ID_ARTIST_PREFIX) }
                ?.mediaId
                ?.removePrefix(MEDIA_ID_ARTIST_PREFIX)
                ?.toLongOrNull()

            if (firstArtistId != null) {
                scope.launch {
                    runCatching {
                        val libraryRepo = GlobalContext.get().get<
                            com.visorcraft.radioshed.data.LibraryRepository
                        >()
                        val tracks = libraryRepo.tracksForArtist(firstArtistId)
                        if (tracks.isNotEmpty()) {
                            GlobalContext.get().get<StationPlayback>()
                                .playTracks(tracks, startIndex = 0)
                        }
                    }
                }
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

    /**
     * Rebuild a playable [MediaItem] from the flat fields a crossfade / up-next
     * custom command carries in its [Bundle]. Mirrors `Playable.toMediaItem`
     * but without the inline-artwork-bytes path: the preloaded/crossfaded item
     * only needs to decode and play; the artwork URI is enough for the
     * notification, and the proxy's normal `play()` already pushed full
     * metadata for the steady-state track. Returns null when no URI is present.
     */
    private fun mediaItemFromArgs(args: Bundle): MediaItem? {
        val uri = args.getString(CrossfadeIpc.EXTRA_ITEM_URI)
            ?.takeIf { it.isNotBlank() } ?: return null
        val trackId = args.getLong(CrossfadeIpc.EXTRA_ITEM_TRACK_ID, 0L)
        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(trackId.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(args.getString(CrossfadeIpc.EXTRA_ITEM_TITLE))
                    .setArtist(args.getString(CrossfadeIpc.EXTRA_ITEM_ARTIST))
                    .setAlbumTitle(args.getString(CrossfadeIpc.EXTRA_ITEM_ALBUM))
                    .also { mb ->
                        args.getString(CrossfadeIpc.EXTRA_ITEM_ARTWORK_URI)
                            ?.takeIf { it.isNotBlank() }
                            ?.let { mb.setArtworkUri(android.net.Uri.parse(it)) }
                    }
                    .build(),
            )
        val cueStart = args.getLong(CrossfadeIpc.EXTRA_ITEM_CUE_START_MS, -1L)
        val cueEnd = args.getLong(CrossfadeIpc.EXTRA_ITEM_CUE_END_MS, -1L)
        if (cueStart >= 0L || cueEnd >= 0L) {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(if (cueStart >= 0L) cueStart else 0L)
                    .also { cc -> if (cueEnd >= 0L) cc.setEndPositionMs(cueEnd) }
                    .build(),
            )
        }
        return builder.build()
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
        /** Single greppable tag for the AA / lock-screen / Bluetooth
         *  handshake debugging. Pull with:
         *    `adb logcat -s RadioShedMS:V` */
        const val TAG = "RadioShedMS"
        const val MEDIA_ID_ROOT = "root"
        const val MEDIA_ID_STATIONS = "stations"
        const val MEDIA_ID_LIBRARY = "library"
        const val MEDIA_ID_LIBRARY_SONGS = "library/songs"
        const val MEDIA_ID_LIBRARY_ALBUMS = "library/albums"
        const val MEDIA_ID_LIBRARY_ARTISTS = "library/artists"
        const val MEDIA_ID_RECENTLY_PLAYED = "recently_played"
        const val MEDIA_ID_TRACK_PREFIX = "track_"
        const val MEDIA_ID_ALBUM_PREFIX = "album_"
        const val MEDIA_ID_ARTIST_PREFIX = "artist_"

        /** Cap on Library → Songs items returned to AA. Beyond this
         *  the user is better served by Stations (smart-query
         *  filtering) or Recently Played (already bounded). */
        const val AA_BROWSE_TRACK_LIMIT = 200L

        /** Recently-played list size after dedup. */
        const val AA_RECENTLY_PLAYED_LIMIT = 30
        const val MEDIA_ID_STATION_PREFIX = "station_"

        // Cap on the [pendingSearchResults] cache. 16 distinct
        // queries is generous — typical voice or AA-search flows
        // touch one query at a time, and the user-typed in-app
        // search debounces so a single typing burst hits one or
        // two cache entries, not dozens.
        const val MAX_PENDING_SEARCH_RESULTS = 16

        // Custom action ids used by the Android Auto layout. Values are
        // arbitrary but must round-trip exactly through SessionCommand.
        const val ACTION_TOGGLE_STAR = "com.visorcraft.radioshed.RATE_STAR"
        const val ACTION_DISLIKE_ON_STATION = "com.visorcraft.radioshed.DISLIKE"
        const val ACTION_TOGGLE_LIKE = "com.visorcraft.radioshed.LIKE"
        const val ACTION_CYCLE_REPEAT = "com.visorcraft.radioshed.REPEAT"
        const val ACTION_TOGGLE_SHUFFLE = "com.visorcraft.radioshed.SHUFFLE"

        // Notification config for the foreground media-playback service.
        // Channel id is referenced from the manifest's auto-created
        // foreground-service channel; OS uses it for "Now playing"
        // notifications. Notification id is unique within our app.
        const val NOTIFICATION_ID = 0xCAFE  // 51966
        const val NOTIFICATION_CHANNEL_ID = "radioshed.playback"

        // Google Assistant voice-search extras. These match the
        // long-standing MediaStore.EXTRA_MEDIA_* / EXTRA_MEDIA_FOCUS
        // string constants — Assistant continues to forward voice
        // queries through this Bundle even after the public
        // MediaSessionCompat.Callback.onPlayFromSearch path was
        // superseded by Media3's RequestMetadata. We hardcode the
        // string keys (rather than reference MediaStore.EXTRA_*)
        // to keep this file decoupled from the MediaStore API
        // surface — the contract is the string value, not the
        // constant.
        const val EXTRA_MEDIA_FOCUS = "android.intent.extra.focus"
        const val EXTRA_MEDIA_ARTIST = "android.intent.extra.artist"
        const val EXTRA_MEDIA_ALBUM = "android.intent.extra.album"
        const val EXTRA_MEDIA_TITLE = "android.intent.extra.title"
        const val EXTRA_MEDIA_GENRE = "android.intent.extra.genre"

        // EXTRA_MEDIA_FOCUS values — the legacy
        // "vnd.android.cursor.item/<x>" MIME-type tokens Assistant
        // uses to declare what it thinks the user asked for.
        const val FOCUS_ARTIST = "vnd.android.cursor.item/artist"
        const val FOCUS_ALBUM = "vnd.android.cursor.item/album"
        const val FOCUS_AUDIO = "vnd.android.cursor.item/audio"
        const val FOCUS_GENRE = "vnd.android.cursor.item/genre"
    }
}

/**
 * Strip a leading filler-token phrase (case-insensitive, trimmed) from
 * a transcribed voice query. The match is whole-word — `removePrefixToken("song")`
 * strips "song " from "song by foo" but leaves "songbird foo" alone.
 */
private fun String.removePrefixToken(token: String): String {
    val trimmed = this.trimStart()
    if (!trimmed.startsWith(token, ignoreCase = true)) return this
    val rest = trimmed.substring(token.length)
    if (rest.isNotEmpty() && !rest[0].isWhitespace()) return this
    return rest.trimStart()
}
