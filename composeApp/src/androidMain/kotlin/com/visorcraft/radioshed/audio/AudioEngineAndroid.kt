package com.visorcraft.radioshed.audio

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * [AudioEngine] backed by Media3. Connects asynchronously to
 * [RadioShedMediaSessionService] via a [MediaController] — once the future
 * resolves, all transport calls forward to it. Calls made before the
 * connection completes are queued via the [controllerDeferred] gate.
 *
 * State-flow updates are driven by the controller's [Player.Listener]
 * callbacks; position is polled at 2 Hz while a track is loaded.
 */
class AudioEngineAndroid(private val appContext: Context) : AudioEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val controllerDeferred = CompletableDeferred<MediaController>()
    private var controller: MediaController? = null
    private var positionJob: Job? = null

    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _nowPlaying = MutableStateFlow<Playable?>(null)
    override val nowPlaying: StateFlow<Playable?> = _nowPlaying.asStateFlow()

    private val _position = MutableStateFlow(Position(0L, 0L))
    override val position: StateFlow<Position> = _position.asStateFlow()

    init {
        connect()
    }

    private fun connect() {
        val token = SessionToken(
            appContext,
            ComponentName(appContext, RadioShedMediaSessionService::class.java),
        )
        val future: ListenableFuture<MediaController> =
            MediaController.Builder(appContext, token).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                controller = c
                wireListeners(c)
                refreshStateFromController(c)
                controllerDeferred.complete(c)
            } catch (t: Throwable) {
                _state.value = PlayerState.Error(
                    "Failed to connect to audio service: ${t.message ?: t::class.simpleName}",
                )
                controllerDeferred.completeExceptionally(t)
            }
        }, MoreExecutors.directExecutor())
    }

    private fun wireListeners(c: MediaController) {
        c.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                refreshStateFromController(c)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                refreshStateFromController(c)
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.value = PlayerState.Error(error.message ?: "Player error")
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _nowPlaying.value = mediaItem?.toPlayable(
                    fallbackDurationMs = if (c.duration > 0) c.duration else 0L,
                )
            }
        })
    }

    private fun refreshStateFromController(c: MediaController) {
        val newState = when {
            c.playerError != null -> PlayerState.Error(
                c.playerError?.message ?: "Player error",
            )
            c.playbackState == Player.STATE_BUFFERING -> PlayerState.Buffering
            c.isPlaying -> PlayerState.Playing
            c.playbackState == Player.STATE_READY -> PlayerState.Paused
            c.playbackState == Player.STATE_ENDED -> PlayerState.Ended
            else -> PlayerState.Idle
        }
        _state.value = newState

        // Only poll position while there's something meaningful to report.
        // Without this guard the polling loop runs forever and would call
        // c.currentPosition on a released controller after stop()/release(),
        // throwing IllegalStateException, plus burns 2 hops per second on
        // the Main thread for the entire process lifetime.
        when (newState) {
            PlayerState.Playing, PlayerState.Buffering, PlayerState.Paused -> ensurePositionPolling(c)
            else -> {
                positionJob?.cancel()
                positionJob = null
            }
        }
    }

    private fun ensurePositionPolling(c: MediaController) {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch {
            while (true) {
                _position.value = Position(
                    currentMs = c.currentPosition.coerceAtLeast(0L),
                    durationMs = if (c.duration > 0L) c.duration else 0L,
                )
                delay(500L)
            }
        }
    }

    override fun play(playable: Playable) {
        scope.launch {
            val c = awaitControllerOrSurface() ?: return@launch
            c.setMediaItem(playable.toMediaItem())
            c.prepare()
            c.play()
            _nowPlaying.value = playable
        }
    }

    override fun playQueue(items: List<Playable>, startIndex: Int) {
        if (items.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, items.size - 1)
        scope.launch {
            val c = awaitControllerOrSurface() ?: return@launch
            c.setMediaItems(items.map { it.toMediaItem() }, safeIndex, /* startPositionMs = */ 0L)
            c.prepare()
            c.play()
            _nowPlaying.value = items[safeIndex]
        }
    }

    /**
     * Awaits the controller, surfacing connection failures as PlayerState.Error
     * rather than letting them propagate as unhandled-exception coroutine crashes
     * (the SupervisorJob would route them to the default uncaught handler with no
     * UI feedback).
     */
    private suspend fun awaitControllerOrSurface(): MediaController? =
        runCatching { controllerDeferred.await() }
            .getOrElse {
                _state.value = PlayerState.Error(
                    "Audio service unavailable: ${it.message ?: it::class.simpleName}",
                )
                null
            }

    override fun pause() {
        controller?.pause()
    }

    override fun resume() {
        controller?.play()
    }

    override fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    override fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun skipNext() {
        controller?.seekToNextMediaItem()
    }

    override fun skipPrevious() {
        controller?.seekToPreviousMediaItem()
    }

    override fun stop() {
        controller?.stop()
        _nowPlaying.value = null
        _position.value = Position(0L, 0L)
    }

    /** Call from process/app shutdown if you want the controller to release cleanly. */
    fun release() {
        positionJob?.cancel()
        controller?.release()
        controller = null
        scope.cancel()
    }
}

// ---- Mappers ----

private fun Playable.toMediaItem(): MediaItem {
    val builder = MediaItem.Builder()
        .setUri(uri)
        .setMediaId(trackId.toString())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .also { mb ->
                    artworkUri?.let { mb.setArtworkUri(android.net.Uri.parse(it)) }
                }
                .build(),
        )
    if (cueOffsetStartMs != null || cueOffsetEndMs != null) {
        builder.setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(cueOffsetStartMs ?: 0L)
                .also { cc ->
                    cueOffsetEndMs?.let { cc.setEndPositionMs(it) }
                }
                .build(),
        )
    }
    return builder.build()
}

private fun MediaItem.toPlayable(fallbackDurationMs: Long = 0L): Playable {
    val md = mediaMetadata
    val uri = localConfiguration?.uri?.toString().orEmpty()
    val clipping = clippingConfiguration
    return Playable(
        trackId = mediaId.toLongOrNull() ?: 0L,
        uri = uri,
        title = md.title?.toString().orEmpty(),
        artist = md.artist?.toString(),
        album = md.albumTitle?.toString(),
        durationMs = fallbackDurationMs,
        artworkUri = md.artworkUri?.toString(),
        cueOffsetStartMs = clipping.startPositionMs.takeIf { it > 0L },
        // Media3's "no end clipping" sentinel is Long.MAX_VALUE, not Long.MIN_VALUE.
        // The previous check let MAX_VALUE round-trip as a literal end position.
        cueOffsetEndMs = clipping.endPositionMs.takeIf { it > 0L && it != Long.MAX_VALUE },
    )
}
