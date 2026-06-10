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
     * throw on engine failure — DJ speech is decorative, never fatal.
     */
    suspend fun synthesizeToFile(text: String, voice: DjVoice?, outPath: String): Boolean
}

class NullTtsEngine : TtsEngine {
    override fun isAvailable(): Boolean = false
    override suspend fun synthesizeToFile(
        text: String,
        voice: DjVoice?,
        outPath: String,
    ): Boolean = false
}
