package com.visorcraft.radioshed.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.visorcraft.radioshed.domain.DjVoice
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android [TextToSpeech]-backed TTS engine for DJ announcements.
 *
 * Lazy init: the underlying [TextToSpeech] instance is constructed on the first
 * [synthesizeToFile] call. Construction is guarded by [initMutex] so concurrent
 * first calls do not double-construct the engine.
 *
 * isAvailable() choice: returns `true` optimistically before the first init
 * attempt — init has no cheap synchronous way to probe the TTS engine state.
 * After init completes, returns the actual init result. Callers should be
 * prepared for [synthesizeToFile] to return `false` if the device TTS engine
 * is unavailable.
 *
 * [synthesizeToFile] never throws — any engine failure or exception yields `false`.
 * DJ speech is decorative, never fatal.
 */
class AndroidTtsEngine(private val context: Context) : TtsEngine {

    private val initMutex = Mutex()
    private val initAttempted = AtomicBoolean(false)

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var initDeferred: CompletableDeferred<Boolean>? = null

    // null = not yet attempted; true/false = outcome of init.
    @Volatile private var initSucceeded: Boolean? = null

    // ConcurrentHashMap so the UtteranceProgressListener (TTS internal thread) can
    // remove entries without blocking the coroutine dispatcher.
    private val utteranceListeners = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    override fun isAvailable(): Boolean {
        // Return actual result if known; otherwise optimistic true (see KDoc).
        return initSucceeded ?: true
    }

    /**
     * Synthesize [text] to an audio file at [outPath].
     *
     * [outPath] must be an absolute path in an app-writable directory (e.g. the DJ
     * cache dir); the caller guarantees the parent exists.
     *
     * Returns false on any failure; never throws.
     */
    override suspend fun synthesizeToFile(text: String, voice: DjVoice?, outPath: String): Boolean =
        runCatching {
            val engine = awaitInit() ?: return@runCatching false

            engine.setSpeechRate((voice?.rate ?: 1.0).toFloat())
            engine.setPitch((voice?.pitch ?: 1.0).toFloat())

            val utteranceId = UUID.randomUUID().toString()
            val resultDeferred = CompletableDeferred<Boolean>()

            // Register BEFORE calling synthesizeToFile so we never miss the completion
            // callback on a fast (cached) engine.
            utteranceListeners[utteranceId] = resultDeferred

            val synthesizeResult = engine.synthesizeToFile(
                text,
                Bundle(),
                File(outPath),
                utteranceId,
            )

            if (synthesizeResult != TextToSpeech.SUCCESS) {
                utteranceListeners.remove(utteranceId)
                return@runCatching false
            }

            resultDeferred.await()
        }.getOrDefault(false)

    /**
     * Ensures [TextToSpeech] is initialised exactly once, even under concurrent callers.
     * Returns the engine on success, null on failure.
     */
    private suspend fun awaitInit(): TextToSpeech? {
        // Fast path: already initialised successfully.
        if (initSucceeded == true) return tts
        if (initSucceeded == false) return null

        initMutex.withLock {
            // Double-checked inside the lock.
            if (initSucceeded == true) return tts
            if (initSucceeded == false) return null

            if (!initAttempted.getAndSet(true)) {
                val deferred = CompletableDeferred<Boolean>()
                initDeferred = deferred

                val engine = TextToSpeech(context) { status ->
                    val ok = status == TextToSpeech.SUCCESS
                    initSucceeded = ok
                    deferred.complete(ok)
                }
                tts = engine

                // Single shared listener; dispatches each event to the matching
                // per-utterance deferred by utteranceId.
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { completeUtterance(it, success = true) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { completeUtterance(it, success = false) }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        utteranceId?.let { completeUtterance(it, success = false) }
                    }
                })
            }

            val ok = initDeferred?.await() ?: (initSucceeded == true)
            return if (ok) tts else null
        }
    }

    /** Called from the TTS internal thread — must not block. */
    private fun completeUtterance(utteranceId: String, success: Boolean) {
        utteranceListeners.remove(utteranceId)?.complete(success)
    }
}
