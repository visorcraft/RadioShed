package com.visorcraft.radioshed.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.data.nowMillis
import com.visorcraft.radioshed.source.BackupFile
import com.visorcraft.radioshed.source.BackupService
import com.visorcraft.radioshed.source.BackupSharer
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Backup &amp; restore — list of saved bundles + create / restore /
 * delete actions. JSON files live under app filesDir/backups/ on
 * Android; user can also grab them via a Files app for off-device
 * copies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
    onBack: () -> Unit,
    backupService: BackupService = koinInject(),
    backupSharer: BackupSharer = koinInject(),
) {
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<BackupFile>>(emptyList()) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var restoreConfirm by remember { mutableStateOf<BackupFile?>(null) }
    var deleteConfirm by remember { mutableStateOf<BackupFile?>(null) }
    var deleteAllConfirm by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    suspend fun reload() {
        files = backupService.listBackups()
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & restore") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Filled.MoreVert,
                                contentDescription = "More",
                            )
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Delete all backups") },
                                enabled = files.isNotEmpty() && !working,
                                onClick = {
                                    menuOpen = false
                                    deleteAllConfirm = true
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                "Backups dump your stations, playlists, and song ratings to a JSON " +
                    "file you can keep around in case of a reinstall. Folders aren't " +
                    "included (they need re-permissioning anyway).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            working = true
                            val result = backupService.export()
                            status = when (result) {
                                is BackupService.ExportResult.Saved ->
                                    "Saved ${result.stationCount} stations, " +
                                        "${result.playlistCount} playlists, " +
                                        "${result.ratingCount} ratings."
                                is BackupService.ExportResult.Failed -> "Export failed: ${result.reason}"
                            }
                            reload()
                            working = false
                        }
                    },
                    enabled = !working,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Create new")
                }
                // Import from system file picker — restores a backup
                // shared from another device or downloaded from cloud.
                // Reuses the same restore logic as in-app backups; the
                // bytes are read from the picked content URI directly
                // rather than via our private storage layer.
                val pickFile = com.visorcraft.radioshed.ui.util.rememberFilePicker { bytes ->
                    scope.launch {
                        working = true
                        val result = backupService.restoreFromBytes(bytes)
                        status = when (result) {
                            is BackupService.RestoreResult.Done -> buildString {
                                append("Imported: ")
                                append("${result.stationsAdded} stations, ")
                                append("${result.playlistsAdded} playlists, ")
                                append("${result.ratingsApplied} ratings")
                                if (result.likedApplied > 0) append(", ${result.likedApplied} liked")
                                if (result.settingsApplied > 0) append(", ${result.settingsApplied} settings")
                                if (result.eqPresetsApplied > 0) append(", ${result.eqPresetsApplied} EQ presets")
                                val skipped = result.ratingsSkipped + result.likedSkipped
                                if (skipped > 0) append(" ($skipped skipped — tracks not in library)")
                            }
                            is BackupService.RestoreResult.Failed -> "Import failed: ${result.reason}"
                        }
                        reload()
                        working = false
                    }
                }
                OutlinedButton(
                    onClick = { pickFile("application/json") },
                    enabled = !working,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Import…")
                }
            }

            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "Saved backups",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(4.dp))

            if (files.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No backups yet — tap above to create one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(files, key = { it.uri }) { file ->
                        BackupRow(
                            file = file,
                            onRestore = { restoreConfirm = file },
                            onShare = {
                                scope.launch {
                                    val ok = backupSharer.share(file)
                                    if (!ok) status = "Couldn't share backup."
                                }
                            },
                            onDelete = { deleteConfirm = file },
                            enabled = !working,
                        )
                    }
                }
            }
        }
    }

    restoreConfirm?.let { file ->
        // Pre-restore preview: parse the bundle on dialog open and
        // surface the contained counts so the user knows what
        // they're about to merge before committing. State has no
        // remember-key — it resets every time the let block leaves
        // composition (i.e. when restoreConfirm clears), so a quick
        // dismiss-and-reopen always re-runs the preview load
        // instead of getting stuck on a stale "Reading…" snapshot.
        var preview by remember { mutableStateOf<BackupService.BundlePreview?>(null) }
        var previewLoaded by remember { mutableStateOf(false) }
        LaunchedEffect(file.uri) {
            preview = backupService.preview(file.uri)
            previewLoaded = true
        }
        AlertDialog(
            onDismissRequest = { restoreConfirm = null },
            title = { Text("Restore backup?") },
            text = {
                Column {
                    val p = preview
                    if (p != null) {
                        Text(
                            "Bundle contains:",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        val parts = buildList {
                            add("${p.stationCount} station${if (p.stationCount == 1) "" else "s"}")
                            add("${p.playlistCount} playlist${if (p.playlistCount == 1) "" else "s"}")
                            add("${p.ratingCount} rating${if (p.ratingCount == 1) "" else "s"}")
                            if (p.likedCount > 0) add("${p.likedCount} liked")
                            if (p.settingsCount > 0) add("${p.settingsCount} settings")
                            if (p.eqPresetCount > 0) add("${p.eqPresetCount} EQ preset${if (p.eqPresetCount == 1) "" else "s"}")
                        }
                        Text(
                            text = parts.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                        // Backup creation time helps the user disambiguate
                        // when several backups have similar contents but
                        // different vintages — e.g. "yesterday" vs
                        // "3 weeks ago" picks the right snapshot.
                        if (p.exportedAtMs > 0L) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Created ${formatTimestamp(p.exportedAtMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Stations and playlists are added to your library. " +
                                "Existing data isn't deleted — duplicates may appear if " +
                                "you've imported this backup before. Ratings overwrite " +
                                "matching tracks (matched by file path).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    } else if (previewLoaded) {
                        Text(
                            "Couldn't parse the backup file. Restoring may still " +
                                "work if the format version is supported — proceed " +
                                "to see a specific error.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            "Reading backup contents…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = previewLoaded,
                    onClick = {
                    val toRestore = file
                    restoreConfirm = null
                    scope.launch {
                        working = true
                        val result = backupService.restore(toRestore.uri)
                        status = when (result) {
                            is BackupService.RestoreResult.Done -> buildString {
                                append("Restored: ")
                                append("${result.stationsAdded} stations, ")
                                append("${result.playlistsAdded} playlists, ")
                                append("${result.ratingsApplied} ratings")
                                if (result.likedApplied > 0) {
                                    append(", ${result.likedApplied} liked")
                                }
                                if (result.settingsApplied > 0) {
                                    append(", ${result.settingsApplied} settings")
                                }
                                if (result.eqPresetsApplied > 0) {
                                    append(", ${result.eqPresetsApplied} EQ presets")
                                }
                                val totalSkipped = result.ratingsSkipped + result.likedSkipped
                                if (totalSkipped > 0) {
                                    append(" ($totalSkipped skipped — tracks not in library)")
                                }
                                if (result.errors > 0) {
                                    append(" — ${result.errors} row")
                                    if (result.errors != 1) append("s")
                                    append(" failed during import")
                                }
                            }
                            is BackupService.RestoreResult.Failed -> "Restore failed: ${result.reason}"
                        }
                        working = false
                    }
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { restoreConfirm = null }) { Text("Cancel") }
            },
        )
    }


    deleteConfirm?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteConfirm = null },
            title = { Text("Delete backup file?") },
            text = { Text("This permanently removes ${file.displayName}.") },
            confirmButton = {
                TextButton(onClick = {
                    val toDelete = file
                    deleteConfirm = null
                    scope.launch {
                        backupService.delete(toDelete.uri)
                        reload()
                    }
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = null }) { Text("Cancel") }
            },
        )
    }

    if (deleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { deleteAllConfirm = false },
            title = { Text("Delete all backup files?") },
            text = {
                Text(
                    "This permanently removes every backup file under " +
                        "the app's private storage (${files.size} file" +
                        (if (files.size == 1) "" else "s") +
                        "). Library, ratings, playlists, and stations are " +
                        "untouched — only the saved JSON dumps go away.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val toDelete = files
                    deleteAllConfirm = false
                    scope.launch {
                        working = true
                        var failed = 0
                        toDelete.forEach { f ->
                            runCatching { backupService.delete(f.uri) }
                                .onFailure { failed += 1 }
                        }
                        status = if (failed == 0) {
                            "Deleted ${toDelete.size} backup file" +
                                (if (toDelete.size == 1) "" else "s") + "."
                        } else {
                            "Deleted ${toDelete.size - failed} of " +
                                "${toDelete.size} (${failed} failed)."
                        }
                        reload()
                        working = false
                    }
                }) {
                    Text("Delete all", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteAllConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun BackupRow(
    file: BackupFile,
    onRestore: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Backup,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${formatTimestamp(file.createdAt)} · ${formatBytes(file.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
            IconButton(onClick = onShare, enabled = enabled) {
                Icon(Icons.Filled.Share, contentDescription = "Share")
            }
            IconButton(onClick = onRestore, enabled = enabled) {
                Icon(Icons.Filled.Restore, contentDescription = "Restore")
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Relative-time formatter — sidesteps needing a calendar lib (we don't
 * pull in kotlinx-datetime). Buckets at minute / hour / day / week /
 * month boundaries; anything older than ~year falls back to "Long ago"
 * which is fine for backup files that should not realistically live
 * that long.
 */
private fun formatTimestamp(ms: Long): String {
    val deltaMs = (nowMillis() - ms).coerceAtLeast(0L)
    val sec = deltaMs / 1_000L
    val min = sec / 60L
    val hr = min / 60L
    val day = hr / 24L
    val wk = day / 7L
    val mo = day / 30L
    return when {
        sec < 45L -> "Just now"
        sec < 90L -> "1 minute ago"
        min < 45L -> "$min minutes ago"
        min < 90L -> "1 hour ago"
        hr < 24L -> "$hr hours ago"
        hr < 36L -> "Yesterday"
        day < 7L -> "$day days ago"
        wk < 5L -> "$wk week${if (wk == 1L) "" else "s"} ago"
        mo < 12L -> "$mo month${if (mo == 1L) "" else "s"} ago"
        else -> "Long ago"
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}
