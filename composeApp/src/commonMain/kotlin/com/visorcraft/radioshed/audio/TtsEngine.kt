package com.visorcraft.radioshed.audio

import com.visorcraft.radioshed.domain.DjVoice

/**
 * Text-to-speech synthesis for DJ announcements. Platform impls render
 * [text] to a playable audio file at [outPath] (wav/m4a — caller treats it
 * as opaque). Bound in platformModule; desktop and iOS ship Null until a
 * platform path exists, so all callers must tolerate isAvailable() == false.
 */
interface TtsEngine {
    fun isAvailable(): Boolean

    /**
     * Synthesize [text] to [outPath]. Returns true on success. Must not
     * throw on engine failure — DJ speech is decorative, never fatal — but
     * must let CancellationException propagate like any suspend function so
     * cancelled callers stop instead of reading a bogus `false`.
     *
     * [outPath] must be an absolute path in an app-writable directory (e.g. the DJ
     * cache dir); the caller guarantees the parent exists.
     */
    suspend fun synthesizeToFile(text: String, voice: DjVoice?, outPath: String): Boolean

    /**
     * Absolute path of an app-writable directory the DJ pipeline may write
     * synthesized clips into, or null when no writable location exists (the
     * Null impl). Implementations create the directory on first call so the
     * synthesize caller can rely on the parent existing. Lives here because
     * the engine already owns the only platform handle (Android `Context`)
     * needed to resolve private storage — keeping the announcer platform-free.
     */
    fun cacheDir(): String?
}

class NullTtsEngine : TtsEngine {
    override fun isAvailable(): Boolean = false
    override suspend fun synthesizeToFile(
        text: String,
        voice: DjVoice?,
        outPath: String,
    ): Boolean = false

    override fun cacheDir(): String? = null
}

/** Plays a short synthesized clip (DJ announcement). Suspends until playback completes. */
interface ClipPlayer {
    suspend fun playFile(path: String)
}

class NullClipPlayer : ClipPlayer {
    override suspend fun playFile(path: String) {}
}
