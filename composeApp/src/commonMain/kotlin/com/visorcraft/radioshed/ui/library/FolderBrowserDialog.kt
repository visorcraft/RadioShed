package com.visorcraft.radioshed.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.source.DiscoveredFolder
import com.visorcraft.radioshed.source.FileBrowser
import org.koin.compose.koinInject

/**
 * Tree-navigation folder picker. Replaces the unfriendly "type a Linux path"
 * UX of the original Add-folder dialog.
 *
 * Flow: user starts at [initialPath] (typically `/storage/`), sees its
 * subdirectories as tappable rows, navigates deeper, and hits "Select this
 * folder" when they're at the directory they want to add as a music root.
 *
 * Hidden directories (`.foo`) and files are filtered out by [FileBrowser].
 *
 * NOT a substitute for the [DiscoveredVolume] quick-pick chips in the
 * [AddFolderDialog] — those land users on `<volume>/Music` in one tap when
 * the layout matches the common case. The browser is for everything else.
 */
@Composable
fun FolderBrowserDialog(
    initialPath: String = "/storage",
    fileBrowser: FileBrowser = koinInject(),
    onSelect: (path: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var currentPath by remember { mutableStateOf(initialPath) }
    var entries by remember { mutableStateOf<List<DiscoveredFolder>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // Quick-jump bookmarks (Home/Music/Downloads + drive letters on
    // Windows). Loaded once on first show and reused on every
    // navigation so the chips don't disappear mid-browse.
    var bookmarks by remember { mutableStateOf<List<DiscoveredFolder>>(emptyList()) }
    LaunchedEffect(Unit) {
        bookmarks = fileBrowser.commonLocations()
    }

    // Editable path field state. Mirrors `currentPath` whenever
    // navigation comes from elsewhere (chip / Up / folder tap), but
    // is decoupled when the user types so each keystroke doesn't
    // trigger a navigation. Pressing Go (or Enter) commits the
    // typed string to `currentPath`. UNC paths (`\\server\share`)
    // and any absolute path the user pastes work as-is — we hand
    // the raw string to `File(path).listFiles()`.
    var pathInput by remember { mutableStateOf(initialPath) }
    LaunchedEffect(currentPath) {
        pathInput = currentPath
    }

    LaunchedEffect(currentPath) {
        loading = true
        entries = fileBrowser.listSubdirectories(currentPath)
        loading = false
    }

    val parent = remember(currentPath) { fileBrowser.parentOf(currentPath) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Browse folders") },
        text = {
            Column {
                // Editable path field. Take any input — drive root
                // (`D:\`), absolute path, or UNC share
                // (`\\server\music`). Enter or Go commits.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = pathInput,
                        onValueChange = { pathInput = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("Path or \\\\server\\share") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions =
                            KeyboardActions(
                                onGo = { currentPath = pathInput.trim() },
                                onDone = { currentPath = pathInput.trim() },
                                onSend = { currentPath = pathInput.trim() },
                            ),
                    )
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = { currentPath = pathInput.trim() }) {
                        Text("Go")
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Breadcrumb-style header: up affordance + current path
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (parent != null) {
                        TextButton(
                            onClick = { currentPath = parent },
                            contentPadding =
                                androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 8.dp,
                                    vertical = 4.dp,
                                ),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(4.dp))
                            Text("Up")
                        }
                        Spacer(Modifier.size(8.dp))
                    }
                    if (currentPath.isEmpty()) {
                        // Synthetic Computer / "This PC" view —
                        // listSubdirectories("") returns drive roots
                        // and the breadcrumb shows a friendly label
                        // instead of the empty string.
                        Icon(
                            imageVector = Icons.Filled.Computer,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = "This PC",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Text(
                            text = currentPath,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // Quick-jump chip row — drive letters / Home / Music
                // shortcuts so users on Windows don't have to click
                // Up repeatedly to escape `~/Music` and reach `D:\`.
                // Hidden when the platform reports no bookmarks.
                if (bookmarks.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        bookmarks.forEach { bm ->
                            QuickJumpChip(
                                label = bm.name,
                                onClick = { currentPath = bm.path },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Folder list. Constrained height so the dialog doesn't grow
                // taller than the screen on long folder trees; LazyColumn scrolls.
                LazyColumn(
                    modifier = Modifier.heightIn(min = 240.dp, max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(entries, key = { it.path }) { folder ->
                        FolderEntry(
                            name = folder.name,
                            onTap = { currentPath = folder.path },
                        )
                    }
                    if (entries.isEmpty() && !loading) {
                        item {
                            Text(
                                text = "(no subfolders)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSelect(currentPath) }) {
                Text("Select this folder")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun QuickJumpChip(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun FolderEntry(
    name: String,
    onTap: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
    }
}
