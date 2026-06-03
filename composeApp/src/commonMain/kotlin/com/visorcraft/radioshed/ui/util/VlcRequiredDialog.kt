package com.visorcraft.radioshed.ui.util

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.visorcraft.radioshed.source.openWebUrl

/**
 * Desktop "VLC required" modal: shown when the system libvlc isn't
 * installed (desktop only — Android/iOS report media playback as always
 * available). Dismissible; triggered both at startup and on a play attempt
 * that fails because libvlc is absent. Wired in `App` off
 * `isMediaPlaybackAvailable`.
 *
 * [onGetVlc] opens videolan.org in the user's browser; the caller dismisses
 * after invoking it. [onDismiss] backs the "Continue" button and the
 * scrim/back dismissal.
 */
@Composable
fun VlcRequiredDialog(
    onDismiss: () -> Unit,
    onGetVlc: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("VLC required") },
        text = {
            Text(
                "Media cannot be played until VLC is installed.\n\n" +
                    "RadioShed uses VLC's libvlc engine for desktop playback. " +
                    "Install VLC, then restart RadioShed.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onGetVlc()
                    onDismiss()
                },
            ) {
                Text("Get VLC")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Continue")
            }
        },
    )
}

/** videolan.org VLC download landing page used by [VlcRequiredDialog]. */
fun openGetVlcPage() {
    openWebUrl("https://www.videolan.org/vlc/")
}
