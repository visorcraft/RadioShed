package com.visorcraft.radioshed.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.Playable
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.source.DirectoryEntry
import com.visorcraft.radioshed.source.FileBrowser
import com.visorcraft.radioshed.source.TrackSharer
import com.visorcraft.radioshed.stations.StationPlayback
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * In-app folder browser. Reached from Track Info → "Open folder" so
 * users can poke around the on-disk layout without leaving the app
 * and without depending on a third-party file manager (which can't be
 * relied on across OEMs — the AOSP DocumentsContract intent the prior
 * implementation used resolves to nothing on Samsung's stock My Files
 * app, for example).
 *
 * Capabilities:
 *  - Up navigation via the top bar's "↑" affordance and back-press
 *  - Tap a directory to drill in
 *  - Tap an audio file to play it (seeds an ad-hoc queue with the
 *    visible audio files in this directory so skip-next walks the
 *    folder, matching the Library Browser tap-to-play semantics)
 *  - Long-press an audio file for a context sheet (Play / Share /
 *    Copy path)
 *  - Non-audio files render dimmed and are tap-noop (we don't try to
 *    open them with system viewers — the whole point of this screen
 *    is to avoid the unreliable hand-off to other apps)
 *
 * Permission story: the `MANAGE_EXTERNAL_STORAGE` permission already
 * granted to the scanner gives us read access to every mounted
 * volume. Directories the user can't read surface as "(empty)".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FolderBrowserScreen(
    initialPath: String,
    onClose: () -> Unit,
    fileBrowser: FileBrowser = koinInject(),
    libraryRepo: LibraryRepository = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    audioEngine: AudioEngine = koinInject(),
    trackSharer: TrackSharer = koinInject(),
) {
    var currentPath by remember { mutableStateOf(initialPath) }
    var entries by remember { mutableStateOf<List<DirectoryEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // Long-press → action sheet state, scoped to a single file.
    var contextEntry by remember { mutableStateOf<DirectoryEntry?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentPath) {
        loading = true
        entries = fileBrowser.listEntries(currentPath)
        loading = false
    }

    com.visorcraft.radioshed.nav.PlatformBackHandler {
        val parent = fileBrowser.parentOf(currentPath)
        if (parent != null) {
            currentPath = parent
        } else {
            onClose()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // Two-line title: directory name + monospaced full
                    // path. The path is horizontally scrollable so deep
                    // trees stay readable on phones without truncating.
                    Column {
                        Text(
                            text = currentPath.substringAfterLast('/').ifBlank { currentPath },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = currentPath,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onClose) { Text("Close") }
                },
                actions = {
                    // Up button — only enabled when we're not at the
                    // navigation root. Going up is also possible via
                    // hardware back, but a visible affordance helps
                    // first-time users orient.
                    val canGoUp = fileBrowser.parentOf(currentPath) != null
                    IconButton(
                        onClick = {
                            fileBrowser.parentOf(currentPath)?.let { currentPath = it }
                        },
                        enabled = canGoUp,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowUpward,
                            contentDescription = "Up one folder",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Loading…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
            return@Scaffold
        }
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "(empty)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
            return@Scaffold
        }

        // Pre-compute the audio-file list so a tap on one can seed an
        // ad-hoc queue from all the audio files in the visible folder
        // (matches Library Browser tap-to-play semantics — skip-next
        // walks forward through the folder).
        val audioEntries = remember(entries) { entries.filter { it.isAudio } }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(entries, key = { it.path }) { entry ->
                FolderEntryRow(
                    entry = entry,
                    onTap = {
                        when {
                            entry.isDirectory -> currentPath = entry.path
                            entry.isAudio -> {
                                scope.launch {
                                    playAudioPath(
                                        entry = entry,
                                        audioEntries = audioEntries,
                                        libraryRepo = libraryRepo,
                                        stationPlayback = stationPlayback,
                                        audioEngine = audioEngine,
                                    )
                                }
                            }
                            // Non-audio files: noop. We deliberately
                            // don't try ACTION_VIEW with mime/octet —
                            // that's exactly the kind of unreliable
                            // hand-off this screen is meant to replace.
                            else -> Unit
                        }
                    },
                    onLongPress = {
                        if (entry.isAudio) contextEntry = entry
                    },
                )
            }
        }
    }

    contextEntry?.let { entry ->
        FileContextSheet(
            entry = entry,
            onDismiss = { contextEntry = null },
            onPlay = {
                contextEntry = null
                scope.launch {
                    playAudioPath(
                        entry = entry,
                        audioEntries = audioEntries(entries),
                        libraryRepo = libraryRepo,
                        stationPlayback = stationPlayback,
                        audioEngine = audioEngine,
                    )
                }
            },
            onShare = {
                contextEntry = null
                scope.launch {
                    trackSharer.share(entry.path, entry.name)
                }
            },
        )
    }
}

/**
 * Helper: derive the audio entries from a generic entry list. Inline
 * here so the context sheet doesn't have to capture the LazyColumn's
 * remembered list.
 */
private fun audioEntries(all: List<DirectoryEntry>): List<DirectoryEntry> =
    all.filter { it.isAudio }

/**
 * Resolve [entry]'s path against the indexed library. If the file is
 * already a known Track, hand it to [stationPlayback] so the queue
 * picks up ratings, play counts, etc. Otherwise play it directly via
 * the audio engine as a one-off Playable. Either way the visible
 * audio files in the folder become the next-track context so
 * skip-next walks the folder.
 */
private suspend fun playAudioPath(
    entry: DirectoryEntry,
    audioEntries: List<DirectoryEntry>,
    libraryRepo: LibraryRepository,
    stationPlayback: StationPlayback,
    audioEngine: AudioEngine,
) {
    // Resolve every audio entry against the library so we get full
    // metadata where indexed; missing ones get a synthesized Playable
    // with the filename as title. This keeps the queue intact even
    // when the user browses into an unscanned folder.
    val tracks = audioEntries.mapNotNull { libraryRepo.getTrackByPath(it.path) }
    if (tracks.isNotEmpty()) {
        val startIdx = tracks.indexOfFirst { it.path == entry.path }.coerceAtLeast(0)
        stationPlayback.playTracks(tracks, startIndex = startIdx)
        return
    }
    // Fallback: nothing in this folder is indexed. Play the single
    // tapped file directly via the audio engine so the user still
    // gets audio. They can scan the folder afterwards to enrich
    // future plays.
    audioEngine.play(
        Playable(
            trackId = -1L,
            uri = entry.path,
            title = entry.name.substringBeforeLast('.', entry.name),
            artist = null,
            album = null,
            durationMs = 0L,
            artworkUri = null,
        ),
    )
}

@Composable
private fun FolderEntryRow(
    entry: DirectoryEntry,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val tint = when {
        entry.isDirectory -> MaterialTheme.colorScheme.primary
        entry.isAudio -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    }
    val icon = when {
        entry.isDirectory -> Icons.Filled.Folder
        entry.isAudio -> Icons.Filled.MusicNote
        else -> Icons.Filled.Description
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        color = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = tint,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!entry.isDirectory && entry.sizeBytes > 0L) {
                    Text(
                        text = formatFileSize(entry.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    )
                }
            }
            if (entry.isAudio) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Play",
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileContextSheet(
    entry: DirectoryEntry,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onShare: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    var showPath by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            ContextRow(
                icon = Icons.Filled.PlayArrow,
                label = "Play",
                onClick = onPlay,
            )
            ContextRow(
                icon = Icons.Filled.Description,
                label = "Copy path",
                onClick = {
                    clipboard.setText(AnnotatedString(entry.path))
                    onDismiss()
                },
            )
            ContextRow(
                icon = Icons.Filled.Description,
                label = "Show full path",
                onClick = { showPath = true },
            )
            ContextRow(
                icon = Icons.Filled.Description,
                label = "Share file",
                onClick = onShare,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
    if (showPath) {
        AlertDialog(
            onDismissRequest = { showPath = false },
            title = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Text(
                    text = entry.path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(entry.path))
                    showPath = false
                }) { Text("Copy") }
            },
            dismissButton = {
                TextButton(onClick = { showPath = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun ContextRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val mb = bytes / 1_048_576.0
    if (mb >= 1.0) return "%.1f MB".format(mb)
    val kb = bytes / 1024.0
    if (kb >= 1.0) return "%.0f KB".format(kb)
    return "$bytes B"
}
