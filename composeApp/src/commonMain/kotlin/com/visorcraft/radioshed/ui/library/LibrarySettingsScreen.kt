package com.visorcraft.radioshed.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.data.FolderRepository
import com.visorcraft.radioshed.domain.Folder
import com.visorcraft.radioshed.permissions.StoragePermissions
import com.visorcraft.radioshed.scanner.ScanProgress
import com.visorcraft.radioshed.scanner.Scanner
import com.visorcraft.radioshed.source.DiscoveredVolume
import com.visorcraft.radioshed.source.StorageVolumes
import com.visorcraft.radioshed.ui.util.OnResume
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Library Settings — manage which folders RadioShed scans, kick off scans,
 * see progress. Replaces the Phase-3-part-1 DebugLibraryPanel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySettingsScreen(
    onBack: () -> Unit,
    folderRepo: FolderRepository = koinInject(),
    scanner: Scanner = koinInject(),
    permissions: StoragePermissions = koinInject(),
    storageVolumes: StorageVolumes = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val folders by folderRepo.observeAll().collectAsState(initial = emptyList())
    // Re-discovered each time the dialog opens so newly-inserted SD cards
    // / OTG drives appear without an app restart.
    val discoveredVolumes = remember { mutableStateOf(storageVolumes.discover()) }

    var permissionGranted by remember { mutableStateOf(permissions.hasAllFilesAccess()) }

    // Auto-recheck on return from system Settings — see OnResume kdoc.
    OnResume {
        if (!permissionGranted) {
            permissionGranted = permissions.hasAllFilesAccess()
        }
    }

    var addDialogOpen by remember { mutableStateOf(false) }
    var editingFolder by remember { mutableStateOf<Folder?>(null) }
    var scanStatus by remember { mutableStateOf<String?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    val scanInFlight = scanJob?.isActive == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (!permissionGranted) {
                PermissionCard(
                    onRequest = { permissions.requestAllFilesAccess() },
                    onRefresh = { permissionGranted = permissions.hasAllFilesAccess() },
                )
                Spacer(Modifier.height(16.dp))
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Folders (${folders.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (folders.size > 1) {
                        // Sequential rescan-all. We can't run concurrent
                        // scans (Scanner stages writes through the same
                        // SQLDelight transactions and we don't want to
                        // race), so we walk the list one at a time.
                        TextButton(
                            onClick = {
                                if (scanInFlight) return@TextButton
                                scanError = null
                                scanJob = scope.launch {
                                    val targets = folders
                                    targets.forEachIndexed { index, folder ->
                                        scanStatus = "Scanning ${folder.displayName} " +
                                            "(${index + 1}/${targets.size})…"
                                        runCatching {
                                            scanner.scanFolder(folder.id).collect { ev ->
                                                if (ev is ScanProgress.Failed) {
                                                    scanError = ev.message
                                                }
                                            }
                                        }.onFailure { scanError = "Scan crashed: ${it.message}" }
                                    }
                                    scanStatus = "Scanned ${targets.size} folder" +
                                        if (targets.size == 1) "" else "s"
                                }.also { job ->
                                    job.invokeOnCompletion { scanJob = null }
                                }
                            },
                            enabled = !scanInFlight,
                        ) { Text("Rescan all") }
                    }
                    Button(onClick = {
                        discoveredVolumes.value = storageVolumes.discover()  // refresh on open
                        addDialogOpen = true
                    }) { Text("Add folder") }
                }
            }
            Spacer(Modifier.height(8.dp))

            if (folders.isEmpty()) {
                Text(
                    "No folders yet. Add one to start scanning.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    folders.forEach { folder ->
                        FolderCard(
                            folder = folder,
                            scanEnabled = !scanInFlight,
                            onScan = {
                                if (scanInFlight) return@FolderCard
                                scanStatus = "Starting…"
                                scanError = null
                                scanJob = scope.launch {
                                    runCatching {
                                        scanner.scanFolder(folder.id).collect { ev ->
                                            scanStatus = ev.toLine()
                                            if (ev is ScanProgress.Failed) scanError = ev.message
                                        }
                                    }.onFailure { scanError = "Scan crashed: ${it.message}" }
                                }.also { job ->
                                    // Job.isActive isn't observable State, so a completed job
                                    // would leave scanInFlight stuck at true until something
                                    // else triggered recomposition. Explicitly clear the
                                    // ref on completion so the flag flips reactively.
                                    job.invokeOnCompletion { scanJob = null }
                                }
                            },
                            onEdit = { editingFolder = folder },
                            onToggleRecursive = {
                                scope.launch {
                                    runCatching { folderRepo.setRecursive(folder.id, !folder.recursive) }
                                        .onFailure { scanError = it.message }
                                }
                            },
                            onDelete = {
                                if (scanInFlight) return@FolderCard
                                scope.launch {
                                    runCatching { folderRepo.delete(folder.id) }
                                        .onFailure { scanError = it.message }
                                }
                            },
                            onPickColor = { color ->
                                scope.launch {
                                    runCatching { folderRepo.setAccentColor(folder.id, color) }
                                        .onFailure { scanError = it.message }
                                }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            scanStatus?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                )
            }
            scanError?.let { err ->
                Text(
                    err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (addDialogOpen) {
        AddFolderDialog(
            volumes = discoveredVolumes.value,
            onDismiss = { addDialogOpen = false },
            onAdd = { path, name ->
                addDialogOpen = false
                scope.launch {
                    runCatching {
                        folderRepo.insert(
                            displayName = name.ifBlank {
                                path.substringAfterLast('/').ifBlank { path }
                            },
                            uri = path,
                            recursive = true,
                        )
                    }.onFailure { scanError = "Add folder: ${it.message}" }
                }
            },
        )
    }

    editingFolder?.let { folder ->
        EditFolderDialog(
            folder = folder,
            onDismiss = { editingFolder = null },
            onRename = { newName ->
                scope.launch {
                    runCatching { folderRepo.rename(folder.id, newName) }
                        .onFailure { scanError = it.message }
                }
                editingFolder = null
            },
        )
    }
}

@Composable
private fun PermissionCard(onRequest: () -> Unit, onRefresh: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "All Files Access not granted",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Scans will return empty until granted. iOS users can ignore this — file access there is per-folder via the document picker.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRequest) { Text("Open Settings") }
                OutlinedButton(onClick = onRefresh) { Text("I granted it") }
            }
        }
    }
}

@Composable
private fun FolderCard(
    folder: Folder,
    scanEnabled: Boolean,
    onScan: () -> Unit,
    onEdit: () -> Unit,
    onToggleRecursive: () -> Unit,
    onDelete: () -> Unit,
    onPickColor: (Int?) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        // Folder accent bar at the top of the card. Visible only when
        // a color is set; renders as a 4dp colored strip across the
        // card's full width so users can scan a list of folders and
        // pick out their personal colorings at a glance.
        folder.accentColor?.let { color ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color(color.toLong() and 0xFFFFFFFFL)),
            )
        }
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(end = 8.dp)) {
                    Text(
                        folder.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        folder.uri,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    val tracksLabel = if (folder.trackCount == 1L) "1 track" else "${folder.trackCount} tracks"
                    val whenLabel = folder.lastScannedAt?.let { "scanned" } ?: "not yet scanned"
                    Text(
                        "$tracksLabel · $whenLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            FolderColorRow(
                current = folder.accentColor,
                onPick = onPickColor,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Recursive", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = folder.recursive, onCheckedChange = { onToggleRecursive() })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onEdit, enabled = scanEnabled) { Text("Rename") }
                    TextButton(onClick = onScan, enabled = scanEnabled) { Text("Scan") }
                    TextButton(onClick = onDelete, enabled = scanEnabled) { Text("Delete") }
                }
            }
        }
    }
}

/**
 * Compact swatch row — one bordered circle per palette entry plus a
 * "no color" first slot. Tapping a swatch sets it; tapping the
 * currently-selected swatch clears the tag (so users can toggle
 * without an explicit "remove" affordance).
 */
@Composable
private fun FolderColorRow(
    current: Int?,
    onPick: (Int?) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Tag",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        // "No color" slot. Visually a hollow swatch matching surface.
        // Guard against a redundant clear when already null — palette
        // swatches already have toggle semantics, so re-tapping a
        // color un-tags. The null swatch should be a no-op when the
        // folder already has no color.
        ColorSwatch(
            color = null,
            selected = current == null,
            onClick = { if (current != null) onPick(null) },
        )
        FolderTagPalette.forEach { color ->
            ColorSwatch(
                color = color,
                selected = current == color,
                onClick = { onPick(if (current == color) null else color) },
            )
        }
    }
}

@Composable
private fun ColorSwatch(
    color: Int?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val fill = color?.let { Color(it.toLong() and 0xFFFFFFFFL) }
        ?: MaterialTheme.colorScheme.surfaceVariant
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = CircleShape,
            )
            .background(fill, CircleShape),
    )
}

/**
 * Six-color palette — same accent hues used elsewhere in the app
 * (matches the Stations preset color set so the palettes feel
 * consistent when a folder + station share a tag).
 */
private val FolderTagPalette: List<Int> = listOf(
    0xFFFF7F1A.toInt(),  // brand amber
    0xFFFFC107.toInt(),  // gold
    0xFF4ECDC4.toInt(),  // teal
    0xFF7B68EE.toInt(),  // medium slate blue
    0xFFFF6B6B.toInt(),  // coral
    0xFF607D8B.toInt(),  // blue grey
)

@Composable
private fun AddFolderDialog(
    volumes: List<DiscoveredVolume>,
    onDismiss: () -> Unit,
    onAdd: (path: String, name: String) -> Unit,
) {
    // Default path: prefer a removable volume's Music folder (the user just
    // inserted an SD card → that's almost certainly what they want), then
    // primary internal storage's Music, then the legacy fallback.
    val defaultPath = remember(volumes) {
        volumes.firstOrNull { it.isRemovable }?.let { "${it.path}/Music" }
            ?: volumes.firstOrNull { it.isPrimary }?.let { "${it.path}/Music" }
            ?: "/storage/emulated/0/Music"
    }
    var path by remember { mutableStateOf(defaultPath) }
    var name by remember { mutableStateOf("") }
    var browserOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = { onAdd(path, name) },
                enabled = path.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add folder") },
        text = {
            Column {
                if (volumes.isNotEmpty()) {
                    Text(
                        "Detected volumes",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        volumes.forEach { vol ->
                            VolumeChip(
                                volume = vol,
                                onPick = { sub -> path = if (sub.isBlank()) vol.path else "${vol.path}/$sub" },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Button(
                    onClick = { browserOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Browse for folder…") }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("Folder path (or use Browse above)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                )
            }
        },
    )

    if (browserOpen) {
        // Start the browser at the current path's parent directory so the
        // user sees neighbors of whatever's pre-filled (e.g. if they tapped
        // the SD card chip, /Music auto-fills, browser opens at the SD root
        // showing /Music alongside /Pictures, /Downloads, etc.).
        val startPath = path.substringBeforeLast('/').ifBlank { "/storage" }
        FolderBrowserDialog(
            initialPath = startPath,
            onSelect = { picked ->
                path = picked
                browserOpen = false
            },
            onDismiss = { browserOpen = false },
        )
    }
}

/**
 * One volume row inside the Add-folder dialog. Shows the label + path on
 * the left and three quick-pick buttons on the right that pre-fill the
 * Folder path field with the volume's `Music`, `Download`, or root path.
 * Avoids users having to remember exact paths like `/storage/1A2B-3C4D`
 * for SD cards.
 */
@Composable
private fun VolumeChip(
    volume: DiscoveredVolume,
    onPick: (subdir: String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (volume.isRemovable) MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = volume.label + if (volume.isRemovable) " · removable" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = volume.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onPick("Music") }) { Text("/Music") }
                TextButton(onClick = { onPick("") }) { Text("(root)") }
            }
        }
    }
}

@Composable
private fun EditFolderDialog(
    folder: Folder,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember { mutableStateOf(folder.displayName) }
    val confirmRename: () -> Unit = {
        if (name.isNotBlank() && name != folder.displayName) onRename(name)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = confirmRename,
                enabled = name.isNotBlank() && name != folder.displayName,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Rename folder") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { confirmRename() },
                ),
            )
        },
    )
}

private fun ScanProgress.toLine(): String = when (this) {
    is ScanProgress.Started -> "Scanning…"
    is ScanProgress.Progress -> "[$tracksProcessed] $currentFile"
    is ScanProgress.Warning -> "WARN: $message"
    is ScanProgress.Completed -> "✔ done — $totalTracks tracks in ${durationMs}ms"
    is ScanProgress.Failed -> "✗ FAILED: $message"
}
