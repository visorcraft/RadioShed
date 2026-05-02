package com.visorcraft.radioshed.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
                // Breadcrumb-style header: up affordance + current path
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (parent != null) {
                        TextButton(
                            onClick = { currentPath = parent },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 8.dp,
                                vertical = 4.dp,
                            ),
                        ) { Text("◀ Up") }
                        Spacer(Modifier.size(8.dp))
                    }
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
private fun FolderEntry(name: String, onTap: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = "📁",
                style = MaterialTheme.typography.titleMedium,
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
            Text(
                text = "›",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
    }
}
