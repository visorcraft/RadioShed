package com.visorcraft.radioshed.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.data.LyricsCacheRepository
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
) {
    val nowPlaying by audioEngine.nowPlaying.collectAsState()
    val position by audioEngine.position.collectAsState()
    val scope = rememberCoroutineScope()

    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    var loading by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf(false) }
    var editSearchOpen by remember { mutableStateOf(false) }

    // Re-resolve whenever the active track changes. The service handles
    // caching internally, so this is cheap on cache hits.
    LaunchedEffect(nowPlaying?.trackId) {
        val id = nowPlaying?.trackId ?: run {
            lyrics = null; notFound = false; loading = false; return@LaunchedEffect
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
                        val subtitle = listOfNotNull(artistName, sourceLabel)
                            .joinToString(" · ")
                        if (subtitle.isNotBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
                actions = {
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val l = lyrics
        when {
            loading -> CenteredLoading(modifier = Modifier.fillMaxSize().padding(padding))
            l != null && l.instrumental -> InstrumentalState(
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            l != null && !l.synced.isNullOrEmpty() -> SyncedLyrics(
                lines = l.synced,
                positionMs = position.currentMs,
                onSeek = { ms -> audioEngine.seekTo(ms) },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            l != null && !l.plain.isNullOrBlank() -> PlainLyrics(
                text = l.plain,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            notFound -> NotFoundState(
                modifier = Modifier.fillMaxSize().padding(padding),
                onEditSearch = { editSearchOpen = true },
            )
            else -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Pick a track to see its lyrics.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        }
    }

    if (editSearchOpen) {
        val track = nowPlaying ?: run {
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
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val a = artist.trim()
                    val t = title.trim()
                    if (a.isNotBlank() && t.isNotBlank()) onSubmit(a, t)
                },
                enabled = artist.isNotBlank() && title.isNotBlank(),
            ) { Text("Search") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SyncedLyrics(
    lines: List<LyricLine>,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Find the active line: the last line whose timeMs <= positionMs.
    // Linear scan is fine here — typical songs have <200 lines and this
    // runs at ~2 Hz with the position flow.
    val activeIndex = remember(lines, positionMs) {
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs) idx = i else break
        }
        idx
    }

    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 && activeIndex < lines.size) {
            // Scroll the active line near the top-third of the viewport.
            // animateScrollToItem keeps the motion smooth as lines tick by.
            runCatching {
                listState.animateScrollToItem(activeIndex.coerceAtLeast(0), -120)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(lines, key = { i, l -> "$i:${l.timeMs}" }) { i, line ->
            val isActive = i == activeIndex
            val isPast = i < activeIndex
            // Tap-to-seek: jump player position to this line's
            // timestamp. The Spotify / Apple Music gesture vocab —
            // people instinctively tap a lyric line they want to
            // start from. Lines with no text (instrumental gaps in
            // the LRC) still seek; that's the right behavior.
            Text(
                text = line.text.ifBlank { " " },  // preserve breath gaps
                style = if (isActive) MaterialTheme.typography.headlineSmall
                        else MaterialTheme.typography.titleMedium,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    isActive -> MaterialTheme.colorScheme.primary
                    isPast -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSeek(line.timeMs) }
                    .padding(vertical = 4.dp),
                textAlign = TextAlign.Start,
            )
        }
    }
}

@Composable
private fun PlainLyrics(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
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
private fun sourceLabelFor(source: String): String? = when {
    source.isBlank() -> null
    source == "local" -> "from local .lrc"
    source == "embedded" -> "from file tags"
    source.startsWith("lrclib") -> "from LRClib"
    else -> "from $source"
}

@Composable
private fun NotFoundState(modifier: Modifier = Modifier, onEditSearch: () -> Unit) {
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
