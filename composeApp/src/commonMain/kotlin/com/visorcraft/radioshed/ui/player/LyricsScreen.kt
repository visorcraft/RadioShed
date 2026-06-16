package com.visorcraft.radioshed.ui.player

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.data.LyricsCacheRepository
import com.visorcraft.radioshed.data.nowMillis
import com.visorcraft.radioshed.domain.LyricLine
import com.visorcraft.radioshed.domain.Lyrics
import com.visorcraft.radioshed.source.LyricsService
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Full-screen lyrics view. Three render modes:
 *   - Synced LRC: list of timed lines, current line highlighted, auto-
 *     scrolls to keep it centered.
 *   - Plain text: scrollable plain-text fallback when synced isn't
 *     available but plain is.
 *   - Empty / instrumental / loading / not-found: centered hint.
 *
 * Lyrics are fetched lazily on first entry per trackId and cached via
 * [LyricsService]. The "Refresh" button bypasses cache (forceRefresh)
 * so users can retry after a network failure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsScreen(
    onBack: () -> Unit,
    audioEngine: AudioEngine = koinInject(),
    lyricsService: LyricsService = koinInject(),
    lyricsCache: LyricsCacheRepository = koinInject(),
    settings: com.visorcraft.radioshed.data.SettingsStore = koinInject(),
) {
    val nowPlaying by audioEngine.nowPlaying.collectAsStateWithLifecycle()
    val position by audioEngine.position.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Lyrics font scale, persisted via SettingsStore so the
    // user's chosen size carries across sessions. A− and A+
    // buttons in the topbar step it in 0.1 increments inside
    // the SettingsStore-clamped range.
    var fontScale by remember { mutableStateOf(settings.getLyricsScale()) }
    // Sync offset compensates for lyrics that consistently arrive
    // early/late vs the audio. Persisted globally — the most common
    // case is system-wide latency from the audio output (Bluetooth
    // headphones, speakers), not per-track lyric drift, so a single
    // global value matches user mental model. ±5s clamped at the
    // store level. Persistence is an LaunchedEffect on the live
    // value below.
    var syncOffsetMs by remember { mutableStateOf(settings.getLyricsSyncOffsetMs()) }
    var syncSheetOpen by remember { mutableStateOf(false) }
    LaunchedEffect(syncOffsetMs) { settings.setLyricsSyncOffsetMs(syncOffsetMs) }

    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    var loading by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf(false) }
    var editSearchOpen by remember { mutableStateOf(false) }

    // Re-resolve whenever the active track changes. The service handles
    // caching internally, so this is cheap on cache hits.
    val copyToClipboard = com.visorcraft.radioshed.ui.util.rememberCopyToClipboard()
    // In-page lyric search — typing into this filter scrolls the
    // synced list to the first matching line and tints matches so
    // the user can spot the rest by eye. Empty query = inactive.
    // Declared above the LaunchedEffect that resets it on track
    // change so the effect can reference both states.
    var findQuery by remember { mutableStateOf("") }
    var findBarOpen by remember { mutableStateOf(false) }

    LaunchedEffect(nowPlaying?.trackId) {
        // Reset the in-page find state on every track change.
        // Without this, the find bar stays visible with the
        // previous track's query, and matchingIndices runs on the
        // new lyrics — auto-scrolling to (and highlighting) any
        // accidental matches in the new song.
        findQuery = ""
        findBarOpen = false
        val id =
            nowPlaying?.trackId ?: run {
                lyrics = null
                notFound = false
                loading = false
                return@LaunchedEffect
            }
        loading = true
        notFound = false
        val resolved = lyricsService.resolve(id)
        // Guard against late writes from a previous track's resolve
        // landing on the new track's screen. If the track id changed
        // while we were suspended, drop the result on the floor —
        // the new LaunchedEffect for the new id has already started
        // its own resolve.
        if (nowPlaying?.trackId == id) {
            lyrics = resolved
            notFound = resolved == null
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            nowPlaying?.title ?: "Lyrics",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        // Show source attribution when we have lyrics —
                        // helps the user understand WHY they're seeing
                        // the result (or none): "embedded" means the
                        // file's tags carry it, "local" means a .lrc
                        // sidecar, "lrclib" means the network fetch.
                        val artistName = nowPlaying?.artist
                        val sourceLabel = lyrics?.let { sourceLabelFor(it.source) }
                        val subtitle =
                            listOfNotNull(artistName, sourceLabel)
                                .joinToString(" · ")
                        if (subtitle.isNotBlank()) {
                            // Tap-to-copy the subtitle so users can
                            // share "{artist} · from LRClib" or
                            // similar attribution into a chat or
                            // bug report. Single-tap is fine here —
                            // attribution is purely informational.
                            val attributionClipboard =
                                com.visorcraft
                                    .radioshed.ui.util.rememberCopyToClipboard()
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                maxLines = 1,
                                modifier =
                                    Modifier.clickable {
                                        attributionClipboard(subtitle)
                                    },
                            )
                        }
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
                actions = {
                    // Font-size +/- buttons. Hidden when there's no
                    // text to size — instrumental / not-found states
                    // don't need them and they'd look like dead UI.
                    val l0 = lyrics
                    val hasReadableLyrics = l0 != null && l0.hasContent && !l0.instrumental
                    if (hasReadableLyrics) {
                        TextButton(onClick = {
                            val next = (fontScale - 0.1f).coerceAtLeast(0.7f)
                            fontScale = next
                            settings.setLyricsScale(next)
                        }) { Text("A−") }
                        TextButton(onClick = {
                            val next = (fontScale + 0.1f).coerceAtMost(1.6f)
                            fontScale = next
                            settings.setLyricsScale(next)
                        }) { Text("A+") }
                        // Sync offset is only meaningful for synced
                        // lyrics — plain text has no per-line timing
                        // to compensate. Hidden when there's only
                        // plain text or no lyrics at all.
                        if (lyrics?.synced?.isNotEmpty() == true) {
                            TextButton(onClick = { syncSheetOpen = true }) {
                                Text("Sync")
                            }
                        }
                    }
                    val l = lyrics
                    if (l != null && l.hasContent && !l.instrumental) {
                        TextButton(onClick = {
                            findBarOpen = !findBarOpen
                            if (!findBarOpen) findQuery = ""
                        }) { Text(if (findBarOpen) "Hide find" else "Find") }
                        TextButton(onClick = {
                            // Synced lines reduce to plain text — most
                            // users want the words, not the timestamps.
                            // If both formats are missing it's an
                            // instrumental and we already returned above.
                            val text =
                                l.plain
                                    ?: l.synced
                                        ?.joinToString("\n") { it.text }
                                        ?.takeIf { it.isNotBlank() }
                                    ?: ""
                            if (text.isNotBlank()) {
                                copyToClipboard(text)
                            }
                        }) { Text("Copy") }
                    }
                    nowPlaying?.trackId?.let { id ->
                        if (!loading) {
                            TextButton(onClick = {
                                scope.launch {
                                    loading = true
                                    notFound = false
                                    lyricsService.forget(id)
                                    val resolved = lyricsService.resolve(id, forceRefresh = true)
                                    // Same stale-write guard as the
                                    // LaunchedEffect above — if the user
                                    // navigated to a different track
                                    // mid-refresh, don't clobber the new
                                    // screen's state.
                                    if (nowPlaying?.trackId == id) {
                                        lyrics = resolved
                                        notFound = resolved == null
                                        loading = false
                                    }
                                }
                            }) { Text("Refresh") }
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val l = lyrics
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (findBarOpen) {
                androidx.compose.material3.OutlinedTextField(
                    value = findQuery,
                    onValueChange = { findQuery = it },
                    placeholder = { Text("Find in lyrics…") },
                    singleLine = true,
                    leadingIcon = {
                        androidx.compose.material3.Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                        )
                    },
                    trailingIcon =
                        if (findQuery.isNotEmpty()) {
                            {
                                com.visorcraft.radioshed.ui.util.TooltipIconButton(
                                    tooltip = "Clear",
                                    onClick = { findQuery = "" },
                                ) {
                                    androidx.compose.material3.Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Clear",
                                    )
                                }
                            }
                        } else {
                            null
                        },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            when {
                loading -> CenteredLoading(modifier = Modifier.fillMaxSize())
                l != null && l.instrumental ->
                    InstrumentalState(
                        modifier = Modifier.fillMaxSize(),
                    )
                l != null && !l.synced.isNullOrEmpty() ->
                    SyncedLyrics(
                        lines = l.synced,
                        positionMs = position.currentMs,
                        onSeek = { ms -> audioEngine.seekTo(ms) },
                        fontScale = fontScale,
                        syncOffsetMs = syncOffsetMs,
                        onCopyLine = { copyToClipboard(it) },
                        findQuery = findQuery,
                        modifier = Modifier.fillMaxSize(),
                    )
                l != null && !l.plain.isNullOrBlank() ->
                    PlainLyrics(
                        text = l.plain,
                        fontScale = fontScale,
                        onCopyLine = { copyToClipboard(it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                notFound ->
                    NotFoundState(
                        modifier = Modifier.fillMaxSize(),
                        onEditSearch = { editSearchOpen = true },
                    )
                else ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Pick a track to see its lyrics.",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        )
                    }
            }
        }
    }

    if (syncSheetOpen) {
        AlertDialog(
            onDismissRequest = { syncSheetOpen = false },
            title = { Text("Lyrics sync offset") },
            text = {
                Column {
                    Text(
                        "Adjust if lyrics are showing early or late. " +
                            "Positive = delay lyrics; negative = show " +
                            "lyrics earlier. Typical Bluetooth latency is " +
                            "150–250ms.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "${(syncOffsetMs / 1000.0).let {
                            if (it == 0.0) "0.0s" else "%+.1fs".format(it)
                        }}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    androidx.compose.material3.Slider(
                        value = syncOffsetMs.toFloat(),
                        onValueChange = { syncOffsetMs = it.toInt() },
                        valueRange = -5000f..5000f,
                        steps = 99, // 100ms increments across ±5s
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = { syncOffsetMs = -250 }) { Text("BT") }
                        TextButton(onClick = { syncOffsetMs = 0 }) { Text("Reset") }
                        TextButton(onClick = { syncOffsetMs = 250 }) { Text("+250ms") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { syncSheetOpen = false }) { Text("Done") }
            },
        )
    }

    if (editSearchOpen) {
        val track =
            nowPlaying ?: run {
                editSearchOpen = false
                return
            }
        EditLyricsSearchDialog(
            initialArtist = track.artist.orEmpty(),
            initialTitle = track.title,
            onDismiss = { editSearchOpen = false },
            onSubmit = { newArtist, newTitle ->
                editSearchOpen = false
                val id = track.trackId
                scope.launch {
                    loading = true
                    notFound = false
                    lyricsCache.setOverride(id, newArtist, newTitle)
                    // Force a refresh so the override actually fires —
                    // the existing cache row is a "miss" sentinel and
                    // would short-circuit otherwise.
                    lyricsService.forget(id)
                    val resolved = lyricsService.resolve(id, forceRefresh = true)
                    if (nowPlaying?.trackId == id) {
                        lyrics = resolved
                        notFound = resolved == null
                        loading = false
                    }
                }
            },
        )
    }
}

@Composable
private fun EditLyricsSearchDialog(
    initialArtist: String,
    initialTitle: String,
    onDismiss: () -> Unit,
    onSubmit: (artist: String, title: String) -> Unit,
) {
    var artist by remember { mutableStateOf(initialArtist) }
    var title by remember { mutableStateOf(initialTitle) }
    val confirmSearch: () -> Unit = {
        val a = artist.trim()
        val t = title.trim()
        if (a.isNotBlank() && t.isNotBlank()) onSubmit(a, t)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit search") },
        text = {
            Column {
                Text(
                    "Adjust the artist and title we send to the lyrics " +
                        "service. We'll save your correction for this " +
                        "track so future refreshes use it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                        ),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                        ),
                    keyboardActions =
                        androidx.compose.foundation.text.KeyboardActions(
                            onSearch = { confirmSearch() },
                        ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = confirmSearch,
                enabled = artist.isNotBlank() && title.isNotBlank(),
            ) { Text("Search") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SyncedLyrics(
    lines: List<LyricLine>,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    fontScale: Float,
    syncOffsetMs: Int,
    onCopyLine: (String) -> Unit,
    findQuery: String = "",
    modifier: Modifier = Modifier,
) {
    // Find the active line: the last line whose timeMs <= adjustedPos.
    // Sync offset compensates for lyrics that arrive consistently
    // early/late vs the audio. Positive offset = lyrics show later
    // (we subtract from positionMs when matching), negative = lyrics
    // show earlier. Linear scan is fine — typical songs have <200
    // lines and this runs at ~2 Hz with the position flow.
    val activeIndex =
        remember(lines, positionMs, syncOffsetMs) {
            val adjusted = (positionMs - syncOffsetMs).coerceAtLeast(0L)
            var idx = -1
            for (i in lines.indices) {
                if (lines[i].timeMs <= adjusted) idx = i else break
            }
            idx
        }

    val listState = rememberLazyListState()
    // Distinguishes user-driven scrolls from our own
    // animateScrollToItem. We can't rely on
    // listState.interactionSource.collectIsDraggedAsState alone —
    // its DragInteraction emission isn't guaranteed on every Compose
    // KMP target — so we additionally gate on a flag we set around
    // the programmatic animate call. If the list is scrolling and
    // we didn't start it, it's the user.
    var animatingProgrammatically by remember { mutableStateOf(false) }
    val isUserDragging by remember {
        derivedStateOf { listState.isScrollInProgress && !animatingProgrammatically }
    }
    var lastUserScrollAt by remember { mutableStateOf(0L) }
    LaunchedEffect(isUserDragging) {
        if (isUserDragging) lastUserScrollAt = nowMillis()
    }
    // Active line off-screen? Used to surface the "Jump to current"
    // FAB when the user has scrolled away. Re-derives every frame
    // off layoutInfo, which is cheap.
    val activeOffscreen by remember(activeIndex) {
        derivedStateOf {
            if (activeIndex < 0) {
                false
            } else {
                val info = listState.layoutInfo.visibleItemsInfo
                info.none { it.index == activeIndex }
            }
        }
    }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(activeIndex, isUserDragging, lastUserScrollAt) {
        if (activeIndex < 0 || activeIndex >= lines.size) return@LaunchedEffect
        // Hold off while the user is touching, plus a ~5s grace
        // window so they can read a verse without being yanked
        // back. The next position tick re-evaluates this and will
        // resume auto-scroll once the window has elapsed.
        if (isUserDragging) return@LaunchedEffect
        val sinceUser = nowMillis() - lastUserScrollAt
        if (lastUserScrollAt != 0L && sinceUser < USER_OVERRIDE_MS) return@LaunchedEffect
        // Scroll the active line near the top-third of the viewport.
        // animateScrollToItem keeps the motion smooth as lines tick by.
        // The programmatic flag is set so the user-scroll detector
        // doesn't misread our own animate call as a manual interaction.
        animatingProgrammatically = true
        try {
            runCatching {
                listState.animateScrollToItem(activeIndex.coerceAtLeast(0), -120)
            }
        } finally {
            animatingProgrammatically = false
        }
    }

    // Compute matching line indices once per (lines, query) — used
    // both to drive the scroll-to-first-match LaunchedEffect and to
    // tint matching rows so the user can see the rest at a glance.
    val matchingIndices =
        remember(lines, findQuery) {
            if (findQuery.isBlank()) {
                emptyList()
            } else {
                lines.mapIndexedNotNull { idx, l ->
                    if (l.text.contains(findQuery, ignoreCase = true)) idx else null
                }
            }
        }
    LaunchedEffect(matchingIndices.firstOrNull()) {
        val first = matchingIndices.firstOrNull() ?: return@LaunchedEffect
        animatingProgrammatically = true
        try {
            runCatching { listState.animateScrollToItem(first, -120) }
        } finally {
            animatingProgrammatically = false
        }
    }

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(lines, key = { i, l -> "$i:${l.timeMs}" }) { i, line ->
                val isActive = i == activeIndex
                val isPast = i < activeIndex
                val isFindMatch = findQuery.isNotBlank() && i in matchingIndices
                // Tap-to-seek: jump player position to this line's
                // timestamp. The Spotify / Apple Music gesture vocab —
                // people instinctively tap a lyric line they want to
                // start from. Lines with no text (instrumental gaps in
                // the LRC) still seek; that's the right behavior.
                val baseStyle =
                    if (isActive) {
                        MaterialTheme.typography.headlineSmall
                    } else {
                        MaterialTheme.typography.titleMedium
                    }
                val rowMod =
                    Modifier
                        .fillMaxWidth()
                        .let { mod ->
                            if (isFindMatch) {
                                mod.background(
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                )
                            } else {
                                mod
                            }
                        }
                        .combinedClickable(
                            onClick = { onSeek(line.timeMs) },
                            onLongClick = {
                                if (line.text.isNotBlank()) onCopyLine(line.text)
                            },
                        )
                        .padding(vertical = 4.dp, horizontal = if (isFindMatch) 6.dp else 0.dp)
                Text(
                    text = line.text.ifBlank { " " }, // preserve breath gaps
                    style = baseStyle.copy(fontSize = baseStyle.fontSize * fontScale),
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    color =
                        when {
                            isActive -> MaterialTheme.colorScheme.primary
                            isPast -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                            else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
                        },
                    modifier = rowMod,
                    textAlign = TextAlign.Start,
                )
            }
        }
        // "Jump back to current line" — only rendered when the user
        // has scrolled the active line off-screen. Tap: scroll to
        // the active line and clear the user-override window so
        // subsequent auto-scrolls resume. Long-press: scroll all the
        // way to the first line (handy when the user has dragged the
        // list way past the song's start to read ahead).
        if (activeOffscreen && activeIndex >= 0) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .size(56.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .combinedClickable(
                            onClick = {
                                lastUserScrollAt = 0L
                                coroutineScope.launch {
                                    runCatching {
                                        listState.animateScrollToItem(activeIndex, -120)
                                    }
                                }
                            },
                            onLongClick = {
                                lastUserScrollAt = nowMillis()
                                coroutineScope.launch {
                                    runCatching { listState.animateScrollToItem(0) }
                                }
                            },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.ArrowDownward,
                    contentDescription = "Jump to current line · long-press for top",
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

private const val USER_OVERRIDE_MS = 5_000L

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlainLyrics(
    text: String,
    fontScale: Float,
    onCopyLine: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Split into per-line rows so long-press can copy a single line
    // (mirrors the synced-lyrics gesture vocab). Blank lines stay as
    // visible breath gaps but don't get a copy handler.
    val lines = remember(text) { text.split('\n') }
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        val base = MaterialTheme.typography.bodyLarge
        lines.forEach { line ->
            val rendered = line.ifBlank { " " }
            val rowMod =
                if (line.isBlank()) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.fillMaxWidth().combinedClickable(
                        onClick = {},
                        onLongClick = { onCopyLine(line) },
                    )
                }
            Text(
                text = rendered,
                style = base.copy(fontSize = base.fontSize * fontScale),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = rowMod.padding(vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun InstrumentalState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "♪",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "Instrumental",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                "No lyrics — just the music.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * Map the LyricsService internal source string to a user-readable
 * label. Falls back to the raw value for anything we don't know
 * about so future fetcher additions surface visibly.
 */
private fun sourceLabelFor(source: String): String? =
    when {
        source.isBlank() -> null
        source == "local" -> "from local .lrc"
        source == "embedded" -> "from file tags"
        source.startsWith("lrclib") -> "from LRClib"
        else -> "from $source"
    }

@Composable
private fun NotFoundState(
    modifier: Modifier = Modifier,
    onEditSearch: () -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Lyrics not found",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                "Tap Refresh to retry, or Edit search to fix the artist " +
                    "and title we look up.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Spacer(Modifier.size(12.dp))
            OutlinedButton(onClick = onEditSearch) { Text("Edit search") }
        }
    }
}

@Composable
private fun CenteredLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
