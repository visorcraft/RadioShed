package com.visorcraft.radioshed.ui.playlists

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.data.PlaylistRepository
import com.visorcraft.radioshed.data.RatingRepository
import com.visorcraft.radioshed.domain.Playlist
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Top-level playlist library — list of all playlists with FAB to create
 * a new one. Tapping a row drills into [PlaylistDetailScreen]. Empty
 * state explains how to add tracks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    onBack: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenLikedSongs: () -> Unit = {},
    repo: PlaylistRepository = koinInject(),
    ratingRepo: RatingRepository = koinInject(),
) {
    val playlists by repo.observeAll().collectAsState(initial = emptyList())
    val likedCount by ratingRepo.observeLikedCount().collectAsState(initial = 0L)
    var createOpen by remember { mutableStateOf(false) }
    // Long-pressing a playlist row opens an inline rename dialog —
    // matches the pattern users learn from station / track context
    // sheets (long-press = "what else can I do with this?"). Dialog
    // routes through PlaylistRepository.rename so the trim / blank-
    // name guard is centralised.
    var renamingPlaylist by remember { mutableStateOf<Playlist?>(null) }
    // Long-press a playlist row → bottom sheet with Rename / Delete.
    // Rename routes through the existing dialog; Delete prompts a
    // confirm before nuking the playlist (the action is irreversible
    // and the row clearly identifies the target, so a confirm dialog
    // is preferable to undo-on-snackbar for now).
    var actionPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var deleteConfirm by remember { mutableStateOf<Playlist?>(null) }
    // In-page filter — appears above the list when there are enough
    // playlists for find-by-scroll to be friction. Below 6 the list
    // already fits a phone screen and the filter would be clutter.
    var listFilter by remember { mutableStateOf("") }
    // Sort order for the playlist list. Default is the natural
    // PlaylistRepository.observeAll() order (most-recently-created
    // first, matching the user's mental "what did I just make"
    // expectation). Session-local — resets on screen exit since
    // user-named playlists don't churn often enough to justify a
    // persisted pref.
    var sortOrder by remember { mutableStateOf(PlaylistsSort.RecentlyCreated) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val libraryRepo: com.visorcraft.radioshed.data.LibraryRepository = koinInject()
    // M3U import via system file picker. The picked .m3u/.m3u8 bytes
    // are decoded UTF-8, parsed line-by-line (skip blank + #-prefixed
    // metadata), each path resolved through `getTrackByPathOrSuffix`
    // (so a relative path or suffix-match works the same way as the
    // backup restore flow), and the resulting tracks land in a fresh
    // playlist named after the file.
    val importPicker = com.visorcraft.radioshed.ui.util.rememberFilePicker { bytes ->
        scope.launch {
            val text = bytes.decodeToString()
            val paths = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
            if (paths.isEmpty()) {
                importStatus = "No track entries found in the selected file."
                return@launch
            }
            var added = 0
            var missing = 0
            // Default name is the timestamp; the user can rename
            // after import via long-press on the playlist row.
            val name = "Imported m3u (${com.visorcraft.radioshed.data.formatShortDate(
                com.visorcraft.radioshed.data.nowMillis(),
            )})"
            val playlistId = repo.insert(name = name, description = null)
            paths.forEachIndexed { idx, path ->
                val track = libraryRepo.getTrackByPathOrSuffix(path)
                if (track != null) {
                    runCatching { repo.addTrack(playlistId, track.id, position = idx) }
                        .onSuccess { added += 1 }
                        .onFailure { missing += 1 }
                } else {
                    missing += 1
                }
            }
            importStatus = "Imported $added track" +
                (if (added == 1) "" else "s") +
                (if (missing > 0) " · $missing not in library" else "") + "."
        }
    }

    // Resolve cover art per playlist whenever the list changes. The
    // queries are LIMIT 1 + indexed, so this is cheap even with dozens
    // of playlists.
    var coverByPlaylistId by remember { mutableStateOf<Map<Long, String?>>(emptyMap()) }
    var trackCountByPlaylistId by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    LaunchedEffect(playlists.map { it.id }) {
        coverByPlaylistId = playlists.associate { it.id to repo.firstTrackArtwork(it.id) }
        trackCountByPlaylistId = playlists.associate { it.id to repo.trackCount(it.id) }
    }

    // Aggregate track count across every user playlist. Recomputed
    // whenever the per-playlist counts map changes (which already
    // re-runs when the playlist list mutates), so this stays in sync
    // without an extra Flow / DB query.
    val totalTrackCount = remember(trackCountByPlaylistId) {
        trackCountByPlaylistId.values.sum()
    }
    // Apply filter, then sort. Filter first so the visible-count
    // math stays consistent. RecentlyCreated falls back to the
    // natural Flow order (createdAt desc); the explicit sort still
    // renders the same order — keeping the lambda symmetric reads
    // better than a `when` skipping the sort branch entirely.
    val filteredPlaylists = remember(playlists, listFilter, sortOrder, trackCountByPlaylistId) {
        val base = if (listFilter.isBlank()) playlists
                   else playlists.filter { it.name.contains(listFilter, ignoreCase = true) }
        when (sortOrder) {
            PlaylistsSort.RecentlyCreated -> base.sortedByDescending { it.createdAt }
            PlaylistsSort.NameAsc -> base.sortedBy { it.name.lowercase() }
            PlaylistsSort.NameDesc -> base.sortedByDescending { it.name.lowercase() }
            PlaylistsSort.TrackCountDesc -> base.sortedByDescending {
                trackCountByPlaylistId[it.id] ?: 0L
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Playlists")
                        if (playlists.isNotEmpty()) {
                            // When the filter is active, subtitle
                            // reflects the visible-count vs total —
                            // matters more to the user mid-search than
                            // the lifetime track total. Empty filter
                            // falls back to the standard summary.
                            val subtitle = if (listFilter.isNotBlank()) {
                                "${filteredPlaylists.size} of ${playlists.size} " +
                                    (if (playlists.size == 1) "playlist" else "playlists")
                            } else {
                                "${playlists.size} playlist" +
                                    (if (playlists.size == 1) "" else "s") +
                                    " · $totalTrackCount track" +
                                    (if (totalTrackCount == 1L) "" else "s")
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Sort,
                                contentDescription = "Sort playlists",
                            )
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                        ) {
                            PlaylistsSort.entries.forEach { opt ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(opt.label, modifier = Modifier.weight(1f))
                                            if (sortOrder == opt) {
                                                Icon(
                                                    imageVector = Icons.Filled.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        sortOrder = opt
                                        sortMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    TextButton(onClick = { importPicker("audio/x-mpegurl") }) {
                        Text("Import")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { createOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New playlist")
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        // Liked Songs is always present, even before the user has any
        // user-created playlists or has hearted anything yet — it
        // doubles as a discovery affordance for the heart-toggle
        // mechanic. Pinned to the top, never deletable from this view.
        val listState = rememberLazyListState()
        // Show the scroll-to-top FAB once the user has moved past the
        // playlist count where reaching the top via flick is friction.
        // Threshold of 8 matches the rest of the app's FABs (Library,
        // Playlist detail, Station detail) so the behaviour is
        // consistent across screens.
        val showScrollTop by remember {
            derivedStateOf { listState.firstVisibleItemIndex > 8 }
        }
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            importStatus?.let { msg ->
                item(key = "import-status") {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
            item(key = "liked-songs") {
                LikedSongsRow(count = likedCount, onTap = onOpenLikedSongs)
            }
            if (playlists.size >= 6) {
                item(key = "filter") {
                    androidx.compose.material3.OutlinedTextField(
                        value = listFilter,
                        onValueChange = { listFilter = it },
                        placeholder = { Text("Filter playlists…") },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Filled.Search, contentDescription = null)
                        },
                        trailingIcon = if (listFilter.isNotEmpty()) {
                            {
                                IconButton(onClick = { listFilter = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear")
                                }
                            }
                        } else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            if (playlists.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp, start = 24.dp, end = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No playlists yet — tap + to create one, then long-press any track to add it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        )
                    }
                }
            } else {
                items(filteredPlaylists, key = { it.id }) { playlist ->
                    PlaylistRow(
                        playlist = playlist,
                        coverUri = coverByPlaylistId[playlist.id],
                        trackCount = trackCountByPlaylistId[playlist.id] ?: 0L,
                        onTap = { onOpenPlaylist(playlist.id) },
                        onLongPress = { actionPlaylist = playlist },
                    )
                }
                if (filteredPlaylists.isEmpty() && listFilter.isNotBlank()) {
                    item(key = "no-match") {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                        ) {
                            Text(
                                "No playlists match \"$listFilter\".",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            )
                            Spacer(Modifier.height(8.dp))
                            // CTA to create a playlist with the
                            // user's filter text as the name. Removes
                            // friction in the "couldn't find what I
                            // wanted, want to start a new one named
                            // for this idea" flow without needing to
                            // tap the FAB then re-type.
                            TextButton(onClick = {
                                val name = listFilter.trim()
                                listFilter = ""
                                scope.launch {
                                    if (name.isNotBlank()) repo.insert(name = name)
                                }
                            }) {
                                Text("Create \"${listFilter.trim()}\"")
                            }
                        }
                    }
                }
            }
        }
        if (showScrollTop) {
            // BottomStart so we don't overlap with the primary "New
            // playlist" FAB at BottomEnd. SmallFloatingActionButton
            // matches the size used on PlaylistDetailScreen and
            // StationDetailScreen so the gesture target is consistent.
            SmallFloatingActionButton(
                onClick = {
                    scope.launch { listState.animateScrollToItem(0) }
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = "Scroll to top",
                )
            }
        }
        }
    }

    if (createOpen) {
        CreatePlaylistDialog(
            onDismiss = { createOpen = false },
            onCreate = { name ->
                scope.launch {
                    if (name.isNotBlank()) repo.insert(name = name.trim())
                }
                createOpen = false
            },
        )
    }

    actionPlaylist?.let { target ->
        PlaylistActionsSheet(
            playlist = target,
            onDismiss = { actionPlaylist = null },
            onRename = {
                actionPlaylist = null
                renamingPlaylist = target
            },
            onDelete = {
                actionPlaylist = null
                deleteConfirm = target
            },
        )
    }

    deleteConfirm?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteConfirm = null },
            title = { Text("Delete playlist?") },
            text = {
                Text(
                    "\"${target.name}\" will be removed. The tracks " +
                        "themselves stay in your library — only the " +
                        "playlist itself is deleted.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = target.id
                        deleteConfirm = null
                        scope.launch { repo.delete(id) }
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = null }) { Text("Cancel") }
            },
        )
    }

    renamingPlaylist?.let { target ->
        RenamePlaylistDialog(
            initial = target.name,
            onDismiss = { renamingPlaylist = null },
            onConfirm = { newName ->
                scope.launch {
                    if (newName.isNotBlank() && newName != target.name) {
                        repo.rename(target.id, newName.trim())
                    }
                }
                renamingPlaylist = null
            },
        )
    }
}

/**
 * Bottom sheet shown when the user long-presses a playlist row.
 * Currently exposes Rename + Delete; new playlist-level actions
 * (export, duplicate, etc.) plug in here as additional rows so the
 * long-press surface stays the single discovery point. Pattern
 * mirrors StationActionSheet on Stations Home.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistActionsSheet(
    playlist: Playlist,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ListItem(
                modifier = Modifier.clickable(onClick = onRename),
                leadingContent = {
                    Icon(
                        imageVector = Icons.Filled.DriveFileRenameOutline,
                        contentDescription = null,
                    )
                },
                headlineContent = { Text("Rename") },
            )
            ListItem(
                modifier = Modifier.clickable(onClick = onDelete),
                leadingContent = {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                headlineContent = {
                    Text(
                        "Delete",
                        color = MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RenamePlaylistDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename playlist") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("Playlist name") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = {
                        if (text.trim().isNotBlank()) onConfirm(text.trim())
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.trim().isNotBlank()) onConfirm(text.trim()) },
                enabled = text.trim().isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Pinned Liked Songs row at the top of the Playlists list. Visually
 * distinct from regular playlists (heart icon, brand-amber tinted
 * artwork tile) so the user reads it as a special always-on
 * collection rather than another user playlist.
 */
@Composable
private fun LikedSongsRow(count: Long, onTap: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Liked songs",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (count == 0L) "Tap the heart on any track to start"
                           else "$count track${if (count == 1L) "" else "s"} you love",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(
    playlist: Playlist,
    coverUri: String?,
    trackCount: Long,
    onTap: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (coverUri != null) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.primaryContainer,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (coverUri != null) {
                    coil3.compose.AsyncImage(
                        model = coverUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = buildString {
                    append(trackCount).append(if (trackCount == 1L) " track" else " tracks")
                    playlist.description?.takeIf { it.isNotBlank() }?.let {
                        append(" · ").append(it)
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { if (name.isNotBlank()) onCreate(name) },
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Sort options for the Playlists list. Default [RecentlyCreated]
 * matches the natural Flow order (createdAt desc) so the user sees
 * "what did I just make" at the top. Session-local — sort prefs
 * for screen-list views in this app are intentionally not
 * persisted unless the screen has many users-of-many-decisions
 * (Library Browser tabs persist; this one doesn't).
 */
private enum class PlaylistsSort(val label: String) {
    RecentlyCreated("Recently created"),
    NameAsc("Name A→Z"),
    NameDesc("Name Z→A"),
    TrackCountDesc("Most tracks"),
}
