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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.data.FolderRepository
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.domain.Folder
import com.visorcraft.radioshed.permissions.StoragePermissions
import com.visorcraft.radioshed.scanner.ScanProgress
import com.visorcraft.radioshed.scanner.Scanner
import com.visorcraft.radioshed.source.DiscoveredVolume
import com.visorcraft.radioshed.source.StorageVolumes
import com.visorcraft.radioshed.source.matchesGlob
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
    onOpenDuplicates: () -> Unit = {},
    folderRepo: FolderRepository = koinInject(),
    libraryRepo: LibraryRepository = koinInject(),
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
    var movingFolder by remember { mutableStateOf<Folder?>(null) }
    // Folder awaiting the confirm dialog before delete. Removing a scanner
    // root is disruptive enough to warrant a second tap, mirroring the
    // station-delete confirm in StationsHomeScreen.
    var pendingDeleteFolder by remember { mutableStateOf<Folder?>(null) }
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
                                scanJob =
                                    scope.launch {
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
                        discoveredVolumes.value = storageVolumes.discover() // refresh on open
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
                                scanJob =
                                    scope.launch {
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
                            onMove = { movingFolder = folder },
                            onToggleRecursive = {
                                scope.launch {
                                    runCatching { folderRepo.setRecursive(folder.id, !folder.recursive) }
                                        .onFailure { scanError = it.message }
                                }
                            },
                            onDelete = {
                                if (scanInFlight) return@FolderCard
                                // Defer the actual delete to the confirm dialog
                                // rendered at the bottom of this screen.
                                pendingDeleteFolder = folder
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

            // Library tools section. Adds entries that don't fit the
            // per-folder workflow above — currently the duplicate
            // finder. Power-user features that affect the library as
            // a whole live here.
            Spacer(Modifier.height(24.dp))
            Text(
                "Library tools",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenDuplicates),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        "Find duplicate tracks",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "Group tracks by tag fuzz — surfaces re-rips and accidental re-imports.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
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
                            displayName =
                                name.ifBlank {
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
            libraryRepo = libraryRepo,
            scope = scope,
            onDismiss = { editingFolder = null },
            onSave = { newName, includeGlobs, excludeGlobs ->
                scope.launch {
                    runCatching {
                        if (newName != folder.displayName) {
                            folderRepo.rename(folder.id, newName)
                        }
                        if (includeGlobs != folder.includeGlobs ||
                            excludeGlobs != folder.excludeGlobs
                        ) {
                            folderRepo.setGlobs(folder.id, includeGlobs, excludeGlobs)
                        }
                    }.onFailure { scanError = it.message }
                }
                editingFolder = null
            },
        )
    }

    movingFolder?.let { folder ->
        MoveFolderDialog(
            folder = folder,
            folderRepo = folderRepo,
            scope = scope,
            onDismiss = { movingFolder = null },
            onMoved = { moved ->
                scanStatus = "Moved $moved track${if (moved == 1L) "" else "s"} " +
                    "to new path. Rescan to catch any renamed or added files."
                movingFolder = null
            },
            onError = { msg ->
                scanError = "Move failed: $msg"
                movingFolder = null
            },
        )
    }

    pendingDeleteFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { pendingDeleteFolder = null },
            title = { Text("Remove folder?") },
            text = {
                Text(
                    "Remove \"${folder.displayName}\" from the scanner? " +
                        "Track files are not deleted from disk — they re-link " +
                        "when you add a folder that covers them again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = folder.id
                    pendingDeleteFolder = null
                    scope.launch {
                        runCatching { folderRepo.delete(id) }
                            .onFailure { scanError = it.message }
                    }
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteFolder = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PermissionCard(
    onRequest: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
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
    onMove: () -> Unit,
    onToggleRecursive: () -> Unit,
    onDelete: () -> Unit,
    onPickColor: (Int?) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    ) {
        // Folder accent bar at the top of the card. Visible only when
        // a color is set; renders as a 4dp colored strip across the
        // card's full width so users can scan a list of folders and
        // pick out their personal colorings at a glance.
        folder.accentColor?.let { color ->
            Box(
                modifier =
                    Modifier
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
                    // Move: bulk-rewrite the folder's URI and every track
                    // path under it. Preserves ids / ratings / play counts
                    // when the storage host changes (NAS IP swap, drive
                    // letter remap, etc.). Confirmation dialog gates the
                    // destructive update.
                    TextButton(onClick = onMove, enabled = scanEnabled) { Text("Move") }
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
    val fill =
        color?.let { Color(it.toLong() and 0xFFFFFFFFL) }
            ?: MaterialTheme.colorScheme.surfaceVariant
    val borderColor =
        if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
        }
    Box(
        modifier =
            Modifier
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
private val FolderTagPalette: List<Int> =
    listOf(
        0xFFFF7F1A.toInt(), // brand amber
        0xFFFFC107.toInt(), // gold
        0xFF4ECDC4.toInt(), // teal
        0xFF7B68EE.toInt(), // medium slate blue
        0xFFFF6B6B.toInt(), // coral
        0xFF607D8B.toInt(), // blue grey
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
    val defaultPath =
        remember(volumes) {
            volumes.firstOrNull { it.isRemovable }
                ?.let { com.visorcraft.radioshed.source.joinPath(it.path, "Music") }
                ?: volumes.firstOrNull { it.isPrimary }
                    ?.let { com.visorcraft.radioshed.source.joinPath(it.path, "Music") }
                ?: com.visorcraft.radioshed.source.defaultMusicFolderPath()
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
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
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
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
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
        // parentPath handles both `/` and `\`; the literal `/storage`
        // fallback was Android-only and meaningless on desktop.
        val startPath =
            com.visorcraft.radioshed.source.parentPath(path)
                .ifBlank { com.visorcraft.radioshed.source.defaultMusicFolderPath() }
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
        color =
            if (volume.isRemovable) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
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

/**
 * Move-folder dialog. Edits the folder's URI in place and bulk-
 * rewrites every track row whose path starts with the old URI to
 * use the new URI prefix. Ratings, play counts, station-pin
 * membership, history are preserved — only the path string
 * changes. Useful when:
 *   - A NAS host changes IP (e.g., 192.168.68.150 → 192.168.68.129)
 *   - The share name changes (e.g., `\\nas\music` → `\\nas\media\music`)
 *   - A local drive letter is reassigned
 *
 * Counts the affected tracks up-front and surfaces the number in
 * the confirmation so the user knows the blast radius before
 * committing. The actual transition runs in a single transaction
 * via FolderRepository.moveFolderUri, so a process kill mid-move
 * leaves the DB in either fully-old or fully-new state, never
 * half-and-half.
 */
@Composable
private fun MoveFolderDialog(
    folder: Folder,
    folderRepo: FolderRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit,
    onMoved: (Long) -> Unit,
    onError: (String) -> Unit,
) {
    var newUri by remember { mutableStateOf(folder.uri) }
    var browserOpen by remember { mutableStateOf(false) }
    var trackCount by remember { mutableStateOf<Long?>(null) }
    var moving by remember { mutableStateOf(false) }

    LaunchedEffect(folder.id) {
        // Probe so the dialog can say "this will move N tracks" before
        // the user picks a destination. Doesn't change with the
        // typed new-URI value (the count is of the SOURCE folder).
        trackCount = folderRepo.countTracksWithPathPrefix(folder.uri)
    }

    val canApply = newUri.isNotBlank() && newUri != folder.uri && !moving

    AlertDialog(
        onDismissRequest = { if (!moving) onDismiss() },
        title = { Text("Move folder") },
        confirmButton = {
            Button(
                enabled = canApply,
                onClick = {
                    moving = true
                    scope.launch {
                        runCatching {
                            folderRepo.moveFolderUri(folder.id, newUri.trim())
                        }.onSuccess { moved -> onMoved(moved) }
                            .onFailure {
                                // Reset the in-progress flag BEFORE calling
                                // onError. Today onError dismisses the
                                // dialog so the leftover state isn't
                                // visible, but if a future refactor keeps
                                // the dialog open on error (e.g., to show
                                // the message inline), the buttons would
                                // otherwise stay disabled with "Moving…"
                                // on the confirm and no way to retry.
                                moving = false
                                onError(it.message ?: it::class.simpleName ?: "unknown")
                            }
                    }
                },
            ) { Text(if (moving) "Moving…" else "Move") }
        },
        dismissButton = {
            TextButton(enabled = !moving, onClick = onDismiss) { Text("Cancel") }
        },
        text = {
            Column {
                Text(
                    "Repoint \"${folder.displayName}\" to a new location. " +
                        "Every track under the old path is rewritten to use " +
                        "the new path; ratings, play counts, station pins, " +
                        "and listening history are preserved. After moving, " +
                        "run Scan to pick up files whose subpath also changed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Current path",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Text(
                    folder.uri,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                trackCount?.let {
                    Text(
                        "$it track${if (it == 1L) "" else "s"} will be relocated",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = newUri,
                    onValueChange = { newUri = it },
                    label = { Text("New path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        ),
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { browserOpen = true },
                    enabled = !moving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Browse for folder…") }
            }
        },
    )

    if (browserOpen) {
        // Start the browser one level up from the current URI so the
        // user can easily land on a sibling folder if the share name
        // (rather than the parent volume) is what changed.
        val startPath =
            com.visorcraft.radioshed.source.parentPath(folder.uri)
                .ifBlank { com.visorcraft.radioshed.source.defaultMusicFolderPath() }
        FolderBrowserDialog(
            initialPath = startPath,
            onSelect = { picked ->
                newUri = picked
                browserOpen = false
            },
            onDismiss = { browserOpen = false },
        )
    }
}

@Composable
private fun EditFolderDialog(
    folder: Folder,
    libraryRepo: LibraryRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit,
    onSave: (
        displayName: String,
        includeGlobs: List<String>,
        excludeGlobs: List<String>,
    ) -> Unit,
) {
    var name by remember { mutableStateOf(folder.displayName) }
    // Globs are stored as a `List<String>`; the editor presents
    // them as one pattern per line. Newline-separated rather than
    // comma-separated because brace-expansion patterns like
    // `**/{Rock,Pop}/**` would otherwise get split mid-pattern by
    // a comma parser. Blank lines are filtered out on save.
    var includeText by remember {
        mutableStateOf(folder.includeGlobs.joinToString("\n"))
    }
    var excludeText by remember {
        mutableStateOf(folder.excludeGlobs.joinToString("\n"))
    }
    val parseGlobs: (String) -> List<String> = { raw ->
        raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }
    val nameClean = name.trim()
    val newIncludes = parseGlobs(includeText)
    val newExcludes = parseGlobs(excludeText)
    val canSave =
        nameClean.isNotBlank() && (
            nameClean != folder.displayName ||
                newIncludes != folder.includeGlobs ||
                newExcludes != folder.excludeGlobs
        )
    val confirmSave: () -> Unit = {
        if (canSave) onSave(nameClean, newIncludes, newExcludes)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = confirmSave, enabled = canSave) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Edit folder") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                        ),
                )
                Spacer(Modifier.height(12.dp))
                // Glob include / exclude. One pattern per line, matched
                // against each track's path during the scan
                // (FileEnumerator side). Common cases: `**/Spoken
                // Word/**` to exclude an audiobook tree, `*.flac` to
                // include only lossless. Empty = no filter. Newline
                // separator avoids splitting brace-expansion patterns
                // like `**/{Rock,Pop}/**` mid-pattern.
                Text(
                    "Path filters (one glob per line)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = includeText,
                    onValueChange = { includeText = it },
                    label = { Text("Include patterns") },
                    placeholder = { Text("*.flac\n**/Music/**") },
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = excludeText,
                    onValueChange = { excludeText = it },
                    label = { Text("Exclude patterns") },
                    placeholder = { Text("**/Spoken Word/**\n*.m4b") },
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                // "Test rule" affordance — runs the in-edit globs
                // against the actual indexed tracks under this folder
                // (relative to the folder's URI root) and shows
                // counts + sample paths for each bucket. Lets users
                // verify a glob does what they expect before saving
                // and triggering a rescan, which is the only way to
                // know if a destructive exclude pattern is correct
                // otherwise.
                Spacer(Modifier.height(12.dp))
                GlobPreviewPanel(
                    folderUri = folder.uri,
                    includeGlobs = newIncludes,
                    excludeGlobs = newExcludes,
                    libraryRepo = libraryRepo,
                    scope = scope,
                )
            }
        },
    )
}

@Composable
private fun GlobPreviewPanel(
    folderUri: String,
    includeGlobs: List<String>,
    excludeGlobs: List<String>,
    libraryRepo: LibraryRepository,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var preview by remember { mutableStateOf<GlobPreview?>(null) }
    var loading by remember { mutableStateOf(false) }
    val canRun = includeGlobs.isNotEmpty() || excludeGlobs.isNotEmpty()

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                enabled = canRun && !loading,
                onClick = {
                    loading = true
                    preview = null
                    scope.launch {
                        runCatching {
                            kotlinx.coroutines.withContext(
                                kotlinx.coroutines.Dispatchers.Default,
                            ) {
                                // Scope to this folder by path-prefix
                                // (no track→folder FK exists in the
                                // schema); cheap on the typical
                                // <10k-track library.
                                val tracks =
                                    libraryRepo
                                        .listAvailableTracks(limit = 100_000L, offset = 0L)
                                        .filter { it.path.startsWith(folderUri) }
                                val excludedSamples = mutableListOf<String>()
                                val notIncludedSamples = mutableListOf<String>()
                                var excludedCount = 0
                                var notIncludedCount = 0
                                var keptCount = 0
                                for (t in tracks) {
                                    val rel =
                                        t.path
                                            .removePrefix(folderUri)
                                            .trimStart('/')
                                    val excludeHit =
                                        excludeGlobs.any {
                                            matchesGlob(rel, it)
                                        }
                                    val includePass =
                                        includeGlobs.isEmpty() ||
                                            includeGlobs.any { matchesGlob(rel, it) }
                                    when {
                                        excludeHit -> {
                                            excludedCount += 1
                                            if (excludedSamples.size < 10) {
                                                excludedSamples += rel
                                            }
                                        }
                                        !includePass -> {
                                            notIncludedCount += 1
                                            if (notIncludedSamples.size < 10) {
                                                notIncludedSamples += rel
                                            }
                                        }
                                        else -> keptCount += 1
                                    }
                                }
                                GlobPreview(
                                    totalChecked = tracks.size,
                                    excludedCount = excludedCount,
                                    notIncludedCount = notIncludedCount,
                                    excludedSamples = excludedSamples,
                                    notIncludedSamples = notIncludedSamples,
                                    keptCount = keptCount,
                                )
                            }
                        }.onSuccess { preview = it }
                            .onFailure { preview = null }
                        loading = false
                    }
                },
            ) { Text(if (loading) "Testing…" else "Test rule") }
            if (preview != null) {
                TextButton(onClick = { preview = null }) { Text("Hide") }
            }
        }
        preview?.let { p ->
            Spacer(Modifier.height(4.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    val filteredTotal = p.excludedCount + p.notIncludedCount
                    Text(
                        text = "${p.keptCount} kept · $filteredTotal filtered (of ${p.totalChecked} indexed)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (p.excludedSamples.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Excluded by exclude patterns:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                        p.excludedSamples.forEach {
                            Text(
                                text = "• $it",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (p.notIncludedSamples.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Filtered out by include patterns:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                        p.notIncludedSamples.forEach {
                            Text(
                                text = "• $it",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (p.excludedSamples.isEmpty() && p.notIncludedSamples.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "No tracks would be filtered.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

private data class GlobPreview(
    val totalChecked: Int,
    val excludedCount: Int,
    val notIncludedCount: Int,
    val excludedSamples: List<String>,
    val notIncludedSamples: List<String>,
    val keptCount: Int,
)

private fun ScanProgress.toLine(): String =
    when (this) {
        is ScanProgress.Started -> "Scanning…"
        is ScanProgress.Progress -> "[$tracksProcessed] $currentFile"
        is ScanProgress.Warning -> "WARN: $message"
        is ScanProgress.Completed -> "✔ done — $totalTracks tracks in ${durationMs}ms"
        is ScanProgress.Failed -> "✗ FAILED: $message"
    }
