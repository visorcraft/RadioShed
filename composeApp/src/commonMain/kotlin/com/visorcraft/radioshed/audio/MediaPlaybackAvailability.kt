package com.visorcraft.radioshed.audio

/**
 * Whether the platform can actually play media right now. On desktop this
 * reflects whether the system libvlc was discovered; on Android (Media3)
 * and iOS (AVAudioEngine) playback is always available, so it's `true`.
 *
 * Used by `App` to gate the "VLC required" modal so the desktop-only check
 * carries no UI-side platform branching.
 */
expect fun isMediaPlaybackAvailable(): Boolean
