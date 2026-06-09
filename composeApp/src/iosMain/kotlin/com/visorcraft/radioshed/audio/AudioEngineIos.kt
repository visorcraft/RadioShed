package com.visorcraft.radioshed.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFoundation.*
import platform.MediaPlayer.*
import platform.Foundation.*
import platform.CoreMedia.*
import kotlinx.cinterop.*
import platform.UIKit.*

/**
 * Implementation of [AudioEngine] for iOS using `AVAudioEngine` and `AVAudioPlayerNode`.
 * This implementation handles gapless playback, audio focus via `AVAudioSession`,
 * and integrates with `MPNowPlayingInfoCenter` and `MPRemoteCommandCenter`
 * to provide lock-screen controls and CarPlay support.
 */
class AudioEngineIos : AudioEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionJob: Job? = null

    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _nowPlaying = MutableStateFlow<Playable?>(null)
    override val nowPlaying: StateFlow<Playable?> = _nowPlaying.asStateFlow()

    private val _position = MutableStateFlow(Position(0L, 0L))
    override val position: StateFlow<Position> = _position.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    override val volume: StateFlow<Float> = _volume.asStateFlow()

    private var wasInterrupted = false
    private var playlist = mutableListOf<Playable>()
    private var currentIndex = 0
    private var playbackStartTimeMs: Long = 0L

    private val engine = AVAudioEngine()
    private val playerNode = AVAudioPlayerNode()
    private val pitchNode = AVAudioUnitTimePitch()
    private val session = AVAudioSession.sharedInstance()

    init {
        setupRemoteCommands()
        configureAudioSession()
        setupInterruptionHandling()
        setupRouteChangeHandling()
    }

    private fun configureAudioSession() {
        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            session.setCategory(AVAudioSessionCategoryPlayback, error = errorPtr.ptr)
            if (errorPtr.value != null) {
                _state.value = PlayerState.Error("Failed to set audio session category: ${errorPtr.value?.localizedDescription}")
                return
            }

            session.setActive(true, error = errorPtr.ptr)
            if (errorPtr.value != null) {
                _state.value = PlayerState.Error("Failed to activate audio session: ${errorPtr.value?.localizedDescription}")
                return
            }
        }
    }

    private fun setupRemoteCommands() {
        val commandCenter = MPRemoteCommandCenter.commandCenter()
        commandCenter.playCommand.setMusicPlayerCommandHandler {
            resume()
            MPRemoteCommandEventResult.success()
        }
        commandCenter.pauseCommand.setMusicPlayerCommandHandler {
            pause()
            MPRemoteCommandEventResult.success
        }
        commandCenter.nextTrackCommand.setMusicPlayerCommandHandler {
            skipNext()
            MPRemoteCommandEventResult.success
        }
        commandCenter.previousTrackCommand.setMusicPlayerCommandHandler {
            skipPrevious()
            MPRemoteCommandEventResult.success
        }
    }

    override fun play(playable: Playable) {
        playlist = mutableListOf(playable)
        currentIndex = 0
        _playTrack(playable)
    }

    override fun playQueue(items: List<Playable>, startIndex: Int) {
        if (items.isEmpty()) return
        playlist = items.toMutableList()
        currentIndex = if (startIndex in items.indices) startIndex else 0
        _playTrack(playlist[currentIndex])
    }

    private fun _playTrack(playable: Playable, isNewPlayback: Boolean = true) {
        if (!isNewPlayback && _state.value == PlayerState.Playing) {
            // The next track was already scheduled by the previous _playTrack call for gapless playback.
            _nowPlaying.value = playable
            updateNowPlayingMetadata(playable)
            startPositionPolling()
            return
        }

        _nowPlaying.value = playable
        _state.value = PlayerState.Buffering

        try {
            val url = NSURL.fileURLWithPath(playable.uri)
            val file = AVURLAsset(uRL = url, options = null)

            engine.attachNode(playerNode)
            engine.attachNode(pitchNode)
            engine.connect(playerNode, to = pitchNode, format = null)
            engine.connect(pitchNode, to = engine.mainMixerNode, format = null)

            playerNode.scheduleFile(file, atTime = kCMTimeZero) {
                if (_state.value == PlayerState.Playing) {
                    handleTrackEnded()
                }
            }

            // Gapless playback: schedule the next track in advance if it exists and we are starting a new playback
            if (isNewPlayback && currentIndex < playlist.size - 1) {
                val nextPlayable = playlist[currentIndex + 1]
                val nextUrl = NSURL.fileURLWithPath(nextPlayable.uri)
                val nextFile = AVURLAsset(uRL = nextUrl, options = null)
                playerNode.scheduleFile(nextFile, atTime = kCMTimeZero) {
                    if (_state.value == PlayerState.Playing) {
                        handleTrackEnded()
                    }
                }
            }

            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                if (!engine.startAndReturnError(errorPtr.ptr)) {
                    _state.value = PlayerState.Error("Failed to start audio engine: ${errorPtr.value?.localizedDescription}")
                    return
                }
            }
            playerNode.play()

            updateNowPlayingMetadata(playable)
            startPositionPolling()
            _state.value = PlayerState.Playing
        } catch (e: Exception) {
            _state.value = PlayerState.Error(e.message ?: "Unknown error during playback")
        }
    }

    private fun handleTrackEnded() {
        if (currentIndex < playlist.size - 1) {
            // The next track is already scheduled by the previous _playTrack call for gapless playback.
            currentIndex++
            val nextPlayable = playlist[currentIndex]
            _nowPlaying.value = nextPlayable
            updateNowPlayingMetadata(nextPlayable)
            startPositionPolling()
        } else {
            stop()
        }
    }

    override fun pause() {
        playerNode.pause()
        _state.value = PlayerState.Paused
    }

    override fun resume() {
        playerNode.play()
        _state.value = PlayerState.Playing
    }

    override fun togglePlayPause() {
        if (_state.value == PlayerState.Playing) pause() else resume()
    }

    override fun seekTo(positionMs: Long) {
        val time = CMTimeMake(value = positionMs, timescale = 1000)
        playerNode.seek(toTime = time)
        _position.value = _position.value.copy(currentMs = positionMs)
    }

    override fun skipNext() {
        if (currentIndex < playlist.size - 1) {
            playerNode.stop() // Clear the queue to avoid overlapping with pre-scheduled tracks
            currentIndex++
            _playTrack(playlist[currentIndex], isNewPlayback = true)
        } else {
            stop()
        }
    }

    override fun skipPrevious() {
        if (currentIndex > 0) {
            playerNode.stop() // Clear the queue to avoid overlapping with pre-scheduled tracks
            currentIndex--
            _playTrack(playlist[currentIndex], isNewPlayback = true)
        } else {
            seekTo(0L)
        }
    }

    override fun stop() {
        playerNode.stop()
        engine.stopAndReturnError(null)
        _state.value = PlayerState.Idle
        _nowPlaying.value = null
        positionJob?.cancel()
        resetPosition()
        playlist.clear()
        currentIndex = 0
    }

    override fun setVolume(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        _volume.value = clamped
        playerNode.volume = clamped
    }

    override fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        pitchNode.rate = clamped
    }

    private fun startPositionPolling() {
        positionJob?.cancel()
        playbackStartTimeMs = System.currentTimeMillis()
        positionJob = scope.launch {
            while (isActive && _state.value == PlayerState.Playing) {
                val elapsed = System.currentTimeMillis() - playbackStartTimeMs
                _position.value = _position.value.copy(currentMs = elapsed)
                delay(250)
            }
        }
    }

    private fun resetPosition() {
        _position.value = Position(0L, 0L)
    }

    private fun updateNowPlayingMetadata(playable: Playable) {
        val infoCenter = MPNowPlayingInfoCenter.defaultPlayableMedia
        val metadata = mutableMapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to playable.title,
            MPMediaItemPropertyArtist to (playable.artist ?: "Unknown Artist"),
            MPMediaItemPropertyAlbum to (playable.album ?: "")
        )

        if (!playable.artworkUri.isNullOrBlank()) {
            val uri = playable.artworkUri
            if (uri.startsWith("file://")) {
                val path = uri.removePrefix("file://")
                val image = UIImage.imageWithContentsOfFile(path)
                if (image != null) {
                    metadata[MPMediaItemPropertyArtwork] = MPMediaItemArtworkImpl(image)
                }
            }
        }

        infoCenter.nowPlayingInfo = metadata as Map<Any?, Any?>
    }

    private fun setupInterruptionHandling() {
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVAudioSessionInterruptionNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
            usingBlock = ::handleInterruption
        )
    }

    private fun handleInterruption(notification: NSNotification) {
        val userInfo = notification.userInfo ?: return
        val typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? Long ?: return

        when (typeValue) {
            AVAudioSessionInterruptionTypeBegan -> {
                if (_state.value == PlayerState.Playing) {
                    wasInterrupted = true
                    pause()
                }
            }
            AVAudioSessionInterruptionTypeEnded -> {
                val optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? Long ?: 0L
                if (optionsValue == AVAudioSessionInterruptionOptionShouldResume && wasInterrupted) {
                    wasInterrupted = false
                    resume()
                } else {
                    wasInterrupted = false
                }
            }
        }
    }

    private fun setupRouteChangeHandling() {
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVAudioSessionRouteChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
            usingBlock = ::handleRouteChange
        )
    }

    private fun handleRouteChange(notification: NSNotification) {
        val userInfo = notification.userInfo ?: return
        val reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? Long ?: return

        when (reasonValue) {
            AVAudioSessionRouteChangeReasonOldDeviceUnavailable -> {
                if (_state.value == PlayerState.Playing) {
                    pause()
                }
            }
            AVAudioSessionRouteChangeReasonMediaServicesReset -> {
                reconfigureAudioEngine()
            }
        }
    }

    private fun reconfigureAudioEngine() {
        scope.launch {
            val wasPlaying = _state.value == PlayerState.Playing
            val playableToResume = if (wasPlaying && currentIndex < playlist.size) playlist[currentIndex] else null

            playerNode.stop()
            stopEngine()
            configureAudioSession()

            if (playableToResume != null) {
                _playTrack(playableToResume)
            }
        }
    }

    private fun stopEngine() {
        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            engine.stopAndReturnError(errorPtr.ptr)
        }
    }

    private class MPMediaItemArtworkImpl(private val image: UIImage) : NSObject(), MPMediaItemArtwork {
        override fun addRemoteContentRequest(request: MPMediaItemArtworkResourceRequest) {}
        override fun addLocalContentRequest(request: MPMediaItemArtworkLocalContentRequest) {}
    }
}