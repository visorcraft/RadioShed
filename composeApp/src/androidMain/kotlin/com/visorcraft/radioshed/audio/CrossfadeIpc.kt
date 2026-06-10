package com.visorcraft.radioshed.audio

import android.os.Bundle

/**
 * Wire protocol for the crossfade / gapless control channel between the in-app
 * [AudioEngineAndroid] (a `MediaController` proxy) and
 * [RadioShedMediaSessionService] (which owns the real ExoPlayer pair).
 *
 * The proxy can't drive a second ExoPlayer directly — the real players live in
 * the service's process — so `setUpNext` (preload) and `crossfadeTo` (run the
 * overlap) are forwarded as `SessionCommand`s carrying a flat [Bundle]. Both
 * sides reference these constants so the action ids and extra keys can't
 * silently drift.
 */
internal object CrossfadeIpc {
    const val ACTION_SET_UP_NEXT = "com.visorcraft.radioshed.SET_UP_NEXT"
    const val ACTION_CROSSFADE_TO = "com.visorcraft.radioshed.CROSSFADE_TO"
    const val ACTION_CANCEL_CROSSFADE = "com.visorcraft.radioshed.CANCEL_CROSSFADE"

    const val EXTRA_CROSSFADE_DURATION_MS = "xfade.durationMs"
    const val EXTRA_CROSSFADE_CURVE = "xfade.curve"

    /** Effective volume (user × ReplayGain) the incoming track should land at,
     *  so the ramp doesn't strand it at raw 1.0 and bypass both. Defaults to 1f
     *  on the read side when absent (older callers / no attenuation). */
    const val EXTRA_CROSSFADE_TARGET_VOLUME = "xfade.targetVolume"

    /** Flat Playable fields for up-next / crossfade-target items. */
    const val EXTRA_ITEM_URI = "item.uri"
    const val EXTRA_ITEM_TRACK_ID = "item.trackId"
    const val EXTRA_ITEM_TITLE = "item.title"
    const val EXTRA_ITEM_ARTIST = "item.artist"
    const val EXTRA_ITEM_ALBUM = "item.album"
    const val EXTRA_ITEM_ARTWORK_URI = "item.artworkUri"
    const val EXTRA_ITEM_CUE_START_MS = "item.cueStartMs"
    const val EXTRA_ITEM_CUE_END_MS = "item.cueEndMs"

    /** Pack a [Playable]'s decode-relevant fields into a [Bundle]. Negative
     *  cue sentinels mean "no clip". */
    fun bundleForItem(playable: Playable): Bundle = Bundle().apply {
        putString(EXTRA_ITEM_URI, playable.uri)
        putLong(EXTRA_ITEM_TRACK_ID, playable.trackId)
        putString(EXTRA_ITEM_TITLE, playable.title)
        putString(EXTRA_ITEM_ARTIST, playable.artist)
        putString(EXTRA_ITEM_ALBUM, playable.album)
        putString(EXTRA_ITEM_ARTWORK_URI, playable.artworkUri)
        putLong(EXTRA_ITEM_CUE_START_MS, playable.cueOffsetStartMs ?: -1L)
        putLong(EXTRA_ITEM_CUE_END_MS, playable.cueOffsetEndMs ?: -1L)
    }
}
