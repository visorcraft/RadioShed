package com.visorcraft.radioshed.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.visorcraft.radioshed.domain.DjVoice
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
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
 *
 * [TextToSpeech.shutdown] is intentionally omitted — this is a Koin single bound
 * for app lifetime; Android reclaims the service connection on process exit.
 */
class AndroidTtsEngine(private val context: Context) : TtsEngine {

    private val initMutex = Mutex()

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

            // setSpeechRate/setPitch are engine-global: concurrent synthesizeToFile calls with
            // differing voice params would race. Acceptable — the DJ pipeline issues one synthesis
            // at a time.
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

            // Guard against cancellation leaking the map entry: the TTS callback will
            // never fire for a coroutine that was cancelled, so remove the entry here.
            try {
                resultDeferred.await()
            } catch (e: CancellationException) {
                utteranceListeners.remove(utteranceId)
                throw e
            }
        }.getOrDefault(false)

    /**
     * Ensures [TextToSpeech] is initialised exactly once, even under concurrent callers.
     * Returns the engine on success, null on failure.
     *
     * The lock is held only long enough to construct the engine and deferred once;
     * the actual [CompletableDeferred.await] happens outside the lock so concurrent
     * callers co-suspend on the same deferred without serialising behind the mutex
     * for the full 100–500 ms TTS init duration.
     */
    private suspend fun awaitInit(): TextToSpeech? {
        // Fast path: already initialised.
        if (initSucceeded == true) return tts
        if (initSucceeded == false) return null

        val deferred = initMutex.withLock {
            // Double-checked inside the lock.
            if (initSucceeded == true) return tts
            if (initSucceeded == false) return null

            if (initDeferred == null) {
                val d = CompletableDeferred<Boolean>()
                initDeferred = d

                val engine = TextToSpeech(context) { status ->
                    val ok = status == TextToSpeech.SUCCESS
                    initSucceeded = ok
                    d.complete(ok)
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

            initDeferred!!
        }

        return if (deferred.await()) tts else null
    }

    /** Called from the TTS internal thread — must not block. */
    private fun completeUtterance(utteranceId: String, success: Boolean) {
        utteranceListeners.remove(utteranceId)?.complete(success)
    }
}
