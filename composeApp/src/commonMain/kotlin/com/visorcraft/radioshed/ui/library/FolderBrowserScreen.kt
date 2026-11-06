package com.visorcraft.radioshed.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.Playable
import com.visorcraft.radioshed.data.FolderRepository
import com.visorcraft.radioshed.domain.withoutBracketGroups
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.scanner.Scanner
import com.visorcraft.radioshed.source.DirectoryEntry
import com.visorcraft.radioshed.source.DiscoveredFolder
import com.visorcraft.radioshed.source.FileBrowser
import com.visorcraft.radioshed.source.TrackSharer
import com.visorcraft.radioshed.stations.StationPlayback
import com.visorcraft.radioshed.ui.playlists.AddToPlaylistSheet
import com.visorcraft.radioshed.ui.util.formatDurationMs
import com.visorcraft.radioshed.ui.util.formatFileSize
import com.visorcraft.radioshed.ui.util.TooltipIconButton
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * In-app folder browser. Reached from Track Info → "Open folder" so
 * users can navigate the on-disk layout without leaving the app and
 * without depending on a third-party file manager.
 *
 * Capabilities:
 *  - Breadcrumb path bar (each segment tappable for one-tap-up nav)
 *  - Quick-jump chips: Storage / Internal / Music / Downloads
 *  - Sort: Name A→Z, Name Z→A, Recently modified, Largest first
 *  - In-page search filter (toggleable)
 *  - Per-directory header with file/folder counts and total size,
 *    plus Play All / Shuffle All buttons when the directory contains
 *    audio files (non-recursive — just the visible level)
 *  - Tap a folder to drill in; long-press for context (Play all music
 *    here recursive / Shuffle all / Add as music folder / Copy path)
 *  - Tap an audio file to play (seeds an ad-hoc queue from the
 *    directory's audio files; skip-next walks forward); long-press
 *    for context (Play next / Add to queue / Add to playlist /
 *    Copy path / Share)
 *  - Up navigation via top-bar arrow OR hardware back
 *
 * Permission story: `MANAGE_EXTERNAL_STORAGE` already granted to the
 * scanner gives us read access to every mounted volume. Directories
 * the user can't read surface as "(empty)".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FolderBrowserScreen(
    initialPath: String,
    onClose: () -> Unit,
    fileBrowser: FileBrowser = koinInject(),
    libraryRepo: LibraryRepository = koinInject(),
    folderRepo: FolderRepository = koinInject(),
    scanner: Scanner = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    audioEngine: AudioEngine = koinInject(),
    trackSharer: TrackSharer = koinInject(),
    settings: SettingsStore = koinInject(),
) {
    var currentPath by remember { mutableStateOf(initialPath) }
    var entries by remember { mutableStateOf<List<DirectoryEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // Path → duration overlay for audio files that exist in the
    // library index. Populated asynchronously after entries load so a
    // slow DB doesn't gate the directory listing. Pure UI affordance —
    // unindexed files still render with file size.
    var durationsMs by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var presetBookmarks by remember { mutableStateOf<List<DiscoveredFolder>>(emptyList()) }
    // Custom user-pinned folders. Stored separately so we can render
    // them with a distinct (Star) icon and offer a pin/unpin toggle in
    // the folder context sheet without conflating the two lists.
    var pinnedBookmarks by remember {
        mutableStateOf(
            settings.getFileBrowserPinnedFolders().map {
                DiscoveredFolder(name = it.first, path = it.second)
            },
        )
    }

    // Long-press → action sheet state. Two distinct sheets — folder
    // and file context have different action sets.
    var fileContext by remember { mutableStateOf<DirectoryEntry?>(null) }
    var folderContext by remember { mutableStateOf<DirectoryEntry?>(null) }
    // Add-to-playlist target trackId; null when picker is dismissed.
    var addToPlaylistTrackId by remember { mutableStateOf<Long?>(null) }
    // "Add as music folder" status feedback.
    var addAsLibraryStatus by remember { mutableStateOf<String?>(null) }

    // Sort + filter state, persisted via SettingsStore so the user's
    // preference carries across sessions.
    var sort by remember {
        mutableStateOf(
            runCatching { Sort.valueOf(settings.getFileBrowserSort()) }
                .getOrDefault(Sort.NameAsc),
        )
    }
    LaunchedEffect(sort) { settings.setFileBrowserSort(sort.name) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Multi-select state. A non-empty selection automatically engages
    // selection mode — taps then toggle membership instead of playing,
    // and a bottom action bar appears with batch actions. Selection
    // persists across folder changes so the user can build a queue
    // from multiple directories before flushing it.
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectionMode = selectedPaths.isNotEmpty()

    val scope = rememberCoroutineScope()

    LaunchedEffect(currentPath) {
        loading = true
        entries = fileBrowser.listEntries(currentPath)
        loading = false
        // Reset search on directory change — query meant for a
        // previous folder rarely makes sense in the new one.
        if (searchQuery.isNotEmpty()) searchQuery = ""
        // Drop the old folder's durations before fetching the new ones
        // so the UI doesn't briefly show stale "3:47" subtitles on
        // unrelated audio files in the new directory.
        durationsMs = emptyMap()
    }
    // Per-file duration lookups. Previous version published the
    // accumulator via toMap() on every hit — O(n²) copying for
    // folders with hundreds of audio files. Now we batch the
    // publishes: incremental updates every BATCH_SIZE hits keep the
    // UI feeling live, plus a final publish on completion.
    LaunchedEffect(entries) {
        val audioPaths = entries.filter { it.isAudio }.map { it.path }
        if (audioPaths.isEmpty()) return@LaunchedEffect
        val batchSize = 25
        val acc = mutableMapOf<String, Long>()
        var sinceLastFlush = 0
        for (p in audioPaths) {
            val track = libraryRepo.getTrackByPath(p) ?: continue
            if (track.durationMs > 0L) {
                acc[p] = track.durationMs
                sinceLastFlush += 1
                if (sinceLastFlush >= batchSize) {
                    durationsMs = acc.toMap()
                    sinceLastFlush = 0
                }
            }
        }
        if (acc.isNotEmpty()) durationsMs = acc.toMap()
    }
    LaunchedEffect(Unit) {
        presetBookmarks = fileBrowser.commonLocations()
    }

    com.visorcraft.radioshed.nav.PlatformBackHandler {
        when {
            // Selection mode is the most "modal" state — back exits
            // it before navigating folders so a chain of long-presses
            // doesn't accidentally leave the screen on misfire.
            selectionMode -> selectedPaths = emptySet()
            searchOpen -> {
                searchOpen = false
                searchQuery = ""
            }
            else -> {
                val parent = fileBrowser.parentOf(currentPath)
                if (parent != null) {
                    currentPath = parent
                } else {
                    onClose()
                }
            }
        }
    }

    // Sorted + filtered entries, derived so recomposition is cheap.
    val visibleEntries by remember(entries, sort, searchQuery) {
        derivedStateOf {
            val sorted = applySort(entries, sort)
            if (searchQuery.isBlank()) {
                sorted
            } else {
                sorted.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }
        }
    }

    // Per-directory stats — folder count, audio-file count, total
    // audio size. Computed once per entries change, used in both the
    // directory header and the Play All / Shuffle All affordances.
    val stats by remember(entries) {
        derivedStateOf {
            val folders = entries.count { it.isDirectory }
            val audio = entries.filter { it.isAudio }
            val totalAudioBytes = audio.sumOf { it.sizeBytes }
            DirStats(
                folderCount = folders,
                audioCount = audio.size,
                totalAudioBytes = totalAudioBytes,
            )
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        when {
                            selectionMode -> {
                                Text(
                                    text = "${selectedPaths.size} selected",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            searchOpen -> {
                                // In-place search field replaces the
                                // breadcrumb when active. Auto-focus
                                // is handled by the field's own
                                // FocusRequester pattern below.
                                val keyboardController = LocalSoftwareKeyboardController.current
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = { Text("Filter this folder…") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions =
                                        androidx.compose.foundation.text.KeyboardOptions(
                                            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                                        ),
                                    keyboardActions =
                                        androidx.compose.foundation.text.KeyboardActions(
                                            onDone = { keyboardController?.hide() },
                                        ),
                                )
                            }
                            else -> {
                                Text(
                                    text = currentPath.substringAfterLast('/').ifBlank { currentPath },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    softWrap = false,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (selectionMode) {
                            TooltipIconButton(
                                tooltip = "Exit selection",
                                onClick = { selectedPaths = emptySet() },
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Exit selection")
                            }
                        } else {
                            TextButton(onClick = onClose) { Text("Close") }
                        }
                    },
                    actions = {
                        if (selectionMode) {
                            // "Select all audio in this folder" — the
                            // most-asked batch action ("queue this
                            // album"). Files outside the current
                            // directory are unaffected.
                            TooltipIconButton(
                                tooltip = "Select all audio in this folder",
                                onClick = {
                                    val visibleAudio =
                                        entries
                                            .filter { it.isAudio }
                                            .map { it.path }
                                    selectedPaths = selectedPaths + visibleAudio
                                },
                            ) {
                                Icon(
                                    Icons.Filled.SelectAll,
                                    contentDescription = "Select all audio in this folder",
                                )
                            }
                        } else {
                            if (!searchOpen) {
                                TooltipIconButton(
                                    tooltip = "Filter folder",
                                    onClick = { searchOpen = true },
                                ) {
                                    Icon(
                                        Icons.Filled.Search,
                                        contentDescription = "Filter folder",
                                    )
                                }
                            } else {
                                TooltipIconButton(
                                    tooltip = "Close filter",
                                    onClick = {
                                        searchOpen = false
                                        searchQuery = ""
                                    },
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = "Close filter")
                                }
                            }
                            Box {
                                TooltipIconButton(
                                    tooltip = "Sort",
                                    onClick = { sortMenuOpen = true },
                                ) {
                                    Icon(
                                        Icons.Filled.SortByAlpha,
                                        contentDescription = "Sort",
                                    )
                                }
                                DropdownMenu(
                                    expanded = sortMenuOpen,
                                    onDismissRequest = { sortMenuOpen = false },
                                ) {
                                    Sort.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.label) },
                                            onClick = {
                                                sort = option
                                                sortMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                            val canGoUp = fileBrowser.parentOf(currentPath) != null
                            TooltipIconButton(
                                tooltip = "Up one folder",
                                onClick = {
                                    fileBrowser.parentOf(currentPath)?.let { currentPath = it }
                                },
                                enabled = canGoUp,
                            ) {
                                Icon(
                                    Icons.Filled.ArrowUpward,
                                    contentDescription = "Up one folder",
                                )
                            }
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor =
                                if (selectionMode) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                        ),
                )
                // Breadcrumb row — always visible, scrolls horizontally
                // for deep paths. Each segment is clickable; the trailing
                // (current) segment is rendered in primary tint with no
                // click handler.
                if (!searchOpen && !selectionMode) {
                    BreadcrumbBar(
                        path = currentPath,
                        onJump = { newPath -> currentPath = newPath },
                    )
                }
                // Quick-jump bookmarks. Hidden when search or
                // selection is active so the top bar stays focused
                // on the active mode.
                if (!searchOpen && !selectionMode &&
                    (presetBookmarks.isNotEmpty() || pinnedBookmarks.isNotEmpty())
                ) {
                    BookmarksRow(
                        presets = presetBookmarks,
                        pinned = pinnedBookmarks,
                        currentPath = currentPath,
                        onPick = { currentPath = it },
                        onUnpin = { path ->
                            settings.unpinFileBrowserFolder(path)
                            // Re-read from storage so the chip row
                            // reflects whatever the store actually
                            // ended up holding (handles the empty
                            // case where the key is removed entirely
                            // by unpin).
                            pinnedBookmarks =
                                settings.getFileBrowserPinnedFolders().map {
                                    DiscoveredFolder(name = it.first, path = it.second)
                                }
                        },
                    )
                }
            }
        },
        bottomBar = {
            if (selectionMode) {
                SelectionActionBar(
                    selectedCount = selectedPaths.size,
                    onPlay = {
                        scope.launch {
                            val tracks =
                                selectedPaths.mapNotNull {
                                    libraryRepo.getTrackByPath(it)
                                }
                            if (tracks.isNotEmpty()) {
                                stationPlayback.playTracks(tracks, startIndex = 0)
                            }
                            selectedPaths = emptySet()
                        }
                    },
                    onPlayNext = {
                        scope.launch {
                            val tracks =
                                selectedPaths.mapNotNull {
                                    libraryRepo.getTrackByPath(it)
                                }
                            // Reverse so earlier-selected items end
                            // up earlier in the priority queue (each
                            // playNext prepends).
                            tracks.reversed().forEach { stationPlayback.playNext(it) }
                            selectedPaths = emptySet()
                        }
                    },
                    onAddToQueue = {
                        scope.launch {
                            val tracks =
                                selectedPaths.mapNotNull {
                                    libraryRepo.getTrackByPath(it)
                                }
                            tracks.forEach { stationPlayback.addToQueue(it) }
                            selectedPaths = emptySet()
                        }
                    },
                    onShuffle = {
                        scope.launch {
                            val tracks =
                                selectedPaths.mapNotNull {
                                    libraryRepo.getTrackByPath(it)
                                }
                            if (tracks.isNotEmpty()) {
                                stationPlayback.playTracks(
                                    tracks.shuffled(),
                                    startIndex = 0,
                                )
                            }
                            selectedPaths = emptySet()
                        }
                    },
                )
            }
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

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Directory stats + Play All / Shuffle All affordances.
            // Hidden when the directory is empty so we don't waste
            // space on unreadable / pristine folders.
            if (entries.isNotEmpty()) {
                DirHeader(
                    stats = stats,
                    onPlayAll = {
                        scope.launch {
                            playAudioPath(
                                tappedEntry = null,
                                audioEntries = entries.filter { it.isAudio },
                                libraryRepo = libraryRepo,
                                stationPlayback = stationPlayback,
                                audioEngine = audioEngine,
                            )
                        }
                    },
                    onShuffleAll = {
                        scope.launch {
                            val audio = entries.filter { it.isAudio }
                            playAudioPath(
                                tappedEntry = null,
                                audioEntries = audio.shuffled(),
                                libraryRepo = libraryRepo,
                                stationPlayback = stationPlayback,
                                audioEngine = audioEngine,
                            )
                        }
                    },
                )
            }

            addAsLibraryStatus?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { addAsLibraryStatus = null }) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            if (visibleEntries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text =
                            if (searchQuery.isNotBlank()) {
                                "No matches in this folder."
                            } else {
                                "(empty)"
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                }
                return@Scaffold
            }

            // Pre-compute the audio-file list (post-sort, pre-filter)
            // so a tap on one can seed an ad-hoc queue from the
            // directory's audio files even if the user has filtered
            // the visible list — feels more useful than narrowing
            // the queue to just what's visible.
            val audioEntries =
                remember(entries, sort) {
                    applySort(entries.filter { it.isAudio }, sort)
                }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(visibleEntries, key = { it.path }) { entry ->
                    FolderEntryRow(
                        entry = entry,
                        durationMs = durationsMs[entry.path],
                        isSelected = entry.path in selectedPaths,
                        selectionMode = selectionMode,
                        onTap = {
                            when {
                                // In selection mode, tap toggles audio
                                // membership. Folders still navigate
                                // — the user often wants to drill into
                                // a sibling folder while building a
                                // multi-folder selection.
                                selectionMode && entry.isAudio -> {
                                    selectedPaths =
                                        if (entry.path in selectedPaths) {
                                            selectedPaths - entry.path
                                        } else {
                                            selectedPaths + entry.path
                                        }
                                }
                                entry.isDirectory -> currentPath = entry.path
                                entry.isAudio -> {
                                    scope.launch {
                                        playAudioPath(
                                            tappedEntry = entry,
                                            audioEntries = audioEntries,
                                            libraryRepo = libraryRepo,
                                            stationPlayback = stationPlayback,
                                            audioEngine = audioEngine,
                                        )
                                    }
                                }
                                else -> Unit
                            }
                        },
                        onLongPress = {
                            when {
                                // Long-press on an audio file (outside
                                // selection mode) opens the rich
                                // file-context sheet — Play next, Add
                                // to queue, Add to playlist, Share,
                                // Copy path, etc. Multi-select is
                                // still reachable from inside that
                                // sheet via the "Select" action.
                                entry.isAudio && !selectionMode -> fileContext = entry
                                entry.isDirectory && !selectionMode -> folderContext = entry
                                entry.isAudio && selectionMode -> {
                                    // Reuse long-press inside selection
                                    // mode as a quick toggle, mirroring
                                    // tap. Predictable for power users.
                                    selectedPaths =
                                        if (entry.path in selectedPaths) {
                                            selectedPaths - entry.path
                                        } else {
                                            selectedPaths + entry.path
                                        }
                                }
                                else -> Unit
                            }
                        },
                    )
                }
            }
        }
    }

    fileContext?.let { entry ->
        FileContextSheet(
            entry = entry,
            audioEntries = entries.filter { it.isAudio },
            libraryRepo = libraryRepo,
            stationPlayback = stationPlayback,
            audioEngine = audioEngine,
            trackSharer = trackSharer,
            scope = scope,
            onDismiss = { fileContext = null },
            onAddToPlaylistTrack = { trackId ->
                fileContext = null
                addToPlaylistTrackId = trackId
            },
            onStartSelection = {
                fileContext = null
                selectedPaths = setOf(entry.path)
            },
        )
    }
    folderContext?.let { entry ->
        val isPinned = pinnedBookmarks.any { it.path == entry.path }
        FolderContextSheet(
            entry = entry,
            isPinned = isPinned,
            fileBrowser = fileBrowser,
            libraryRepo = libraryRepo,
            stationPlayback = stationPlayback,
            audioEngine = audioEngine,
            folderRepo = folderRepo,
            scanner = scanner,
            scope = scope,
            onDismiss = { folderContext = null },
            onOpen = {
                folderContext = null
                currentPath = entry.path
            },
            onAddedAsLibrary = { msg ->
                folderContext = null
                addAsLibraryStatus = msg
            },
            onTogglePin = {
                if (isPinned) {
                    settings.unpinFileBrowserFolder(entry.path)
                } else {
                    settings.pinFileBrowserFolder(entry.name, entry.path)
                }
                // Re-read from storage rather than mutating the
                // in-memory list directly. SettingsStore enforces
                // FILE_BROWSER_PIN_CAP and tab/newline sanitization;
                // a naive in-memory prepend would let the visible
                // list outgrow the cap and drift from the actual
                // persisted shape (e.g. a pinned folder with a tab
                // in its name renders one way in the bookmarks row
                // and another after app restart).
                pinnedBookmarks =
                    settings.getFileBrowserPinnedFolders().map {
                        DiscoveredFolder(name = it.first, path = it.second)
                    }
                folderContext = null
            },
        )
    }
    addToPlaylistTrackId?.let { tid ->
        AddToPlaylistSheet(
            trackId = tid,
            onDismiss = { addToPlaylistTrackId = null },
        )
    }
}

private enum class Sort(val label: String) {
    NameAsc("Name A → Z"),
    NameDesc("Name Z → A"),
    RecentlyModified("Recently modified"),
    LargestFirst("Largest first"),
}

private fun applySort(
    entries: List<DirectoryEntry>,
    sort: Sort,
): List<DirectoryEntry> {
    // Always group dirs first then files — most file managers do
    // that and it matches user expectation. Sort order applies
    // within each group.
    val (dirs, files) = entries.partition { it.isDirectory }
    val cmp =
        when (sort) {
            Sort.NameAsc -> compareBy<DirectoryEntry> { it.name.lowercase() }
            Sort.NameDesc -> compareByDescending { it.name.lowercase() }
            Sort.RecentlyModified -> compareByDescending { it.lastModifiedMs }
            Sort.LargestFirst -> compareByDescending { it.sizeBytes }
        }
    return dirs.sortedWith(cmp) + files.sortedWith(cmp)
}

private data class DirStats(
    val folderCount: Int,
    val audioCount: Int,
    val totalAudioBytes: Long,
)

@Composable
private fun BreadcrumbBar(
    path: String,
    onJump: (String) -> Unit,
) {
    // Build (label, full-path-up-to-here) pairs. Edge cases:
    //  - "/storage" → segments: ["", "storage"] → render "/" + "storage"
    //  - "/storage/emulated/0/Music" → "/" → "storage" → "emulated" → "0" → "Music"
    val segments =
        remember(path) {
            val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
            val out = mutableListOf<Pair<String, String>>()
            out += "/" to "/"
            var cumulative = ""
            for (p in parts) {
                cumulative += "/$p"
                out += p to cumulative
            }
            out
        }
    LazyRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(segments) { (label, fullPath) ->
            val isLast = fullPath == path
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = { if (!isLast) onJump(fullPath) },
                    enabled = !isLast,
                    color = Color.Transparent,
                    modifier = Modifier.padding(horizontal = 2.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        color =
                            if (isLast) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                            },
                        fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                if (!isLast) {
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarksRow(
    presets: List<DiscoveredFolder>,
    pinned: List<DiscoveredFolder>,
    currentPath: String,
    onPick: (String) -> Unit,
    onUnpin: (String) -> Unit,
) {
    // Render presets first (Bookmark icon) then user-pinned folders
    // (Star icon). The split is purely cosmetic — both jump to a path
    // on tap. Long-press on a pinned chip removes it; long-press on
    // a preset is a no-op since presets are derived from real
    // filesystem locations rather than user state.
    val items =
        remember(presets, pinned) {
            // Bookmark = preset, Star = user-pinned. We piggyback on the
            // chip's leading icon to communicate the source.
            presets.map { it to false } + pinned.map { it to true }
        }
    LazyRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items) { (bm, isUserPinned) ->
            val active = bm.path == currentPath
            Surface(
                color = Color.Transparent,
                modifier =
                    Modifier.combinedClickable(
                        onClick = { if (!active) onPick(bm.path) },
                        onLongClick = { if (isUserPinned) onUnpin(bm.path) },
                    ),
            ) {
                AssistChip(
                    onClick = { if (!active) onPick(bm.path) },
                    enabled = !active,
                    label = { Text(bm.name) },
                    leadingIcon = {
                        Icon(
                            imageVector =
                                if (isUserPinned) {
                                    Icons.Filled.Star
                                } else {
                                    Icons.Filled.Bookmark
                                },
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    },
                    colors =
                        AssistChipDefaults.assistChipColors(
                            containerColor =
                                if (active) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                        ),
                )
            }
        }
    }
}

@Composable
private fun SelectionActionBar(
    selectedCount: Int,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onShuffle: () -> Unit,
) {
    // Disable actions when nothing is selected. selectedCount==0
    // shouldn't render in practice since selectionMode is derived
    // from emptiness, but the guard keeps the bar safe to call from
    // anywhere.
    val enabled = selectedCount > 0
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectionAction(Icons.Filled.PlayArrow, "Play", enabled, onPlay)
            SelectionAction(Icons.Filled.Shuffle, "Shuffle", enabled, onShuffle)
            SelectionAction(Icons.Filled.PlayArrow, "Play next", enabled, onPlayNext)
            SelectionAction(
                Icons.AutoMirrored.Filled.QueueMusic,
                "Add to queue",
                enabled,
                onAddToQueue,
            )
        }
    }
}

@Composable
private fun SelectionAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint =
                if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color =
                if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                },
            maxLines = 1,
        )
    }
}

@Composable
private fun DirHeader(
    stats: DirStats,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            val parts =
                buildList {
                    if (stats.folderCount > 0) {
                        add("${stats.folderCount} folder${if (stats.folderCount == 1) "" else "s"}")
                    }
                    if (stats.audioCount > 0) {
                        add("${stats.audioCount} audio")
                        add(formatFileSize(stats.totalAudioBytes))
                    }
                }
            Text(
                text = if (parts.isEmpty()) "—" else parts.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            if (stats.audioCount > 0) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onPlayAll,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text("Play all")
                    }
                    OutlinedButton(
                        onClick = onShuffleAll,
                        modifier = Modifier.weight(1f),
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary,
                            ),
                    ) {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text("Shuffle")
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderEntryRow(
    entry: DirectoryEntry,
    durationMs: Long?,
    isSelected: Boolean,
    selectionMode: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val tint =
        when {
            entry.isDirectory -> MaterialTheme.colorScheme.primary
            entry.isAudio -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        }
    val icon =
        when {
            entry.isDirectory -> Icons.Filled.Folder
            entry.isAudio -> Icons.Filled.MusicNote
            else -> Icons.Filled.Description
        }
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        color =
            if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            } else {
                Color.Transparent
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
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
                if (!entry.isDirectory && (entry.sizeBytes > 0L || durationMs != null)) {
                    val subtitle =
                        buildList {
                            durationMs?.let { add(formatDurationMs(it, showHours = true)) }
                            if (entry.sizeBytes > 0L) add(formatFileSize(entry.sizeBytes))
                        }.joinToString(" · ")
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    )
                }
            }
            // Trailing accessory — selection check in selection mode
            // (only meaningful for audio files since folders aren't
            // selectable), otherwise the standard play affordance.
            when {
                selectionMode && entry.isAudio -> {
                    Icon(
                        imageVector =
                            if (isSelected) {
                                Icons.Filled.CheckCircle
                            } else {
                                Icons.Filled.RadioButtonUnchecked
                            },
                        contentDescription = if (isSelected) "Selected" else "Not selected",
                        tint =
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            },
                    )
                }
                entry.isAudio -> {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                    )
                }
                else -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileContextSheet(
    entry: DirectoryEntry,
    audioEntries: List<DirectoryEntry>,
    libraryRepo: LibraryRepository,
    stationPlayback: StationPlayback,
    audioEngine: AudioEngine,
    trackSharer: TrackSharer,
    scope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit,
    onAddToPlaylistTrack: (Long) -> Unit,
    onStartSelection: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val copyToClipboard = com.visorcraft.radioshed.ui.util.rememberCopyToClipboard()
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
                onClick = {
                    onDismiss()
                    scope.launch {
                        playAudioPath(
                            tappedEntry = entry,
                            audioEntries = audioEntries,
                            libraryRepo = libraryRepo,
                            stationPlayback = stationPlayback,
                            audioEngine = audioEngine,
                        )
                    }
                },
            )
            ContextRow(
                icon = Icons.Filled.PlayArrow,
                label = "Play next",
                onClick = {
                    onDismiss()
                    scope.launch {
                        val track = libraryRepo.getTrackByPath(entry.path)
                        if (track != null) stationPlayback.playNext(track)
                    }
                },
            )
            ContextRow(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                label = "Add to queue",
                onClick = {
                    onDismiss()
                    scope.launch {
                        val track = libraryRepo.getTrackByPath(entry.path)
                        if (track != null) stationPlayback.addToQueue(track)
                    }
                },
            )
            ContextRow(
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                label = "Add to playlist…",
                onClick = {
                    scope.launch {
                        val track = libraryRepo.getTrackByPath(entry.path)
                        if (track != null) {
                            onAddToPlaylistTrack(track.id)
                        } else {
                            onDismiss()
                        }
                    }
                },
            )
            ContextRow(
                icon = Icons.Filled.Description,
                label = "Copy path",
                onClick = {
                    copyToClipboard(entry.path)
                    onDismiss()
                },
            )
            ContextRow(
                icon = Icons.Filled.Info,
                label = "Show full path",
                onClick = { showPath = true },
            )
            ContextRow(
                icon = Icons.Filled.Description,
                label = "Share file",
                onClick = {
                    onDismiss()
                    scope.launch { trackSharer.share(entry.path, entry.name) }
                },
            )
            ContextRow(
                icon = Icons.Filled.SelectAll,
                label = "Select…",
                onClick = onStartSelection,
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
                    copyToClipboard(entry.path)
                    showPath = false
                }) { Text("Copy") }
            },
            dismissButton = {
                TextButton(onClick = { showPath = false }) { Text("Close") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderContextSheet(
    entry: DirectoryEntry,
    isPinned: Boolean,
    fileBrowser: FileBrowser,
    libraryRepo: LibraryRepository,
    stationPlayback: StationPlayback,
    audioEngine: AudioEngine,
    folderRepo: FolderRepository,
    scanner: Scanner,
    scope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onAddedAsLibrary: (String) -> Unit,
    onTogglePin: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val copyToClipboard = com.visorcraft.radioshed.ui.util.rememberCopyToClipboard()
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
                icon = Icons.Filled.Folder,
                label = "Open",
                onClick = onOpen,
            )
            ContextRow(
                icon = Icons.Filled.PlayArrow,
                label = "Play all music here",
                onClick = {
                    onDismiss()
                    scope.launch {
                        val audio = fileBrowser.listAudioRecursive(entry.path)
                        playAudioPath(
                            tappedEntry = null,
                            audioEntries = audio,
                            libraryRepo = libraryRepo,
                            stationPlayback = stationPlayback,
                            audioEngine = audioEngine,
                        )
                    }
                },
            )
            ContextRow(
                icon = Icons.Filled.Shuffle,
                label = "Shuffle all music here",
                onClick = {
                    onDismiss()
                    scope.launch {
                        val audio = fileBrowser.listAudioRecursive(entry.path).shuffled()
                        playAudioPath(
                            tappedEntry = null,
                            audioEntries = audio,
                            libraryRepo = libraryRepo,
                            stationPlayback = stationPlayback,
                            audioEngine = audioEngine,
                        )
                    }
                },
            )
            ContextRow(
                icon = Icons.Filled.LibraryAdd,
                label = "Add as music folder",
                onClick = {
                    scope.launch {
                        // Idempotent — if the folder is already
                        // registered we skip the insert and just
                        // re-trigger a scan. Same defensive pattern
                        // the Onboarding folder step uses.
                        val existing = folderRepo.getByUri(entry.path)
                        val id =
                            existing?.id ?: folderRepo.insert(
                                displayName = entry.name,
                                uri = entry.path,
                                recursive = true,
                            )
                        scanner.scanFolder(id).collect { /* fire and forget */ }
                        val verb = if (existing != null) "Re-scanned" else "Added & scanned"
                        onAddedAsLibrary("$verb \"${entry.name}\".")
                    }
                },
            )
            ContextRow(
                icon = if (isPinned) Icons.Filled.Star else Icons.Filled.StarBorder,
                label = if (isPinned) "Unpin from bookmarks" else "Pin to bookmarks",
                onClick = onTogglePin,
            )
            ContextRow(
                icon = Icons.Filled.Description,
                label = "Copy path",
                onClick = {
                    copyToClipboard(entry.path)
                    onDismiss()
                },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ContextRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
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

/**
 * Resolve [tappedEntry]'s path against the indexed library. If the
 * file is already a known Track, hand it (and the surrounding audio
 * entries) to [stationPlayback] so the queue picks up ratings, play
 * counts, etc. Otherwise play it directly via the audio engine as a
 * one-off Playable.
 *
 * When [tappedEntry] is null (Play All / Shuffle All from header or
 * folder context), starts at index 0 of [audioEntries].
 */
private suspend fun playAudioPath(
    tappedEntry: DirectoryEntry?,
    audioEntries: List<DirectoryEntry>,
    libraryRepo: LibraryRepository,
    stationPlayback: StationPlayback,
    audioEngine: AudioEngine,
) {
    if (audioEntries.isEmpty()) return
    val pathToTrack = libraryRepo.tracksByExactPaths(audioEntries.map { it.path })
    val tracks = audioEntries.mapNotNull { pathToTrack[it.path] }
    if (tracks.isNotEmpty()) {
        val startIdx =
            if (tappedEntry != null) {
                tracks.indexOfFirst { it.path == tappedEntry.path }.coerceAtLeast(0)
            } else {
                0
            }
        stationPlayback.playTracks(tracks, startIndex = startIdx)
        return
    }
    // Fallback: nothing in this folder is indexed. Play the first
    // tapped audio file directly. Skip-next won't work — the user
    // should "Add as music folder" if they want full queueing.
    val target = tappedEntry ?: audioEntries.first()
    audioEngine.play(
        Playable(
            trackId = -1L,
            uri = target.path,
            title = target.name.substringBeforeLast('.', target.name).withoutBracketGroups(),
            artist = null,
            album = null,
            durationMs = 0L,
            artworkUri = null,
        ),
    )
}

