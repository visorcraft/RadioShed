package com.visorcraft.radioshed.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.data.FolderRepository
import com.visorcraft.radioshed.domain.Folder
import com.visorcraft.radioshed.permissions.StoragePermissions
import com.visorcraft.radioshed.scanner.ScanProgress
import com.visorcraft.radioshed.scanner.Scanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Temporary debug surface for Phase 3 development. Lets you register folders,
 * grant the Android all-files permission, and kick off a scan to watch tracks
 * populate the database. Will be replaced by proper Library Settings + the
 * onboarding folder picker in Phase 3 part 2; for now this is the only way
 * to actually exercise the scanner.
 */
@Composable
fun DebugLibraryPanel(
    modifier: Modifier = Modifier,
    folderRepo: FolderRepository = koinInject(),
    scanner: Scanner = koinInject(),
    permissions: StoragePermissions = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val folders by folderRepo.observeAll().collectAsState(initial = emptyList())

    var permissionGranted by remember { mutableStateOf(permissions.hasAllFilesAccess()) }
    var folderPath by remember { mutableStateOf("/storage/emulated/0/Music") }
    var folderName by remember { mutableStateOf("") }
    var scanStatus by remember { mutableStateOf<String?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }
    // Track the in-flight scan so a second click on "Scan" doesn't kick off
    // a parallel scan (which would race on the artist/tag junction tables).
    var scanJob by remember { mutableStateOf<Job?>(null) }
    val scanInFlight = scanJob?.isActive == true

    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Library (debug)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Temporary scanner harness. Replaced by proper UI in Phase 3 part 2.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )

            Spacer(Modifier.height(12.dp))

            if (!permissionGranted) {
                PermissionBanner(
                    onRequest = {
                        permissions.requestAllFilesAccess()
                    },
                    onRefresh = {
                        permissionGranted = permissions.hasAllFilesAccess()
                    },
                )
                Spacer(Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = folderPath,
                onValueChange = { folderPath = it },
                label = { Text("Folder path (Android: e.g. /storage/emulated/0/Music)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("Display name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        runCatching {
                            folderRepo.insert(
                                displayName = folderName.ifBlank { folderPath.substringAfterLast('/').ifBlank { folderPath } },
                                uri = folderPath,
                                recursive = true,
                            )
                            folderName = ""
                        }.onFailure { lastError = "Add folder failed: ${it.message}" }
                    }
                },
                enabled = folderPath.isNotBlank(),
            ) {
                Text("Add folder")
            }

            Spacer(Modifier.height(16.dp))

            if (folders.isEmpty()) {
                Text(
                    "No folders registered yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            } else {
                Text(
                    "Registered folders (${folders.size}):",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    folders.forEach { folder ->
                        FolderRow(
                            folder = folder,
                            scanEnabled = !scanInFlight,
                            onScan = {
                                if (scanInFlight) return@FolderRow
                                scanStatus = "Starting scan…"
                                lastError = null
                                scanJob = scope.launch {
                                    runCatching {
                                        scanner.scanFolder(folder.id).collect { ev ->
                                            scanStatus = ev.toDebugLine()
                                            // Failed is an emitted value, not an exception, so the
                                            // outer runCatching won't catch it — surface it manually.
                                            if (ev is ScanProgress.Failed) {
                                                lastError = ev.message
                                            }
                                        }
                                    }.onFailure { lastError = "Scan crashed: ${it.message}" }
                                }
                            },
                            onDelete = {
                                if (scanInFlight) return@FolderRow
                                scope.launch {
                                    runCatching { folderRepo.delete(folder.id) }
                                        .onFailure { lastError = "Delete failed: ${it.message}" }
                                }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            scanStatus?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                )
            }
            lastError?.let { err ->
                Text(
                    err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PermissionBanner(onRequest: () -> Unit, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(12.dp),
    ) {
        Text(
            "All Files Access not granted",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Scanner needs MANAGE_EXTERNAL_STORAGE on Android to read arbitrary folders.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRequest) { Text("Grant in Settings") }
            OutlinedButton(onClick = onRefresh) { Text("I granted it") }
        }
    }
}

@Composable
private fun FolderRow(
    folder: Folder,
    scanEnabled: Boolean,
    onScan: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                folder.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "${folder.uri} · ${folder.trackCount} tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onScan, enabled = scanEnabled) { Text("Scan") }
        TextButton(onClick = onDelete, enabled = scanEnabled) { Text("✕") }
    }
}

private fun ScanProgress.toDebugLine(): String = when (this) {
    is ScanProgress.Started -> "Started scanning folder $folderId"
    is ScanProgress.Progress -> "[$tracksProcessed] $currentFile"
    is ScanProgress.Warning -> "WARN: $message"
    is ScanProgress.Completed -> "✔ done — $totalTracks tracks in ${durationMs}ms"
    is ScanProgress.Failed -> "✗ FAILED: $message"
}

@Suppress("unused") // Exposed for tests / future preview parameters
private val _placeholder = Unit
