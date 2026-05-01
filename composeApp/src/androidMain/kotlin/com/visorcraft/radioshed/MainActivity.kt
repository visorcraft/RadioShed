package com.visorcraft.radioshed

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.Playable
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val audioEngine: AudioEngine by inject()

    /**
     * On Android 13+ (API 33), the foreground media notification posted by
     * [androidx.media3.session.MediaSessionService] requires an explicit
     * `POST_NOTIFICATIONS` runtime grant. Without it, playback works but
     * the lock-screen / notification / Bluetooth-AVRCP transport surface
     * is invisible. Result ignored — if the user denies, music still plays
     * (just without the lock-screen UI), and Library Settings re-shows the
     * grant flow only for storage.
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Intentionally no-op: Media3 will skip posting the notification
            // gracefully if the permission isn't granted.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPostNotificationsIfNeeded()

        setContent {
            App()
        }

        // Cold-start path: the activity was launched via VIEW intent (e.g.
        // user tapped an .mp3 in a file manager). Handle here because
        // setContent ⇢ Koin ⇢ AudioEngine is now ready.
        handleViewIntent(intent)
    }

    /**
     * Warm path: RadioShed was already running and the user tapped another
     * audio file. With launchMode=singleTask the existing instance is reused
     * and onNewIntent fires instead of onCreate. Without this, subsequent
     * "Open with RadioShed" taps would no-op.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    /**
     * If the activity was launched/resumed via [Intent.ACTION_VIEW] with an
     * audio URI (file:// or content://), play it ad-hoc. The track isn't
     * added to the library — that's a Library Settings → scan flow. This is
     * just "tap a file → it plays in RadioShed."
     *
     * For content:// URIs we need temporary read access; the system grants
     * it for the lifetime of the activity automatically because of the
     * `<intent-filter>` declaration. Media3's underlying ExoPlayer reads via
     * ContentResolver, which respects that grant.
     */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri: Uri = intent.data ?: return

        // Best-effort title from the URI's last path segment, dropping the
        // file extension. Real metadata would require a sync read of the
        // file's tags — skip for ad-hoc playback to keep tap-to-play instant.
        val title = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "Unknown"

        audioEngine.play(
            Playable(
                trackId = AD_HOC_TRACK_ID,
                uri = uri.toString(),
                title = title,
                artist = null,
                album = null,
                durationMs = 0L,
            ),
        )
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val alreadyGranted = ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
        if (!alreadyGranted) {
            notificationPermissionLauncher.launch(permission)
        }
    }

    private companion object {
        /**
         * Sentinel track id for ad-hoc playback (a file opened via
         * Intent.ACTION_VIEW that isn't in the indexed library). Negative so
         * it can never collide with a real `track.id` (auto-increment > 0).
         * Repos that take a track id (Rating, Skip, etc.) should treat this
         * as a no-op or "not in library."
         */
        const val AD_HOC_TRACK_ID = -1L
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
