package com.visorcraft.radioshed.audio

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
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
import com.visorcraft.radioshed.util.crashGuard
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

    // crashGuard prevents a Media3 callback exception (bad media file,
    // controller disconnect during teardown, etc.) from propagating to
    // Android's system uncaught-exception handler and showing the
    // "RadioShed keeps stopping" OS dialog.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + crashGuard("AudioEngineAndroid"))

    private val controllerDeferred = CompletableDeferred<MediaController>()
    private var controller: MediaController? = null
    private var positionJob: Job? = null

    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _nowPlaying = MutableStateFlow<Playable?>(null)
    override val nowPlaying: StateFlow<Playable?> = _nowPlaying.asStateFlow()

    private val _position = MutableStateFlow(Position(0L, 0L))
    override val position: StateFlow<Position> = _position.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    override val volume: StateFlow<Float> = _volume.asStateFlow()

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
                // Sync the volume StateFlow with the actual controller
                // volume so consumers (SleepTimer fade, ReplayGain) see
                // the real value rather than the construction-time
                // default of 1f.
                _volume.value = c.volume.coerceIn(0f, 1f)
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
            c.setMediaItem(playable.toMediaItem(appContext))
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
            c.setMediaItems(
                items.map { it.toMediaItem(appContext) },
                safeIndex,
                /* startPositionMs = */ 0L,
            )
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
        // Reset volume to unity so a future session doesn't start at
        // whatever fade fraction the SleepTimer left behind. ReplayGain
        // re-applies on the next track load anyway.
        runCatching { controller?.volume = 1f }
        _volume.value = 1f
    }

    override fun setVolume(fraction: Float) {
        // MediaController.volume is the "session volume" Media3 multiplier
        // (0.0..1.0); below the underlying audio stream volume but above
        // the ExoPlayer's internal audio-track gain. Suitable for our
        // per-track ReplayGain attenuation. Clamping prevents boosting
        // (which would just clip) and keeps us out of negative-fraction
        // territory.
        val clamped = fraction.coerceIn(0f, 1f)
        // Wrap in runCatching: the controller may already be released
        // (e.g. SleepTimer fade ticker still firing after the service
        // is destroyed), and Media3 throws IllegalStateException on
        // operations against a released MediaController.
        runCatching { controller?.volume = clamped }
        _volume.value = clamped
    }

    override fun setPlaybackSpeed(speed: Float) {
        // Media3 accepts any positive Float; we clamp to 0.5..2.0 to
        // keep the user out of stutter / chipmunk territory. The
        // controller might be null briefly during service hand-off,
        // so guard with runCatching the same way setVolume does.
        val clamped = speed.coerceIn(0.5f, 2.0f)
        runCatching { controller?.setPlaybackSpeed(clamped) }
    }

    override fun setCrossfade(crossfade: com.visorcraft.radioshed.domain.Crossfade?) {
        // No-op on Android: the crossfade-arming decision lives in
        // StationPlayback (it polls position and owns the queue), and it hands
        // the resolved duration + curve straight to [crossfadeTo]. The service
        // therefore never needs the rule object itself — only the per-transition
        // ramp params. Kept as an override (vs the interface default) to make
        // the "intentionally nothing to forward here" decision explicit; desktop
        // (PLAN 3.3) will use this hook to arm its own libvlc ramp.
    }

    override fun setUpNext(playable: Playable?) {
        if (playable == null) return
        sendServiceCommand(CrossfadeIpc.ACTION_SET_UP_NEXT) {
            putAll(CrossfadeIpc.bundleForItem(playable))
        }
    }

    override fun crossfadeTo(
        playable: Playable,
        durationMs: Long,
        curve: com.visorcraft.radioshed.domain.CrossfadeCurve,
    ) {
        sendServiceCommand(CrossfadeIpc.ACTION_CROSSFADE_TO) {
            putAll(CrossfadeIpc.bundleForItem(playable))
            putLong(CrossfadeIpc.EXTRA_CROSSFADE_DURATION_MS, durationMs)
            putString(CrossfadeIpc.EXTRA_CROSSFADE_CURVE, curve.name)
        }
    }

    override fun cancelCrossfade() {
        sendServiceCommand(CrossfadeIpc.ACTION_CANCEL_CROSSFADE) {}
    }

    /**
     * Send a custom [SessionCommand] to the playback service once the
     * controller is connected. Awaits the controller (these commands are
     * harmless to drop if the service never comes up) and swallows failures
     * the same way the other transport calls do.
     */
    private fun sendServiceCommand(action: String, fill: android.os.Bundle.() -> Unit) {
        val args = android.os.Bundle().apply(fill)
        scope.launch {
            val c = awaitControllerOrSurface() ?: return@launch
            runCatching {
                c.sendCustomCommand(
                    androidx.media3.session.SessionCommand(action, android.os.Bundle.EMPTY),
                    args,
                )
            }
        }
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

private fun Playable.toMediaItem(appContext: Context): MediaItem {
    val builder = MediaItem.Builder()
        .setUri(uri)
        .setMediaId(trackId.toString())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .also { mb ->
                    // Two artwork channels covering both consumers:
                    //   - setArtworkUri: a content:// URI surfaced via
                    //     FileProvider so out-of-process consumers
                    //     (Android Auto, Bluetooth AVRCP cover art,
                    //     system lock screen) can fetch the full-fidelity
                    //     image directly. Their processes can't read
                    //     file:// from our private filesDir; a content://
                    //     URI is the canonical bridge.
                    //   - setArtworkData: downscaled 512px JPEG bytes
                    //     parceled inline as a fallback for hosts that
                    //     don't follow the URI (some BT AVRCP stacks,
                    //     legacy AA versions).
                    // In-app surfaces (Coil in MiniPlayer / NowPlaying)
                    // continue to read the original file:// URI from
                    // [Playable.artworkUri] directly — they don't go
                    // through MediaMetadata.
                    val uriString = artworkUri
                    if (uriString != null) {
                        val publicUri = artworkContentUri(appContext, uriString)
                            ?: android.net.Uri.parse(uriString)
                        mb.setArtworkUri(publicUri)
                        readArtworkBytes(uriString)?.let { bytes ->
                            mb.setArtworkData(
                                bytes,
                                MediaMetadata.PICTURE_TYPE_FRONT_COVER,
                            )
                        }
                    }
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

/**
 * Read + downscale the artwork referenced by [uriString] for inline
 * embedding in a MediaMetadata. We only handle `file://` URIs —
 * that's everything AndroidArtworkPersister produces. Returns null on
 * any failure (file missing, IO error, decode failure), and the
 * caller falls back to URI-only.
 *
 * Why downscale instead of pass-through: Android Auto's IPC parcel has
 * a hard ~1 MB cap for MediaMetadata payloads, and embedded MP3 cover
 * art frequently ships at 1500-3000 px / 1-3 MB. The previous
 * "reject anything over 512 KB" path silently produced no-art on AA
 * for those tracks (the most common case for ID3v2 APIC frames from
 * iTunes-era libraries). Recompressing to 512×512 JPEG @ 85% quality
 * lands at ~30-80 KB regardless of source — well under the IPC cap,
 * and AA renders this size at full fidelity since its NowPlaying
 * artwork slot is ~600 px on most head units.
 *
 * Done synchronously on the calling thread because [toMediaItem] is
 * already off the main thread (called from the binder/coroutine path
 * around `setMediaItem`). Decode of a typical 2000×2000 JPEG runs in
 * 30-80 ms which is acceptable per track switch.
 */
private fun readArtworkBytes(uriString: String): ByteArray? = runCatching {
    if (!uriString.startsWith("file://")) {
        Log.i(TAG_ART, "skip: not file:// uri ($uriString)")
        return@runCatching null
    }
    val path = android.net.Uri.parse(uriString).path ?: return@runCatching null
    val file = java.io.File(path)
    if (!file.exists() || !file.canRead()) {
        Log.i(TAG_ART, "skip: file missing or unreadable ($path)")
        return@runCatching null
    }

    // Two-pass decode — first an inJustDecodeBounds probe to read the
    // dimensions without allocating the pixel buffer, then a real
    // decode at an inSampleSize that fits the target box. Avoids
    // allocating a 4000×4000 Bitmap into RAM on hi-res cover art.
    val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, probe)
    if (probe.outWidth <= 0 || probe.outHeight <= 0) {
        Log.i(TAG_ART, "skip: decoder reported zero dimensions ($path)")
        return@runCatching null
    }
    var sample = 1
    val maxOf2 = maxOf(probe.outWidth, probe.outHeight)
    while (maxOf2 / sample > ART_TARGET_PX * 2) sample *= 2

    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val raw = BitmapFactory.decodeFile(path, opts) ?: run {
        Log.i(TAG_ART, "skip: decode returned null ($path)")
        return@runCatching null
    }

    // Final fit-into-box step. Downscale (never up) to ART_TARGET_PX
    // on the long edge, preserving aspect ratio so non-square art
    // (rare in practice but possible) doesn't get squashed.
    val longEdge = maxOf(raw.width, raw.height)
    val scaled = if (longEdge > ART_TARGET_PX) {
        val ratio = ART_TARGET_PX.toFloat() / longEdge
        val w = (raw.width * ratio).toInt().coerceAtLeast(1)
        val h = (raw.height * ratio).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(raw, w, h, true).also {
            if (it !== raw) raw.recycle()
        }
    } else {
        raw
    }

    val out = java.io.ByteArrayOutputStream(64 * 1024)
    scaled.compress(Bitmap.CompressFormat.JPEG, ART_JPEG_QUALITY, out)
    scaled.recycle()
    val bytes = out.toByteArray()
    Log.i(TAG_ART, "embed: ${bytes.size}B (src=${file.length()}B path=$path)")
    bytes
}.onFailure {
    Log.e(TAG_ART, "readArtworkBytes failed for $uriString", it)
}.getOrNull()

private const val TAG_ART = "RadioShedArt"
private const val ART_TARGET_PX = 512
private const val ART_JPEG_QUALITY = 85

/**
 * Translate a `file://<filesDir>/artwork/<sha256>` URI into the
 * `content://<authority>/artwork/<sha256>` URI that the FileProvider
 * declared in the manifest will hand out. Returns null when the
 * passed URI doesn't fall under our artwork directory (Subsonic
 * https://, an outside file:// path, etc.) so callers can fall back
 * to passing the original URI through unchanged.
 *
 * The MediaSession framework auto-grants temporary read permission
 * on content:// URIs travelling through MediaMetadata to whichever
 * controller pulled them, so AA, BT-AVRCP, and the system lock
 * screen can fetch without us calling [Context.grantUriPermission]
 * per-controller.
 */
private fun artworkContentUri(appContext: Context, fileUri: String): android.net.Uri? {
    if (!fileUri.startsWith("file://")) return null
    val path = android.net.Uri.parse(fileUri).path ?: return null
    val artworkDir = java.io.File(appContext.filesDir, "artwork").absolutePath
    val file = java.io.File(path)
    if (!file.absolutePath.startsWith(artworkDir)) return null
    if (!file.exists()) return null
    val contentUri = runCatching {
        androidx.core.content.FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
    }.onFailure {
        Log.e(TAG_ART, "FileProvider.getUriForFile failed for $path", it)
    }.getOrNull() ?: return null

    // Belt-and-suspenders URI grant. The MediaSession framework is
    // documented to auto-grant URI permissions on metadata to the
    // controller's package, but in practice the auto-grant misses
    // some Auto / AVRCP / lock-screen process boundaries — leaving
    // the consumer with a `content://` URI it gets SecurityException
    // on. Granting up-front to the well-known media-consumer
    // packages bypasses that.
    //
    // Revoke the previous track's grants before issuing new ones.
    // grantUriPermission entries persist in UriGrantsManagerService's
    // in-memory list (and the on-disk grant table) until explicit
    // revoke or process death, with no expiry. Without revocation,
    // a long shuffle/queue session would accumulate one grant per
    // package per track switch and eventually trip the framework's
    // grant-table size guard.
    val previous = lastGrantedArtworkUri.getAndSet(contentUri)
    if (previous != null && previous != contentUri) {
        for (pkg in MEDIA_CONSUMER_PACKAGES) {
            runCatching {
                appContext.revokeUriPermission(
                    pkg,
                    previous,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }
    // Each grant is independent: Android lets you grant a URI to a
    // package that doesn't exist on the device (no-op), so we don't
    // need to feature-check whether the user has Auto installed.
    for (pkg in MEDIA_CONSUMER_PACKAGES) {
        runCatching {
            appContext.grantUriPermission(
                pkg,
                contentUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }
    return contentUri
}

// Tracks the most recently granted artwork URI so it can be revoked
// before the next grant. AtomicReference because toMediaItem may run
// on the binder thread (controller IPC) while a parallel track
// transition runs on the player thread.
private val lastGrantedArtworkUri = java.util.concurrent.atomic.AtomicReference<android.net.Uri?>()

private val MEDIA_CONSUMER_PACKAGES = listOf(
    "com.google.android.projection.gearhead",   // Android Auto
    "com.google.android.bluetooth",             // Bluetooth AVRCP cover-art
    "com.android.bluetooth",                    // Bluetooth (alt package name)
    "com.android.systemui",                     // System lock screen / notifications
    "com.google.android.gms",                   // Google Play Services bridge
)

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
