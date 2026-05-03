package com.visorcraft.radioshed.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.KeyEvent
import android.widget.RemoteViews
import com.visorcraft.radioshed.MainActivity
import com.visorcraft.radioshed.R
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.PlayerState
import com.visorcraft.radioshed.audio.RadioShedMediaSessionService
import org.koin.core.context.GlobalContext

/**
 * Home-screen widget showing the currently-playing track + transport
 * controls. The widget runs in the system process via RemoteViews,
 * but RadioShed's AppWidgetProvider class lives in our APK and can
 * Koin-inject the AudioEngine since it's a regular BroadcastReceiver
 * inside our own application classloader.
 *
 * Updates are pushed via [pushUpdate] from the playback service when
 * the now-playing track or play state changes — no polling. The
 * widget treats `updatePeriodMillis="0"` in its info XML, so the
 * system never auto-refreshes; we own the cadence.
 *
 * Transport buttons synthesize media-button KeyEvents and broadcast
 * them to the [RadioShedMediaSessionService]. Media3 routes those
 * back through the same code path the lock-screen / Bluetooth
 * controls use, so we don't need to duplicate any business logic.
 */
class NowPlayingWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // Trigger a fresh paint for every widget instance the user
        // has on their home screen. The state read pulls live data
        // from the AudioEngine StateFlow's current value — no
        // suspend / no async, so we can run inline on the receiver
        // thread.
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    companion object {
        /**
         * Target dimension for widget artwork decoding. The persister
         * stores at ~500 px; the widget cell is ~128 dp at most.
         * Decoding to 256 px is more than enough headroom for any
         * scaled-up cell on a 3-4x density display, and ~16x cheaper
         * memory than the full-size source.
         */
        private const val ARTWORK_TARGET_PX = 256

        /**
         * Force a one-shot update on every existing widget instance.
         * Called from [RadioShedMediaSessionService] whenever the
         * now-playing track or play/pause state changes.
         */
        fun pushUpdate(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, NowPlayingWidget::class.java)
            val ids = runCatching { mgr.getAppWidgetIds(component) }
                .getOrNull() ?: IntArray(0)
            for (id in ids) updateWidget(context, mgr, id)
        }

        private fun updateWidget(
            context: Context,
            mgr: AppWidgetManager,
            widgetId: Int,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_now_playing)

            // Pull live state from the engine via Koin. The widget
            // process IS our app process, so the singletons are the
            // same instances the rest of the app sees.
            val (title, subtitle, isPlaying, artworkUri) = runCatching {
                val engine = GlobalContext.get().get<AudioEngine>()
                val playable = engine.nowPlaying.value
                val playing = engine.state.value == PlayerState.Playing
                Snapshot(
                    title = playable?.title?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.widget_idle_title),
                    subtitle = playable?.let {
                        listOfNotNull(it.artist, it.album).joinToString(" · ")
                            .ifBlank { null }
                    } ?: context.getString(R.string.widget_idle_subtitle),
                    isPlaying = playing,
                    artworkUri = playable?.artworkUri,
                )
            }.getOrDefault(idleSnapshot(context))

            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_subtitle, subtitle)
            views.setImageViewResource(
                R.id.widget_play_pause,
                if (isPlaying) R.drawable.ic_radioshed_pause
                else R.drawable.ic_radioshed_play,
            )

            // Artwork: load from file:// URI to a Bitmap if present;
            // fall back to the launcher icon. Decoded at widget-cell
            // size (~128 px) rather than the persister's full ~500 px
            // — RemoteViews serialises the Bitmap into a Parcel for
            // IPC, so a 1MB full-size bitmap on every state tick is
            // wasteful and on low-memory devices can OOM the widget
            // host.
            val artworkBitmap = artworkUri?.let { uri ->
                runCatching {
                    val parsed = Uri.parse(uri)
                    val path = parsed.path ?: return@runCatching null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    val targetPx = ARTWORK_TARGET_PX
                    var sample = 1
                    while ((bounds.outWidth / sample) > targetPx ||
                        (bounds.outHeight / sample) > targetPx) {
                        sample *= 2
                    }
                    val decode = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeFile(path, decode)
                }.getOrNull()
            }
            if (artworkBitmap != null) {
                views.setImageViewBitmap(R.id.widget_artwork, artworkBitmap)
                // RemoteViews serialises the bitmap into a Parcel for
                // IPC, so the host process gets its own copy. We can
                // drop our reference here — without the recycle, a
                // burst of pushUpdate calls (bluetooth reconnect, rapid
                // skip-next) leaves the widget process leaking ~100KB
                // of bitmap heap per call until GC catches up.
                artworkBitmap.recycle()
            } else {
                views.setImageViewResource(
                    R.id.widget_artwork,
                    R.drawable.ic_radioshed_notification,
                )
            }

            // Tap-anywhere → open the app at MainActivity. The
            // background of the root LinearLayout doesn't have a
            // pendingIntent on its own; setOnClickPendingIntent on
            // the root fires for any tap that isn't on a specific
            // child handled below.
            views.setOnClickPendingIntent(
                R.id.widget_root,
                openAppIntent(context),
            )

            // Transport buttons → media-button broadcasts to the
            // session service. Media3's MediaSessionService routes
            // them back through its onMediaButtonEvent which we've
            // already overridden to handle multi-click etc.
            views.setOnClickPendingIntent(
                R.id.widget_play_pause,
                mediaButtonIntent(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 1),
            )
            views.setOnClickPendingIntent(
                R.id.widget_next,
                mediaButtonIntent(context, KeyEvent.KEYCODE_MEDIA_NEXT, 2),
            )
            views.setOnClickPendingIntent(
                R.id.widget_prev,
                mediaButtonIntent(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS, 3),
            )

            mgr.updateAppWidget(widgetId, views)
        }

        private fun idleSnapshot(context: Context): Snapshot = Snapshot(
            title = context.getString(R.string.widget_idle_title),
            subtitle = context.getString(R.string.widget_idle_subtitle),
            isPlaying = false,
            artworkUri = null,
        )

        private fun openAppIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            return PendingIntent.getActivity(
                context,
                /* requestCode = */ 0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun mediaButtonIntent(
            context: Context,
            keyCode: Int,
            requestCode: Int,
        ): PendingIntent {
            // Target the session service directly so the broadcast
            // reaches MediaSessionService.onMediaButtonEvent rather
            // than the system media-button receiver (which routes
            // to whichever foreground media app the system picked,
            // not necessarily us).
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                component = ComponentName(context, RadioShedMediaSessionService::class.java)
                putExtra(
                    Intent.EXTRA_KEY_EVENT,
                    KeyEvent(KeyEvent.ACTION_DOWN, keyCode),
                )
            }
            return PendingIntent.getService(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }

    private data class Snapshot(
        val title: String,
        val subtitle: String,
        val isPlaying: Boolean,
        val artworkUri: String?,
    )
}
