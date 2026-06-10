package com.visorcraft.radioshed.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Android [MediaPlayer]-backed player for short synthesized DJ clips.
 *
 * [playFile] suspends until the clip finishes (or fails) and always releases
 * the underlying [MediaPlayer] — on completion, on error, and on coroutine
 * cancellation. A clip failure is a silent no-op: DJ speech is decorative,
 * never fatal, so every path is wrapped in [runCatching] and the function
 * returns normally instead of propagating.
 *
 * The listener→coroutine bridge here is deliberately NOT shared with
 * AndroidTtsEngine: that one fans out a single engine-wide
 * UtteranceProgressListener across many in-flight utterances keyed by id,
 * whereas this owns one short-lived MediaPlayer per call. Forcing a common
 * helper would couple two different lifetimes for no real reuse.
 */
class AndroidClipPlayer : ClipPlayer {

    override suspend fun playFile(path: String) {
        runCatching {
            suspendCancellableCoroutine { continuation ->
                val player = MediaPlayer()

                // Release exactly once even if completion and an error race.
                var released = false
                fun release() {
                    if (released) return
                    released = true
                    runCatching { player.release() }
                }

                // Cancellation (e.g. the announcer scope tearing down) must
                // also release the native player, otherwise it leaks until GC.
                continuation.invokeOnCancellation { release() }

                runCatching {
                    player.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    player.setOnCompletionListener {
                        release()
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    // Resume (never resumeWithException) so a decode/IO failure
                    // is a silent no-op rather than an exception the caller's
                    // runCatching would have to swallow anyway.
                    player.setOnErrorListener { _, _, _ ->
                        release()
                        if (continuation.isActive) continuation.resume(Unit)
                        true
                    }
                    player.setDataSource(path)
                    player.setOnPreparedListener { it.start() }
                    player.prepareAsync()
                }.onFailure {
                    // setDataSource / prepareAsync can throw synchronously
                    // (bad path, IllegalState) before any listener fires —
                    // resume so the caller never hangs waiting on a player
                    // that will never call back.
                    release()
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    }
}
