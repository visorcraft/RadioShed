package com.visorcraft.radioshed.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.data.WidgetConfigRepository
import com.visorcraft.radioshed.domain.Station
import com.visorcraft.radioshed.ui.stations.StationArtwork
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.android.ext.android.inject

/**
 * Configuration activity for the station-launcher widget. Launched automatically
 * when the user drags a new widget onto their home screen (because the widget
 * info XML declares `android:configure`). The user may also reach it by tapping
 * an empty or partially-configured widget.
 *
 * Presents the 6 slots as a scrollable list of rows; tapping a row opens a
 * station-picker bottom sheet. On save the config is written to
 * [WidgetConfigRepository], the widget is updated, and the activity finishes
 * with [RESULT_OK] so the widget host finalises the placement.
 */
class StationLauncherWidgetConfigActivity : ComponentActivity() {
    private val widgetConfigRepo: WidgetConfigRepository by inject()
    private val stationRepo: StationRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appWidgetId =
            intent?.extras?.getInt(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // If the intent carries no valid widget id we can't save anything;
        // finish immediately so the host doesn't leave a stale placeholder.
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        setContent {
            StationLauncherConfigScreen(
                appWidgetId = appWidgetId,
                context = this,
                stationRepo = stationRepo,
                widgetConfigRepo = widgetConfigRepo,
                onSaveAndFinish = {
                    // The appwidget configure contract requires RESULT_OK to
                    // carry EXTRA_APPWIDGET_ID back; some third-party hosts
                    // treat its absence as a failed configuration and delete
                    // the freshly placed widget.
                    setResult(
                        Activity.RESULT_OK,
                        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                    )
                    finish()
                },
                onCancel = {
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StationLauncherConfigScreen(
    appWidgetId: Int,
    context: Context,
    stationRepo: StationRepository,
    widgetConfigRepo: WidgetConfigRepository,
    onSaveAndFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var allStations by remember { mutableStateOf<List<Station>>(emptyList()) }
    var assignedIds by remember { mutableStateOf(List(StationLauncherWidget.MAX_SLOTS) { -1L }) }
    var pickerSlot by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        allStations = stationRepo.list()
        val existing =
            runCatching {
                widgetConfigRepo.get(StationLauncherWidget.widgetKey(appWidgetId))
                    ?.configJson
                    ?.let { Json.decodeFromString<LauncherConfig>(it) }
            }.getOrNull()
        if (existing != null) {
            assignedIds =
                List(StationLauncherWidget.MAX_SLOTS) { index ->
                    existing.stationIds.getOrNull(index) ?: -1L
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Station Launcher") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val config = LauncherConfig(stationIds = assignedIds)
                            widgetConfigRepo.upsert(
                                widgetKey = StationLauncherWidget.widgetKey(appWidgetId),
                                widgetType = "station-launcher",
                                configJson = Json.encodeToString(config),
                            )
                            StationLauncherWidget.pushUpdate(context)
                            onSaveAndFinish()
                        }
                    }) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "Tap a slot to pick a station. Up to ${StationLauncherWidget.MAX_SLOTS} stations.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(StationLauncherWidget.MAX_SLOTS) { slot ->
                    val stationId = assignedIds.getOrNull(slot) ?: -1L
                    val station = allStations.find { it.id == stationId }
                    SlotRow(
                        slot = slot + 1,
                        station = station,
                        onClick = { pickerSlot = slot },
                        onClear = {
                            assignedIds =
                                assignedIds.toMutableList().apply {
                                    set(slot, -1L)
                                }
                        },
                    )
                }
            }
        }
    }

    if (pickerSlot != null) {
        StationPickerSheet(
            stations = allStations,
            onSelect = { station ->
                pickerSlot?.let { slot ->
                    assignedIds =
                        assignedIds.toMutableList().apply {
                            set(slot, station.id)
                        }
                }
                pickerSlot = null
            },
            onDismiss = { pickerSlot = null },
        )
    }
}

@Composable
private fun SlotRow(
    slot: Int,
    station: Station?,
    onClick: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
        ) {
            StationArtwork(
                station = station,
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
            )

            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = "Slot $slot",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = station?.name ?: "Empty",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (station != null) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Clear",
                    )
                }
            }
        }
    }
}

@Composable
private fun StationPickerSheet(
    stations: List<Station>,
    onSelect: (Station) -> Unit,
    onDismiss: () -> Unit,
) {
    // Simple full-screen overlay instead of a proper bottom sheet to avoid
    // adding new dependencies. The back handler dismisses it.
    com.visorcraft.radioshed.nav.PlatformBackHandler(onBack = onDismiss)

    Surface(
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "Pick a station",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                items(stations) { station ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        tonalElevation = 1.dp,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(station) },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                        ) {
                            StationArtwork(
                                station = station,
                                modifier =
                                    Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                            )

                            Text(
                                text = station.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
