package com.visorcraft.radioshed.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.visorcraft.radioshed.MainActivity

/**
 * Shared helpers used by both [NowPlayingWidget] and [StationLauncherWidget]
 * to avoid duplicating PendingIntent construction and artwork bitmap decoding.
 */
internal object WidgetSupport {

    const val ARTWORK_TARGET_PX = 256

    /**
     * Decode a file-backed artwork URI to a Bitmap scaled to [ARTWORK_TARGET_PX].
     * Returns null on any failure (missing file, bad URI, decode error).
     *
     * The caller owns the returned Bitmap and must recycle it after the
     * RemoteViews have been applied via [android.appwidget.AppWidgetManager.updateAppWidget].
     */
    fun decodeArtworkBitmap(uriString: String?): Bitmap? {
        if (uriString.isNullOrBlank()) return null
        return runCatching {
            val parsed = Uri.parse(uriString)
            val path = parsed.path ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while ((bounds.outWidth / sample) > ARTWORK_TARGET_PX ||
                (bounds.outHeight / sample) > ARTWORK_TARGET_PX
            ) {
                sample *= 2
            }
            val decode = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, decode)
        }.getOrNull()
    }

    /**
     * Build a [PendingIntent] that opens [MainActivity].
     *
     * When [stationId] is non-null the extra [EXTRA_STATION_ID] is attached so
     * that [MainActivity] can start playback of that station immediately.
     *
     * [requestCode] must be unique per widget / per slot so that PendingIntents
     * for different stations do not overwrite each other.
     */
    fun openAppIntent(
        context: Context,
        requestCode: Int,
        stationId: Long? = null,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (stationId != null) {
                putExtra(EXTRA_STATION_ID, stationId)
            }
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    const val EXTRA_STATION_ID = "radioshed.station_id"
}
