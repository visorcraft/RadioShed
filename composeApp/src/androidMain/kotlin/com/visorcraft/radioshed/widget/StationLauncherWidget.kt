package com.visorcraft.radioshed.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.visorcraft.radioshed.R
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.data.WidgetConfigRepository
import com.visorcraft.radioshed.widget.WidgetSupport.decodeArtworkBitmap
import com.visorcraft.radioshed.widget.WidgetSupport.openAppIntent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.context.GlobalContext

/**
 * Home-screen widget that displays a grid of station tiles (up to 6).
 * Tapping a tile opens RadioShed and immediately starts playback of that
 * station via the same [MainActivity] intent path that the Now-Playing
 * widget uses.
 *
 * Configuration (which station occupies each slot) is persisted per
 * `appWidgetId` through [WidgetConfigRepository] under the key
 * `"station-launcher:<id>"`. Deleted widgets clean up their rows in
 * [onDeleted].
 *
 * The widget layout is a static 2×3 grid; unused slots are hidden. The
 * 6-tile cap is a RemoteViews practical limit — dynamic grid views would
 * need an adapter-backed RemoteViewsService, which is overkill for a
 * music-station launcher.
 */
class StationLauncherWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val repo = GlobalContext.get().get<WidgetConfigRepository>()
        for (id in appWidgetIds) {
            runBlocking {
                runCatching { repo.delete(widgetKey(id)) }
            }
        }
    }

    companion object {
        const val MAX_SLOTS = 6

        private val SLOT_VIEW_IDS = intArrayOf(
            R.id.widget_slot_0,
            R.id.widget_slot_1,
            R.id.widget_slot_2,
            R.id.widget_slot_3,
            R.id.widget_slot_4,
            R.id.widget_slot_5,
        )
        private val SLOT_ARTWORK_IDS = intArrayOf(
            R.id.widget_slot_artwork_0,
            R.id.widget_slot_artwork_1,
            R.id.widget_slot_artwork_2,
            R.id.widget_slot_artwork_3,
            R.id.widget_slot_artwork_4,
            R.id.widget_slot_artwork_5,
        )
        private val SLOT_NAME_IDS = intArrayOf(
            R.id.widget_slot_name_0,
            R.id.widget_slot_name_1,
            R.id.widget_slot_name_2,
            R.id.widget_slot_name_3,
            R.id.widget_slot_name_4,
            R.id.widget_slot_name_5,
        )

        fun widgetKey(appWidgetId: Int): String = "station-launcher:$appWidgetId"

        /**
         * Force a one-shot update on every existing station-launcher instance.
         * Callers (e.g. the config activity after save, or [App] after station
         * metadata changes) should fire-and-forget — the update is cheap.
         */
        fun pushUpdate(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, StationLauncherWidget::class.java)
            val ids = runCatching { mgr.getAppWidgetIds(component) }
                .getOrNull() ?: IntArray(0)
            for (id in ids) updateWidget(context, mgr, id)
        }

        private fun updateWidget(
            context: Context,
            mgr: AppWidgetManager,
            widgetId: Int,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_station_launcher)

            // Read config from the repository. runBlocking is acceptable here
            // because the DB read is small (single row) and the system expects
            // onUpdate to finish quickly.
            val config = runBlocking {
                runCatching {
                    val repo = GlobalContext.get().get<WidgetConfigRepository>()
                    val entry = repo.get(widgetKey(widgetId))
                    entry?.configJson?.let { Json.decodeFromString<LauncherConfig>(it) }
                }.getOrNull()
            } ?: LauncherConfig()

            val stationRepo = GlobalContext.get().get<StationRepository>()
            val stations = runBlocking {
                runCatching { stationRepo.list() }.getOrDefault(emptyList())
            }
            val stationById = stations.associateBy { it.id }

            // Populate each slot. Slots without a configured station are hidden
            // rather than showing an empty placeholder, which looks cleaner on
            // a partially-configured widget.
            val decodedBitmaps = mutableListOf<android.graphics.Bitmap>()
            for (slot in 0 until MAX_SLOTS) {
                val stationId = config.stationIds.getOrNull(slot)
                val station = stationId?.let { stationById[it] }

                if (station != null) {
                    views.setViewVisibility(SLOT_VIEW_IDS[slot], View.VISIBLE)
                    views.setTextViewText(SLOT_NAME_IDS[slot], station.name)

                    val artwork = decodeArtworkBitmap(station.artworkUri)
                    if (artwork != null) {
                        views.setImageViewBitmap(SLOT_ARTWORK_IDS[slot], artwork)
                        decodedBitmaps += artwork
                    } else {
                        views.setImageViewResource(
                            SLOT_ARTWORK_IDS[slot],
                            R.drawable.ic_radioshed_notification,
                        )
                    }

                    // Tap starts the station. Request codes are offset by
                    // widgetId so different widget instances don't collide.
                    val requestCode = widgetId * 100 + slot
                    views.setOnClickPendingIntent(
                        SLOT_VIEW_IDS[slot],
                        openAppIntent(context, requestCode, stationId = station.id),
                    )
                } else {
                    views.setViewVisibility(SLOT_VIEW_IDS[slot], View.GONE)
                }
            }

            mgr.updateAppWidget(widgetId, views)
            // Now safe to recycle — updateAppWidget parcels the bitmap pixels
            // inline before returning, so the host already has its own copy.
            decodedBitmaps.forEach { it.recycle() }
        }
    }
}

@Serializable
internal data class LauncherConfig(
    val stationIds: List<Long> = emptyList(),
)
