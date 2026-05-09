package com.visorcraft.radioshed.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.visorcraft.radioshed.ui.util.TooltipIconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box as LayoutBox
import androidx.compose.runtime.rememberCoroutineScope
import com.visorcraft.radioshed.data.HistoryEntryWithStation
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.data.formatIsoUtc
import com.visorcraft.radioshed.data.nowMillis
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.stations.StationPlayback
import com.visorcraft.radioshed.ui.playlists.TrackContextSheet
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Chronological global play history. Each row shows what was played,
 * which station it came from, and when, all rendered with relative
 * time. Tap to play that track again as a one-off (seeds an ad-hoc
 * queue from the visible history list).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    stationRepo: StationRepository = koinInject(),
    libraryRepo: LibraryRepository = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    audioEngine: com.visorcraft.radioshed.audio.AudioEngine = koinInject(),
    historyExporter: com.visorcraft.radioshed.source.HistoryExporter = koinInject(),
) {
    var entries by remember { mutableStateOf<List<HistoryEntryWithStation>>(emptyList()) }
    var trackById by remember { mutableStateOf<Map<Long, Track>>(emptyMap()) }
    var menuOpen by remember { mutableStateOf(false) }
    var clearConfirmOpen by remember { mutableStateOf(false) }
    // Granular-trim confirmation. Captures both the cutoff (so the
    // confirm-button knows what window to delete) and a label for
    // the prompt copy. Null = no dialog shown. Mirrors the existing
    // clearConfirmOpen flow but parameterised — the user always gets
    // a chance to bail out of an irreversible delete.
    var trimConfirm by remember {
        mutableStateOf<Pair<Long, String>?>(null)
    }
    var contextTrackId by remember { mutableStateOf<Long?>(null) }
    // Station filter: null means "show all stations". Reset back to
    // null on each entries-reload because the station the user
    // filtered on may have just been deleted, and a stale id would
    // produce an empty list with no obvious cause.
    var selectedStationId by remember { mutableStateOf<Long?>(null) }
    // Free-text filter on track title or artist name. Composes with
    // the station chip — the visible list is the intersection of
    // both filters. Empty string means no text filter.
    var searchQuery by remember { mutableStateOf("") }
    // Time-window filter: All / Today / Week / Month. Composes with
    // the station chip + free-text query — visible list is the
    // intersection of all three filters. The boundary is computed
    // from `nowMillis()` at filter-evaluation time so the user
    // doesn't get stuck with a stale "today" if the screen lingers
    // across midnight.
    var timeWindow by remember { mutableStateOf(HistoryTimeWindow.All) }
    val nowPlaying by audioEngine.nowPlaying.collectAsState()
    val currentTrackId = nowPlaying?.trackId
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        entries = stationRepo.allHistory(limit = 200L)
        trackById = entries
            .map { it.trackId }
            .distinct()
            .mapNotNull { id -> libraryRepo.getTrackById(id)?.let { id to it } }
            .toMap()
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Listening history") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    LayoutBox {
                        TooltipIconButton(
                            tooltip = "More",
                            onClick = { menuOpen = true },
                        ) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            // Trim a rolling window. Useful when the user
                            // wants to delete a casual-listening session
                            // that's polluting their stats without
                            // wiping the entire history. trimHistoryOlderThan
                            // is monotonic in playedAt so we pass `now -
                            // window` as the threshold.
                            DropdownMenuItem(
                                text = { Text("Clear last hour") },
                                enabled = entries.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    val cutoff = nowMillis() - 60L * 60L * 1000L
                                    trimConfirm = cutoff to "the last hour"
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Clear last 24 hours") },
                                enabled = entries.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    val cutoff = nowMillis() - 24L * 60L * 60L * 1000L
                                    trimConfirm = cutoff to "the last 24 hours"
                                },
                            )
                            // 7d trim pairs with the Week chip so the
                            // user can preview what's about to vanish
                            // before committing. Same monotonic-cutoff
                            // semantics as the hour / 24h options —
                            // trimHistoryNewerThan deletes rows
                            // STRICTLY newer than the threshold so we
                            // never accidentally nuke the boundary row.
                            DropdownMenuItem(
                                text = { Text("Clear last 7 days") },
                                enabled = entries.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    val cutoff = nowMillis() - 7L * 24L * 60L * 60L * 1000L
                                    trimConfirm = cutoff to "the last 7 days"
                                },
                            )
                            // 30d closes the parity gap with the time
                            // filter chip row (which has 30d). Same
                            // monotonic-cutoff + confirm flow.
                            DropdownMenuItem(
                                text = { Text("Clear last 30 days") },
                                enabled = entries.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    val cutoff = nowMillis() - 30L * 24L * 60L * 60L * 1000L
                                    trimConfirm = cutoff to "the last 30 days"
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Clear all history") },
                                enabled = entries.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    clearConfirmOpen = true
                                },
                            )
                            androidx.compose.material3.HorizontalDivider()
                            // CSV export — drops the visible-history rows
                            // into a spreadsheet-friendly file via the
                            // share sheet. Disabled when there's nothing
                            // to write so the menu doesn't promise an
                            // empty share.
                            DropdownMenuItem(
                                text = { Text("Export as CSV…") },
                                enabled = entries.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    scope.launch {
                                        val rows = entries.map { e ->
                                            val track = trackById[e.trackId]
                                            val albumTitle = track?.albumId?.let {
                                                libraryRepo.getAlbumById(it)?.title
                                            }
                                            val artistName = track?.id?.let {
                                                libraryRepo.artistsForTrack(it)
                                                    .firstOrNull()?.name
                                            }
                                            com.visorcraft.radioshed.source.HistoryCsvRow(
                                                playedAtIso = formatIsoUtc(e.playedAt),
                                                title = track?.title ?: "(missing)",
                                                artist = artistName,
                                                album = albumTitle,
                                                stationName = e.stationName,
                                                listenedMs = e.listenedMs,
                                                completed = e.completed,
                                            )
                                        }
                                        historyExporter.exportCsv(rows)
                                    }
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
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No history yet — start playing some music to populate.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        } else {
            // Station filter chips: distinct (id, name) pairs in the
            // visible history, in their first-seen order. The
            // distinctBy({id}) keeps each station once even when it
            // appears in many rows, while preserving the natural
            // "most-recently-played stations first" order.
            val stationOptions = remember(entries) {
                entries
                    .distinctBy { it.stationId }
                    .map { it.stationId to it.stationName }
            }
            val visibleEntries = remember(entries, selectedStationId, searchQuery, trackById, timeWindow) {
                // Time-window cutoff: nowMillis() at filter eval, so a
                // user who left the screen open across midnight gets a
                // freshly-rolled window when the chip composes again.
                val nowMs = nowMillis()
                val cutoffMs = when (timeWindow) {
                    HistoryTimeWindow.All -> 0L
                    HistoryTimeWindow.Today -> nowMs - 24L * 60L * 60L * 1000L
                    HistoryTimeWindow.Week -> nowMs - 7L * 24L * 60L * 60L * 1000L
                    HistoryTimeWindow.Month -> nowMs - 30L * 24L * 60L * 60L * 1000L
                }
                val byTime = if (cutoffMs == 0L) entries
                             else entries.filter { it.playedAt >= cutoffMs }
                val byStation = if (selectedStationId == null) byTime
                                else byTime.filter { it.stationId == selectedStationId }
                val q = searchQuery.trim()
                if (q.isBlank()) byStation
                else byStation.filter { entry ->
                    // Match on track title OR file name (for tracks with
                    // empty title tags). Removed-from-library tracks
                    // also fall through here — they won't have a row
                    // in trackById and thus never match the filter,
                    // which is the right behavior (a search shouldn't
                    // surface ghost rows).
                    val track = trackById[entry.trackId] ?: return@filter false
                    track.title.contains(q, ignoreCase = true) ||
                        track.fileName.contains(q, ignoreCase = true) ||
                        entry.stationName.contains(q, ignoreCase = true)
                }
            }
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                // Free-text search row above the station filter chips.
                // Matches on track title / filename / station name —
                // composes with the station filter so users can
                // narrow ("show me 'Rain' on Latest Hits") rather
                // than choose between filters.
                androidx.compose.material3.OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search played tracks…") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Filled.Search,
                            contentDescription = null,
                        )
                    },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            TooltipIconButton(
                                tooltip = "Clear search",
                                onClick = { searchQuery = "" },
                            ) {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Filled.Clear,
                                    contentDescription = "Clear search",
                                )
                            }
                        }
                    } else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
                // Time-window chip row, always rendered (doesn't need a
                // size threshold like station chips do — even one
                // station's history benefits from "show me Today only").
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HistoryTimeWindow.entries.forEach { opt ->
                        // Long-press the chip to trim history matching
                        // that window — preview-then-trim in one motion.
                        // The "All" chip skips the long-press path
                        // because it's destructive on the order of "wipe
                        // everything" which already lives in the menu's
                        // Clear-all-history option (with its own confirm).
                        Box(
                            modifier = Modifier.combinedClickable(
                                onClick = { timeWindow = opt },
                                onLongClick = lp@{
                                    // `All` is filtered out here because trimming
                                    // *everything* is destructive enough to live
                                    // exclusively in the menu's Clear-all-history
                                    // option (with its own confirm) — we don't want
                                    // a long-press on the All chip to trigger a
                                    // wipe. With All ruled out, the `when` below
                                    // covers Today/Week/Month exhaustively, no
                                    // `else` arm needed.
                                    if (opt == HistoryTimeWindow.All) return@lp
                                    val nowMs = nowMillis()
                                    val cutoff = when (opt) {
                                        HistoryTimeWindow.Today -> nowMs - 24L * 60L * 60L * 1000L
                                        HistoryTimeWindow.Week -> nowMs - 7L * 24L * 60L * 60L * 1000L
                                        HistoryTimeWindow.Month -> nowMs - 30L * 24L * 60L * 60L * 1000L
                                    }
                                    trimConfirm = cutoff to opt.trimLabel
                                },
                            ),
                        ) {
                            androidx.compose.material3.FilterChip(
                                selected = timeWindow == opt,
                                onClick = { timeWindow = opt },
                                label = { Text(opt.label) },
                            )
                        }
                    }
                }
                if (stationOptions.size > 1) {
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            androidx.compose.material3.FilterChip(
                                selected = selectedStationId == null,
                                onClick = { selectedStationId = null },
                                label = { Text("All") },
                            )
                        }
                        items(stationOptions, key = { (id, _) -> "f:$id" }) { (id, name) ->
                            androidx.compose.material3.FilterChip(
                                selected = selectedStationId == id,
                                onClick = {
                                    // Tapping a station chip while a
                                    // text search is active also clears
                                    // the search — the user signaled
                                    // "narrow by station" and likely
                                    // wants the broad text filter out
                                    // of the way to see what's in that
                                    // station. Without this, the chip
                                    // tap and search box compound and
                                    // can leave a confusingly empty
                                    // list.
                                    if (searchQuery.isNotEmpty()) searchQuery = ""
                                    selectedStationId = if (selectedStationId == id) null else id
                                },
                                label = { Text(name) },
                            )
                        }
                    }
                }
                // Bucket the visible entries by relative-time band so
                // we can emit a "Today" / "Yesterday" / "Earlier this
                // week" header before each group. The bands are
                // computed once per bucket boundary; "now" is captured
                // at composition time so all rows agree on which side
                // of midnight they fall on.
                val nowSnap = remember(visibleEntries) { nowMillis() }
                val grouped = remember(visibleEntries, nowSnap) {
                    buildList {
                        var lastBucket: HistoryBucket? = null
                        for (e in visibleEntries) {
                            val b = bucketFor(e.playedAt, nowSnap)
                            if (b != lastBucket) {
                                add(HistoryListItem.Header(b))
                                lastBucket = b
                            }
                            add(HistoryListItem.Entry(e))
                        }
                    }
                }
                // Aggregate header — total plays in the visible
                // window, summed listened-time, and avg/day across
                // the calendar-day span the entries cover. Quietly
                // hides when visibleEntries is empty (the empty-state
                // copy at the screen top already handles that case).
                val summary = remember(visibleEntries, nowSnap) {
                    if (visibleEntries.isEmpty()) null
                    else {
                        val plays = visibleEntries.size
                        val listenedMs = visibleEntries.sumOf { it.listenedMs }
                        val span = visibleEntries.maxOf { it.playedAt } -
                            visibleEntries.minOf { it.playedAt }
                        val daysInSpan = (span / DAY_MS_MILLIS).toInt() + 1
                        val avgPerDay = if (daysInSpan > 0)
                            (plays.toDouble() / daysInSpan).coerceAtLeast(0.0)
                        else 0.0
                        Triple(plays, listenedMs, avgPerDay)
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    summary?.let { (plays, listenedMs, avgPerDay) ->
                        item(key = "summary") {
                            // Tap-to-copy lets users paste their
                            // listening summary into a journal / chat
                            // without having to retype the three
                            // numbers. Single-tap because the row's
                            // only purpose is informational — no
                            // collision with another gesture.
                            val copyToClipboard = com.visorcraft
                                .radioshed.ui.util.rememberCopyToClipboard()
                            val summaryText = "$plays play" +
                                (if (plays == 1) "" else "s") +
                                " · ${formatHistoryListened(listenedMs)}" +
                                " · ${"%.1f/day".format(avgPerDay)}"
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant
                                    .copy(alpha = 0.45f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .clickable {
                                        copyToClipboard(summaryText)
                                    },
                            ) {
                                Row(
                                    modifier = Modifier.padding(
                                        horizontal = 12.dp,
                                        vertical = 8.dp,
                                    ),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "$plays play${if (plays == 1) "" else "s"}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = formatHistoryListened(listenedMs),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "%.1f/day".format(avgPerDay),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                    items(
                        grouped,
                        key = { item ->
                            when (item) {
                                is HistoryListItem.Header -> "h:${item.bucket.name}"
                                is HistoryListItem.Entry -> "e:${item.entry.id}"
                            }
                        },
                    ) { item ->
                        when (item) {
                            is HistoryListItem.Header -> HistoryDateHeader(item.bucket)
                            is HistoryListItem.Entry -> {
                                val entry = item.entry
                                HistoryRow(
                                    entry = entry,
                                    track = trackById[entry.trackId],
                                    isCurrent = entry.trackId == currentTrackId,
                                    onTap = {
                                        // "Continue session from here":
                                        // play chronologically forward
                                        // from the tapped entry. We use
                                        // the FULL history list (not
                                        // visibleEntries) so an active
                                        // search filter doesn't punch
                                        // holes through the playback
                                        // queue — if the user filtered
                                        // to "Track Foo" and tapped one
                                        // result, the session they
                                        // continue should still include
                                        // every track that played
                                        // around it, not just the ones
                                        // matching the filter. The
                                        // tapped entry's id is found in
                                        // the reversed list to seed
                                        // playback at the right slot.
                                        val sessionPairs = entries
                                            .filter { it.playedAt <= entry.playedAt }
                                            .reversed()
                                            .mapNotNull { e ->
                                                trackById[e.trackId]?.let { e.id to it }
                                            }
                                        if (sessionPairs.isEmpty()) return@HistoryRow
                                        val startIdx = sessionPairs
                                            .indexOfFirst { it.first == entry.id }
                                            .coerceAtLeast(0)
                                        stationPlayback.playTracks(
                                            sessionPairs.map { it.second },
                                            startIndex = startIdx,
                                        )
                                    },
                                    onLongPress = {
                                        // Skip rows whose track has
                                        // been deleted — the context
                                        // sheet would just show an
                                        // empty title with no
                                        // actionable info.
                                        trackById[entry.trackId]?.let {
                                            contextTrackId = entry.trackId
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    contextTrackId?.let { id ->
        TrackContextSheet(
            trackId = id,
            onDismiss = { contextTrackId = null },
        )
    }

    if (clearConfirmOpen) {
        AlertDialog(
            onDismissRequest = { clearConfirmOpen = false },
            title = { Text("Clear listening history?") },
            text = {
                Text(
                    "This deletes every play record AND resets per-station play " +
                        "counts. Library, ratings, playlists, and station " +
                        "definitions are untouched. Top played tracks / artists " +
                        "/ albums and Top Stations all reset to zero.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    clearConfirmOpen = false
                    scope.launch {
                        stationRepo.clearAllHistory()
                        reload()
                    }
                }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirmOpen = false }) { Text("Cancel") }
            },
        )
    }

    trimConfirm?.let { (cutoff, windowLabel) ->
        AlertDialog(
            onDismissRequest = { trimConfirm = null },
            title = { Text("Clear $windowLabel of history?") },
            text = {
                Text(
                    "This deletes every play recorded in $windowLabel. " +
                        "Per-station play counts and listened time are NOT " +
                        "reset (those reflect total plays since the station " +
                        "was first used). Library, ratings, playlists, and " +
                        "station definitions are untouched.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val applyCutoff = cutoff
                    trimConfirm = null
                    scope.launch {
                        stationRepo.trimHistoryNewerThan(applyCutoff)
                        reload()
                    }
                }) {
                    Text(
                        "Clear",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { trimConfirm = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    entry: HistoryEntryWithStation,
    track: Track?,
    isCurrent: Boolean = false,
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
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
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
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = track?.title?.ifBlank { track.fileName } ?: "Removed track",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = when {
                            track == null -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                            isCurrent -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onBackground
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    com.visorcraft.radioshed.ui.util.NowPlayingIndicator(isCurrent)
                }
                Text(
                    text = "${entry.stationName} · ${formatHistoryTimestamp(entry.playedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Visual marker for skipped vs completed plays — completed
            // gets a small filled dot, skipped is hollow. Subtle but
            // useful for "what did I actually finish vs skip past?".
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (entry.completed) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
                    ),
            )
        }
    }
}

/**
 * Date bands used to group history rows. Each entry below the
 * "Older" threshold falls into one of the named buckets so the
 * scroll has cadence; older entries collapse into a single
 * "Older" footer rather than a dozen "X months ago" buckets.
 */
private enum class HistoryBucket(val label: String) {
    Today("Today"),
    Yesterday("Yesterday"),
    EarlierThisWeek("Earlier this week"),
    EarlierThisMonth("Earlier this month"),
    Older("Older"),
}

/**
 * Time-window filter for history rows. Independent from
 * [HistoryBucket] (which groups visible entries by relative-time
 * band for header rendering). Order matters here — `entries` is
 * iterated in declaration order to render the chip row.
 */
private enum class HistoryTimeWindow(val label: String, val trimLabel: String) {
    All("All", "all history"),
    Today("24h", "the last 24 hours"),
    Week("7d", "the last 7 days"),
    Month("30d", "the last 30 days"),
}

private sealed interface HistoryListItem {
    data class Header(val bucket: HistoryBucket) : HistoryListItem
    data class Entry(val entry: HistoryEntryWithStation) : HistoryListItem
}

private fun bucketFor(playedAtMs: Long, nowMs: Long): HistoryBucket {
    val deltaMs = (nowMs - playedAtMs).coerceAtLeast(0L)
    val day = deltaMs / 86_400_000L
    return when {
        day < 1L -> HistoryBucket.Today
        day < 2L -> HistoryBucket.Yesterday
        day < 7L -> HistoryBucket.EarlierThisWeek
        day < 30L -> HistoryBucket.EarlierThisMonth
        else -> HistoryBucket.Older
    }
}

@Composable
private fun HistoryDateHeader(bucket: HistoryBucket) {
    Text(
        text = bucket.label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Same relative-time vocabulary as backups, just shorter. */
private fun formatHistoryTimestamp(ms: Long): String {
    val deltaMs = (nowMillis() - ms).coerceAtLeast(0L)
    val sec = deltaMs / 1_000L
    val min = sec / 60L
    val hr = min / 60L
    val day = hr / 24L
    return when {
        sec < 45L -> "Just now"
        min < 60L -> "${min}m ago"
        hr < 24L -> "${hr}h ago"
        day < 7L -> "${day}d ago"
        day < 30L -> "${day / 7L}w ago"
        else -> "${day / 30L}mo ago"
    }
}

private const val DAY_MS_MILLIS: Long = 24L * 60L * 60L * 1000L

/** Total listened time formatted compact for the summary header
 *  ("3h 12m" / "47m" / "9s"). Distinct from the per-row recency
 *  string above so the two render side by side without confusion. */
private fun formatHistoryListened(ms: Long): String {
    if (ms <= 0L) return "0m"
    val totalMin = ms / 60_000L
    val hours = totalMin / 60L
    val mins = totalMin % 60L
    return when {
        hours > 0L -> "${hours}h ${mins}m"
        else -> "${mins}m"
    }
}
