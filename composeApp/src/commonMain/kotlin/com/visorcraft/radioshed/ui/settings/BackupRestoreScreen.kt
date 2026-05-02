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
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
) {
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<BackupFile>>(emptyList()) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var restoreConfirm by remember { mutableStateOf<BackupFile?>(null) }
    var deleteConfirm by remember { mutableStateOf<BackupFile?>(null) }

    suspend fun reload() {
        files = backupService.listBackups()
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & restore") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
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
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Create new backup")
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
                            onDelete = { deleteConfirm = file },
                            enabled = !working,
                        )
                    }
                }
            }
        }
    }

    restoreConfirm?.let { file ->
        AlertDialog(
            onDismissRequest = { restoreConfirm = null },
            title = { Text("Restore backup?") },
            text = {
                Text(
                    "Stations and playlists from this backup will be added to your " +
                        "library. Existing data isn't deleted — duplicates may appear " +
                        "if you've imported this backup before. Ratings will overwrite " +
                        "existing ratings on matching tracks (matched by file path).",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val toRestore = file
                    restoreConfirm = null
                    scope.launch {
                        working = true
                        val result = backupService.restore(toRestore.uri)
                        status = when (result) {
                            is BackupService.RestoreResult.Done ->
                                "Restored: ${result.stationsAdded} stations, " +
                                    "${result.playlistsAdded} playlists, " +
                                    "${result.ratingsApplied} ratings " +
                                    if (result.ratingsSkipped > 0)
                                        "(${result.ratingsSkipped} ratings skipped — tracks not in library)"
                                    else ""
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
}

@Composable
private fun BackupRow(
    file: BackupFile,
    onRestore: () -> Unit,
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
