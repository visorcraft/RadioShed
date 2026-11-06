package com.visorcraft.radioshed.ui.car

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.car.BluetoothWatcher
import com.visorcraft.radioshed.car.BtPeer
import com.visorcraft.radioshed.car.COMMON_DRIVE_KEY_CODES
import com.visorcraft.radioshed.car.HARDWARE_DRIVE_KEY_ACTIONS
import com.visorcraft.radioshed.car.NullBluetoothWatcher
import com.visorcraft.radioshed.car.NullSpeedWatcher
import com.visorcraft.radioshed.car.SpeedWatcher
import com.visorcraft.radioshed.car.UI_DRIVE_KEY_ACTIONS
import com.visorcraft.radioshed.car.driveKeyCodeLabel
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.domain.DriveKeyBinding
import com.visorcraft.radioshed.domain.DriveModeTrigger
import com.visorcraft.radioshed.domain.Station
import com.visorcraft.radioshed.domain.decodeDriveKeyBindings
import com.visorcraft.radioshed.domain.decodeDriveTriggers
import com.visorcraft.radioshed.domain.encodeDriveKeyBindings
import com.visorcraft.radioshed.domain.encodeDriveTriggers
import com.visorcraft.radioshed.permissions.rememberBluetoothConnectGate
import com.visorcraft.radioshed.permissions.rememberLocationGate
import com.visorcraft.radioshed.ui.settings.MinutesOfDaySlider
import com.visorcraft.radioshed.ui.settings.SettingsSectionHeader
import com.visorcraft.radioshed.ui.settings.SettingsToggleRow
import com.visorcraft.radioshed.ui.settings.formatHhMm
import com.visorcraft.radioshed.ui.util.rememberHapticTick
import org.koin.compose.koinInject

/** Human labels for the Drive Mode button-bar action ids. The id ORDER (and
 *  the set of valid ids) is owned by [SettingsStore.DRIVE_BUTTON_ID_ORDER];
 *  this map only supplies display strings. Keeping the two apart means adding
 *  an id in SettingsStore without a label here trips [DRIVE_BUTTON_ORDER]'s
 *  fail-fast check rather than silently rendering a blank row. */
private val DRIVE_BUTTON_LABELS =
    mapOf(
        "previous" to "Previous",
        "play_pause" to "Play / Pause",
        "next" to "Next",
        "station_picker" to "Station picker",
        "voice_search" to "Voice search",
        "mute" to "Mute",
    )

/** Canonical (id → label) order for the button-bar editor, built from
 *  [SettingsStore.DRIVE_BUTTON_ID_ORDER] mapped through [DRIVE_BUTTON_LABELS].
 *  Selected ids persist in this order so the rendered bar (Task 2.4) is
 *  deterministic regardless of the order the user toggled them. Fails fast if
 *  an id has no label so the two lists can't silently drift apart. */
private val DRIVE_BUTTON_ORDER: List<Pair<String, String>> =
    SettingsStore.DRIVE_BUTTON_ID_ORDER.map { id ->
        id to (DRIVE_BUTTON_LABELS[id] ?: error("missing label for Drive Mode button id '$id'"))
    }

/** Hardware-key bindings: all Drive Mode transport actions. */
private val KEY_BINDING_ACTION_ORDER: List<Pair<String, String>> =
    (HARDWARE_DRIVE_KEY_ACTIONS + UI_DRIVE_KEY_ACTIONS).map { id ->
        id to (DRIVE_BUTTON_LABELS[id] ?: error("missing label for hardware key action '$id'"))
    }

private const val QUICK_LIST_MAX = 6
private const val MIN_BUTTONS = 4

/**
 * Drive Mode configuration: the manual toggle + auto-exit, the automatic
 * trigger list (Bluetooth devices / schedule windows), the big-button station
 * quick-list, and the transport button-bar selection.
 *
 * State is hoisted via [SettingsStore] reads held in compose state; every edit
 * re-encodes and writes straight through so the change survives process death
 * and is picked up by the running [com.visorcraft.radioshed.car.DriveModeController].
 *
 * Triggers round-trip through [encodeDriveTriggers] / [decodeDriveTriggers]
 * (the same codec the controller decodes with). The quick-list is comma-joined
 * station ids (≤6); the button bar is comma-joined action ids ordered by
 * [SettingsStore.DRIVE_BUTTON_ID_ORDER].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveModeSettingsScreen(
    onBack: () -> Unit,
    settings: SettingsStore = koinInject(),
    bluetoothWatcher: BluetoothWatcher = koinInject(),
    speedWatcher: SpeedWatcher = koinInject(),
    stationRepo: StationRepository = koinInject(),
) {
    // Bluetooth triggers only exist on platforms with a real watcher; desktop /
    // iOS get the [NullBluetoothWatcher] no-op, where there's nothing to pair
    // against. Computed once (the injected instance doesn't change) and used to
    // gate the "Add Bluetooth device" affordance + show the platform note.
    val btSupported = remember(bluetoothWatcher) { bluetoothWatcher !is NullBluetoothWatcher }
    val speedSupported = remember(speedWatcher) { speedWatcher !is NullSpeedWatcher }

    var driveMode by remember { mutableStateOf(settings.getDriveMode()) }
    var autoExit by remember { mutableStateOf(settings.getDriveAutoExit()) }

    var triggers by remember { mutableStateOf(decodeDriveTriggers(settings.getDriveTriggers())) }
    val persistTriggers: (List<DriveModeTrigger>) -> Unit = { next ->
        triggers = next
        settings.setDriveTriggers(encodeDriveTriggers(next))
    }

    var quickList by remember { mutableStateOf(parseIds(settings.getDriveQuickList())) }
    val persistQuickList: (List<String>) -> Unit = { next ->
        quickList = next
        settings.setDriveQuickList(next)
    }

    var buttons by remember { mutableStateOf(parseIds(settings.getDriveButtons())) }
    val persistButtons: (List<String>) -> Unit = { next ->
        // Re-order to the canonical sequence so rendering (Task 2.4) is stable.
        val ordered = DRIVE_BUTTON_ORDER.map { it.first }.filter { it in next }
        buttons = ordered
        settings.setDriveButtons(ordered)
    }

    // All stations, used by the quick-list editor for names + the "add" dialog.
    var allStations by remember { mutableStateOf<List<Station>>(emptyList()) }
    LaunchedEffect(Unit) { allStations = stationRepo.list() }

    val btGate = rememberBluetoothConnectGate()
    val locationGate = rememberLocationGate()

    var keyBindings by remember { mutableStateOf(decodeDriveKeyBindings(settings.getDriveKeyBindings())) }
    val persistKeyBindings: (List<DriveKeyBinding>) -> Unit = { next ->
        keyBindings = next
        settings.setDriveKeyBindings(encodeDriveKeyBindings(next))
    }

    var btDialogOpen by remember { mutableStateOf(false) }
    var scheduleDialogOpen by remember { mutableStateOf(false) }
    var speedDialogOpen by remember { mutableStateOf(false) }
    var addKeyBindingDialogOpen by remember { mutableStateOf(false) }
    var addStationDialogOpen by remember { mutableStateOf(false) }

    // True only while we're waiting for the OS permission dialog the user just
    // triggered via "Add Bluetooth device". It lets the grant LaunchedEffect
    // auto-open the picker the instant permission is granted — without it, the
    // effect would also fire on every screen entry where permission is already
    // granted and pop the dialog unbidden.
    var btRequestInFlight by remember { mutableStateOf(false) }
    LaunchedEffect(btGate.granted) {
        if (btGate.granted && btRequestInFlight) {
            btRequestInFlight = false
            btDialogOpen = true
        }
    }

    var speedRequestInFlight by remember { mutableStateOf(false) }
    LaunchedEffect(locationGate.granted) {
        if (locationGate.granted) {
            speedWatcher.start()
            if (speedRequestInFlight) {
                speedRequestInFlight = false
                speedDialogOpen = true
            }
        }
    }

    val hasSpeedTrigger = triggers.any { it is DriveModeTrigger.Speed }

    val haptic = rememberHapticTick()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Drive Mode") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // ---- 1. Drive Mode ----
            SettingsSectionHeader("Drive Mode")
            SettingsToggleRow(
                title = "Drive Mode",
                subtitle =
                    "Larger transport buttons on Now Playing — easier to hit at a glance " +
                        "while driving. Takes effect the next time you open Now Playing.",
                checked = driveMode,
                onCheckedChange = { v ->
                    haptic()
                    driveMode = v
                    settings.setDriveMode(v)
                },
            )
            Spacer(Modifier.height(8.dp))
            SettingsToggleRow(
                title = "Auto-exit Drive Mode",
                subtitle =
                    "Leave Drive Mode automatically once no trigger condition is active " +
                        "anymore (Bluetooth disconnects, schedule window closes).",
                checked = autoExit,
                onCheckedChange = { v ->
                    autoExit = v
                    settings.setDriveAutoExit(v)
                },
            )

            Spacer(Modifier.height(24.dp))

            // ---- 2. Triggers ----
            SettingsSectionHeader("Automatic triggers")
            Text(
                "Drive Mode turns on by itself when any of these conditions match.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))
            TriggersSection(
                triggers = triggers,
                btSupported = btSupported,
                speedSupported = speedSupported,
                hasSpeedTrigger = hasSpeedTrigger,
                onDelete = { index ->
                    persistTriggers(triggers.toMutableList().apply { removeAt(index) })
                },
                onAddBluetooth = {
                    if (!btGate.granted) {
                        // Remember we kicked off the request so the grant
                        // LaunchedEffect opens the picker for us once it resolves.
                        btRequestInFlight = true
                        btGate.request()
                    } else {
                        btDialogOpen = true
                    }
                },
                onAddSchedule = { scheduleDialogOpen = true },
                onAddSpeed = {
                    if (!locationGate.granted) {
                        speedRequestInFlight = true
                        locationGate.request()
                    } else {
                        speedDialogOpen = true
                    }
                },
            )

            Spacer(Modifier.height(24.dp))

            SettingsSectionHeader("Steering-wheel keys")
            Text(
                "Map hardware media keys to Drive Mode actions while Drive Mode is active.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))
            KeyBindingsSection(
                bindings = keyBindings,
                onRemove = { index ->
                    persistKeyBindings(keyBindings.toMutableList().apply { removeAt(index) })
                },
                onAdd = { addKeyBindingDialogOpen = true },
            )

            Spacer(Modifier.height(24.dp))

            // ---- 3. Quick list ----
            SettingsSectionHeader("Station quick list")
            Text(
                "Stations shown as big buttons in the Drive Mode picker, in this order. " +
                    "Up to $QUICK_LIST_MAX.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))
            QuickListSection(
                quickList = quickList,
                stationsById = allStations.associateBy { it.id.toString() },
                onMoveUp = { index ->
                    if (index <= 0) return@QuickListSection
                    persistQuickList(
                        quickList.toMutableList().apply {
                            val tmp = this[index - 1]
                            this[index - 1] = this[index]
                            this[index] = tmp
                        },
                    )
                },
                onMoveDown = { index ->
                    if (index >= quickList.lastIndex) return@QuickListSection
                    persistQuickList(
                        quickList.toMutableList().apply {
                            val tmp = this[index + 1]
                            this[index + 1] = this[index]
                            this[index] = tmp
                        },
                    )
                },
                onRemove = { index ->
                    persistQuickList(quickList.toMutableList().apply { removeAt(index) })
                },
                onAdd = { addStationDialogOpen = true },
            )

            Spacer(Modifier.height(24.dp))

            // ---- 4. Buttons ----
            SettingsSectionHeader("Transport buttons")
            Text(
                "Pick which controls appear in the Drive Mode button bar. Choose $MIN_BUTTONS " +
                    "to ${DRIVE_BUTTON_ORDER.size}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))
            ButtonsSection(
                selected = buttons,
                onToggle = { id, checked ->
                    if (checked) {
                        if (id !in buttons) persistButtons(buttons + id)
                    } else {
                        // Enforce a 4-button floor — block the deselect that
                        // would drop below it (the hint line explains why).
                        if (buttons.size > MIN_BUTTONS) persistButtons(buttons - id)
                    }
                },
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (btDialogOpen) {
        BluetoothDeviceDialog(
            // Filter out devices already configured as triggers.
            candidates =
                bluetoothWatcher.bondedDevices().filterNot { peer ->
                    triggers.any { it is DriveModeTrigger.BtDevice && it.mac == peer.mac }
                },
            onPick = { peer ->
                persistTriggers(
                    triggers + DriveModeTrigger.BtDevice(mac = peer.mac, name = peer.name),
                )
                btDialogOpen = false
            },
            onDismiss = { btDialogOpen = false },
        )
    }

    if (scheduleDialogOpen) {
        ScheduleDialog(
            onConfirm = { startMin, endMin ->
                persistTriggers(
                    triggers + DriveModeTrigger.Schedule(startMin = startMin, endMin = endMin),
                )
                scheduleDialogOpen = false
            },
            onDismiss = { scheduleDialogOpen = false },
        )
    }

    if (speedDialogOpen) {
        SpeedDialog(
            onConfirm = { enterKmh, exitKmh ->
                persistTriggers(
                    triggers.filterNot { it is DriveModeTrigger.Speed } +
                        DriveModeTrigger.Speed(enterKmh = enterKmh, exitKmh = exitKmh),
                )
                speedDialogOpen = false
            },
            onDismiss = { speedDialogOpen = false },
        )
    }

    if (addKeyBindingDialogOpen) {
        AddKeyBindingDialog(
            usedKeyCodes = keyBindings.map { it.keyCode }.toSet(),
            onPick = { binding ->
                persistKeyBindings(keyBindings + binding)
                addKeyBindingDialogOpen = false
            },
            onDismiss = { addKeyBindingDialogOpen = false },
        )
    }

    if (addStationDialogOpen) {
        AddStationDialog(
            // Only stations not already in the quick list.
            candidates = allStations.filterNot { it.id.toString() in quickList },
            onPick = { station ->
                if (quickList.size < QUICK_LIST_MAX) {
                    persistQuickList(quickList + station.id.toString())
                }
                addStationDialogOpen = false
            },
            onDismiss = { addStationDialogOpen = false },
        )
    }
}

@Composable
private fun TriggersSection(
    triggers: List<DriveModeTrigger>,
    btSupported: Boolean,
    speedSupported: Boolean,
    hasSpeedTrigger: Boolean,
    onDelete: (Int) -> Unit,
    onAddBluetooth: () -> Unit,
    onAddSchedule: () -> Unit,
    onAddSpeed: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (triggers.isEmpty()) {
                Text(
                    "No triggers yet. Drive Mode is activated manually.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            } else {
                triggers.forEachIndexed { index, trigger ->
                    TriggerRow(trigger = trigger, onDelete = { onDelete(index) })
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // On platforms without a real Bluetooth watcher (desktop / iOS)
                // there's nothing to pair against, so disable the row rather than
                // open an always-empty dialog. Schedule triggers still work.
                OutlinedButton(onClick = onAddBluetooth, enabled = btSupported) {
                    Text("Add Bluetooth device")
                }
                OutlinedButton(onClick = onAddSchedule) { Text("Add schedule") }
                OutlinedButton(
                    onClick = onAddSpeed,
                    enabled = speedSupported && !hasSpeedTrigger,
                ) {
                    Text(if (hasSpeedTrigger) "Speed trigger set" else "Add speed")
                }
            }
            if (!btSupported) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Bluetooth triggers aren't available on this platform.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            if (!speedSupported) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Speed triggers aren't available on this platform.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            } else if (!hasSpeedTrigger) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Location permission is required for GPS speed triggers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun TriggerRow(
    trigger: DriveModeTrigger,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (trigger) {
            is DriveModeTrigger.BtDevice -> {
                Icon(
                    imageVector = Icons.Filled.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        trigger.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        trigger.mac,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            is DriveModeTrigger.Schedule -> {
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    "${formatHhMm(trigger.startMin)} – ${formatHhMm(trigger.endMin)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }

            is DriveModeTrigger.Speed -> {
                Icon(
                    imageVector = Icons.Filled.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    "Enter ≥ ${trigger.enterKmh.toInt()} km/h · exit < ${trigger.exitKmh.toInt()} km/h",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(imageVector = Icons.Filled.Delete, contentDescription = "Delete trigger")
        }
    }
}

@Composable
private fun QuickListSection(
    quickList: List<String>,
    stationsById: Map<String, Station>,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    val atCapacity = quickList.size >= QUICK_LIST_MAX
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (quickList.isEmpty()) {
                Text(
                    "No stations yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            } else {
                quickList.forEachIndexed { index, id ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            // Stale id (station deleted) degrades to the raw id
                            // rather than vanishing, so the user can still remove it.
                            // This fallback also doubles as the loading placeholder
                            // before allStations resolves; the brief "Unknown
                            // station" flash on entry is imperceptible (one frame).
                            stationsById[id]?.name ?: "Unknown station ($id)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onMoveUp(index) }, enabled = index > 0) {
                            Icon(
                                imageVector = Icons.Filled.ArrowUpward,
                                contentDescription = "Move up",
                            )
                        }
                        IconButton(
                            onClick = { onMoveDown(index) },
                            enabled = index < quickList.lastIndex,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ArrowDownward,
                                contentDescription = "Move down",
                            )
                        }
                        IconButton(onClick = { onRemove(index) }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Remove station",
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onAdd, enabled = !atCapacity) { Text("Add station") }
                if (atCapacity) {
                    Spacer(Modifier.size(12.dp))
                    Text(
                        "$QUICK_LIST_MAX of $QUICK_LIST_MAX",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ButtonsSection(
    selected: List<String>,
    onToggle: (String, Boolean) -> Unit,
) {
    val atFloor = selected.size <= MIN_BUTTONS
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            DRIVE_BUTTON_ORDER.forEach { (id, label) ->
                val checked = id in selected
                // Block the deselect that would drop below the floor — the
                // checkbox stays disabled while it's the load-bearing one.
                val deselectBlocked = checked && atFloor
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        enabled = !deselectBlocked,
                        onCheckedChange = { onToggle(id, it) },
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (atFloor) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "At least $MIN_BUTTONS buttons are required.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

// Only reachable on Bluetooth-capable platforms: the sole entry point is the
// "Add Bluetooth device" button, which is disabled when there's no real
// watcher — so the empty state can assume the Android pair/permission story.
@Composable
private fun BluetoothDeviceDialog(
    candidates: List<BtPeer>,
    onPick: (BtPeer) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Paired devices") },
        text = {
            Column {
                if (candidates.isEmpty()) {
                    Text(
                        "No paired devices available. Pair a device in your phone's " +
                            "Bluetooth settings, or grant the nearby-devices permission.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                } else {
                    candidates.forEach { peer ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    peer.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    peer.mac,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                            }
                            Button(onClick = { onPick(peer) }) { Text("Add") }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ScheduleDialog(
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var startMin by remember { mutableStateOf(8 * 60) }
    var endMin by remember { mutableStateOf(17 * 60) }
    // A zero-width window (start == end) is never active per the MinuteWindow
    // contract, so it'd save a trigger that can never fire — block the confirm.
    val zeroWidth = startMin == endMin
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(startMin, endMin) }, enabled = !zeroWidth) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Schedule window") },
        text = {
            Column {
                MinutesOfDaySlider(label = "Start", minutes = startMin, onChange = { startMin = it })
                Spacer(Modifier.height(8.dp))
                MinutesOfDaySlider(label = "End", minutes = endMin, onChange = { endMin = it })
                Spacer(Modifier.height(8.dp))
                Text(
                    if (zeroWidth) {
                        "Start and end are the same — that window is never active. " +
                            "Pick different times."
                    } else {
                        "If the start is later than the end, the window wraps across midnight " +
                            "(e.g. 23:00 → 01:00)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        },
    )
}

@Composable
private fun AddStationDialog(
    candidates: List<Station>,
    onPick: (Station) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Add station") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (candidates.isEmpty()) {
                    Text(
                        "All stations are already in the quick list.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                } else {
                    candidates.forEach { station ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                station.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Button(onClick = { onPick(station) }) { Text("Add") }
                        }
                    }
                }
            }
        },
    )
}

/** Split a comma-joined id string into a clean list (drops blanks). */
private fun parseIds(joined: String): List<String> = joined.split(',').map { it.trim() }.filter { it.isNotEmpty() }

@Composable
private fun KeyBindingsSection(
    bindings: List<DriveKeyBinding>,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (bindings.isEmpty()) {
                Text(
                    "No key bindings yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            } else {
                bindings.forEachIndexed { index, binding ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${driveKeyCodeLabel(binding.keyCode)} → " +
                                (DRIVE_BUTTON_LABELS[binding.actionId] ?: binding.actionId),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onRemove(index) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove binding")
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onAdd) { Text("Add key binding") }
        }
    }
}

@Composable
private fun SpeedDialog(
    onConfirm: (Float, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var enterKmh by remember { mutableStateOf(25f) }
    var exitKmh by remember { mutableStateOf(15f) }
    val valid = enterKmh > exitKmh && exitKmh >= 0f
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(enterKmh, exitKmh) }, enabled = valid) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Speed trigger") },
        text = {
            Column {
                Text("Enter speed (km/h)", style = MaterialTheme.typography.labelMedium)
                androidx.compose.material3.Slider(
                    value = enterKmh,
                    onValueChange = { enterKmh = it },
                    valueRange = 5f..120f,
                    steps = 22,
                )
                Text("${enterKmh.toInt()} km/h", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text("Exit below (km/h)", style = MaterialTheme.typography.labelMedium)
                androidx.compose.material3.Slider(
                    value = exitKmh,
                    onValueChange = { exitKmh = it },
                    valueRange = 0f..80f,
                    steps = 15,
                )
                Text("${exitKmh.toInt()} km/h", style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

@Composable
private fun AddKeyBindingDialog(
    usedKeyCodes: Set<Int>,
    onPick: (DriveKeyBinding) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedKey by remember { mutableStateOf(COMMON_DRIVE_KEY_CODES.first()) }
    var selectedAction by remember { mutableStateOf("play_pause") }
    val candidates = COMMON_DRIVE_KEY_CODES.filterNot { it in usedKeyCodes }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onPick(DriveKeyBinding(selectedKey, selectedAction)) },
                enabled = candidates.isNotEmpty(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Key binding") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (candidates.isEmpty()) {
                    Text("All common keys are already bound.")
                } else {
                    Text("Key", style = MaterialTheme.typography.labelMedium)
                    candidates.forEach { code ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { selectedKey = code },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = selectedKey == code, onCheckedChange = { selectedKey = code })
                            Text(driveKeyCodeLabel(code))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Action", style = MaterialTheme.typography.labelMedium)
                    KEY_BINDING_ACTION_ORDER.forEach { (id, label) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { selectedAction = id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = selectedAction == id, onCheckedChange = { selectedAction = id })
                            Text(label)
                        }
                    }
                }
            }
        },
    )
}
