package com.visorcraft.radioshed.audio

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Foreground media-playback service. Hosts the actual [ExoPlayer] and
 * [MediaSession]; the in-app [AudioEngineAndroid] talks to this service
 * via a `MediaController`, so playback survives configuration changes
 * and the OS handles foreground promotion + notification automatically.
 *
 * Audio attributes:
 *  - USAGE_MEDIA + CONTENT_TYPE_MUSIC so the system treats us as a music
 *    app (matters for ducking, BT routing, Do-Not-Disturb interaction).
 *  - handleAudioFocus = true so we honor incoming calls / alarms.
 *  - handleAudioBecomingNoisy = true so unplugging headphones pauses
 *    instead of blasting through the speaker.
 *
 * Manifest: this service is registered with intent-filter
 * `androidx.media3.session.MediaSessionService` and foreground-service
 * type `mediaPlayback`.
 */
class RadioShedMediaSessionService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // Standard music-app behavior: if music is actively playing when the
        // user swipes the task from recents, leave the service running so
        // playback continues with the notification as the entry-point back
        // in. If playback is paused (or there's no player), tear down so we
        // don't leak a non-functional notification.
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady) {
            player?.stop()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
