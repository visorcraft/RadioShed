package com.visorcraft.radioshed.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Shared `CoroutineExceptionHandler` factory for the app's long-lived
 * scopes (audio engines, MediaSessionService, sleep timer, etc.).
 *
 * Without a handler installed on a scope's context, any uncaught
 * exception inside a coroutine launched on that scope propagates to
 * the JVM's default uncaught-exception handler, which on Windows
 * produces "RadioShed has stopped working" (a Win32 message box that
 * doesn't respect our Material theme), and on Android shows the
 * system "Keeps stopping" dialog. Both are exactly the OS-default
 * popups we want to avoid.
 *
 * The handler:
 *  - Re-throws [CancellationException]. Cancellation is the normal
 *    structured-concurrency control-flow signal — swallowing it
 *    would break parent-child propagation of cancel semantics.
 *  - Otherwise logs (println + stack trace) and swallows. Callers
 *    that want to surface the failure in the UI should compose with
 *    a `StateFlow<String?>` alongside this handler (see
 *    `StationPlayback._playbackError` for the canonical pattern).
 *
 * Pass a [tag] so logged messages are attributable to a specific
 * subsystem when reading logcat / stderr.
 */
internal fun crashGuard(tag: String): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, throwable ->
        if (throwable is CancellationException) throw throwable
        val message = throwable.message ?: throwable::class.simpleName ?: "unknown"
        println("$tag: unhandled exception — $message")
        throwable.printStackTrace()
    }
