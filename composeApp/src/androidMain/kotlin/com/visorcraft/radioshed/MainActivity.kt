package com.visorcraft.radioshed

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

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
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
