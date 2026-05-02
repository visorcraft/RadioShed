package com.visorcraft.radioshed.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * v1 stub for iOS. Real implementation lands when Mac access is available
 * and will wrap `AVAudioEngine` + `AVAudioPlayerNode` for gapless playback,
 * `AVAudioSession` for audio focus, `MPNowPlayingInfoCenter` for the lock
 * screen / Control Center, and `MPRemoteCommandCenter` for transport
 * controls (CarPlay's `CPNowPlayingTemplate` plumbs through this too).
 *
 * For now, all transport methods are no-ops so the framework links cleanly
 * and Compose UI on iOS doesn't crash when injecting AudioEngine.
 */
class AudioEngineIos : AudioEngine {

    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _nowPlaying = MutableStateFlow<Playable?>(null)
    override val nowPlaying: StateFlow<Playable?> = _nowPlaying.asStateFlow()

    private val _position = MutableStateFlow(Position(0L, 0L))
    override val position: StateFlow<Position> = _position.asStateFlow()

    override fun play(playable: Playable) {
        // TODO: Phase 4 part 2 — AVAudioEngine.attach(playerNode).schedule(...)
        _state.value = PlayerState.Idle
    }

    override fun playQueue(items: List<Playable>, startIndex: Int) = Unit
    override fun pause() = Unit
    override fun resume() = Unit
    override fun togglePlayPause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun skipNext() = Unit
    override fun skipPrevious() = Unit
    override fun stop() = Unit
    override fun setVolume(fraction: Float) = Unit
}
