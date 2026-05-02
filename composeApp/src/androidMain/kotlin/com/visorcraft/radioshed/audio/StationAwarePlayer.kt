package com.visorcraft.radioshed.audio

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * [ForwardingPlayer] that overlays our station-playback model on top of
 * a vanilla [ExoPlayer]. Two reasons we need this:
 *
 * 1. **Skip buttons in external surfaces** (Android Auto, lock-screen,
 *    Bluetooth AVRCP, system media controls, Wear OS). They look at
 *    [Player.getAvailableCommands] / [Player.isCommandAvailable] to decide
 *    which transport controls to render. ExoPlayer derives those from
 *    queue position — and our queue always has exactly one item (we feed
 *    tracks one at a time as the previous one ends), so external
 *    surfaces conclude "no next track exists" and hide the skip button.
 *    This wrapper lies and says skip is always available; the resulting
 *    surface UI (Auto's Now Playing screen, the lock-screen mini-player,
 *    Bluetooth AVRCP NEXT command, etc.) shows the button correctly.
 *
 * 2. **Routing skip commands through StationPlayback**. When the user
 *    taps that button, the request goes through `seekToNext` /
 *    `seekToNextMediaItem` (or PREVIOUS variants). Default ExoPlayer
 *    behavior would be a no-op (one-item queue) or rewind to start.
 *    We override to call [onSkipNext] / [onSkipPrevious] which the
 *    service hooks up to [com.visorcraft.radioshed.stations.StationPlayback]
 *    so the rest of the queue-rule logic (smart shuffle, no-repeat
 *    memory, weighting, dislike filtering) kicks in.
 */
class StationAwarePlayer(
    private val underlying: ExoPlayer,
    private val onSkipNext: () -> Unit,
    private val onSkipPrevious: () -> Unit,
) : ForwardingPlayer(underlying) {

    override fun getAvailableCommands(): Player.Commands {
        return super.getAvailableCommands()
            .buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()
    }

    override fun isCommandAvailable(command: Int): Boolean = when (command) {
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
        else -> super.isCommandAvailable(command)
    }

    override fun seekToNext() = onSkipNext()
    override fun seekToNextMediaItem() = onSkipNext()
    override fun seekToPrevious() = onSkipPrevious()
    override fun seekToPreviousMediaItem() = onSkipPrevious()

    /** Whether the underlying ExoPlayer can be released; exposed so the
     *  service can release the real player when it tears down. */
    val realPlayer: ExoPlayer get() = underlying
}
