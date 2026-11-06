package com.visorcraft.radioshed.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame

/**
 * Show an AWT [FileDialog] from a coroutine and return the
 * user's choice as `(directory, filename)` once the dialog closes.
 *
 * Why this isn't inlined into each caller: `FileDialog.isVisible = true`
 * is a blocking call that must be issued from the AWT Event Dispatch
 * Thread. In Compose Desktop, the EDT is what the runtime exposes as
 * `Dispatchers.Main`. Calling `isVisible = true` from `Dispatchers.IO`
 * (where we land via `IODispatcher`) deadlocks on Windows: the dialog
 * needs the EDT to pump paint events to the OS dialog, but the EDT
 * is parked waiting for the IO-suspend to complete because the JVM
 * tries to maintain modal-ordering invariants.
 *
 * Returns `(null, null)` if the user cancels.
 *
 * The IO-bound work (file copy / write) is the caller's problem —
 * keep it outside this helper so the EDT is freed as soon as the
 * dialog returns.
 */
internal suspend fun showSaveDialog(
    title: String,
    suggestedFileName: String,
): Pair<String?, String?> =
    withContext(Dispatchers.Main) {
        val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
        dialog.file = suggestedFileName
        dialog.isVisible = true
        dialog.directory to dialog.file
    }

