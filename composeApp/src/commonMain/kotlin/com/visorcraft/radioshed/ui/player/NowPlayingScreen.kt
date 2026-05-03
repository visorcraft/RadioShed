package com.visorcraft.radioshed.ui.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ShuffleOn
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.PlayerState
import com.visorcraft.radioshed.audio.SleepTimer
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.RatingRepository
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.stations.StationPlayback
import com.visorcraft.radioshed.ui.playlists.AddToPlaylistSheet
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Full-screen Now Playing. BoxWithConstraints drives a single-column
 * stacked layout on compact widths and a two-pane (art-left,
 * controls-right) layout from medium up.
 *
 * Album art is a tinted gradient until Phase 10 wires Cover Art Archive.
 * Lyrics and queue panes are deferred to Phase 6 part 2 (need lyrics
 * fetching + a queue projection out of StationPlayback respectively).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NowPlayingScreen(
    onClose: () -> Unit,
    onOpenLyrics: () -> Unit = {},
    onOpenAlbum: (Long) -> Unit = {},
    onOpenArtist: (Long) -> Unit = {},
    audioEngine: AudioEngine = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    ratingRepo: RatingRepository = koinInject(),
    sleepTimer: SleepTimer = koinInject(),
    libraryRepo: LibraryRepository = koinInject(),
    settings: SettingsStore = koinInject(),
) {
    // Drive Mode is observed live so a toggle from Settings takes
    // effect immediately, even if Now Playing is the active screen
    // (e.g. settings opened in a different tab on a foldable). The
    // mirror StateFlow in SettingsStore propagates writes from any
    // setDriveMode() call.
    val driveMode by settings.driveModeFlow.collectAsState()
    val reduceMotion = remember(settings) { settings.getReduceMotion() }
    val nowPlaying by audioEngine.nowPlaying.collectAsState()
    val state by audioEngine.state.collectAsState()
    val position by audioEngine.position.collectAsState()
    val currentStation by stationPlayback.currentStation.collectAsState()
    val repeatMode by stationPlayback.repeatMode.collectAsState()
    val shuffleSession by stationPlayback.shuffleSession.collectAsState()
    val userVolume by stationPlayback.userVolume.collectAsState()
    val sleepState by sleepTimer.state.collectAsState()
    val scope = rememberCoroutineScope()
    var sleepMenuOpen by remember { mutableStateOf(false) }
    var queueSheetOpen by remember { mutableStateOf(false) }
    var overflowMenuOpen by remember { mutableStateOf(false) }
    var addToPlaylistTrackId by remember { mutableStateOf<Long?>(null) }
    var trackInfoOpen by remember { mutableStateOf(false) }
    // Snapshot taken when the sheet opens; refresh each time the sheet is
    // re-opened so the user sees the current upcoming/recent at that
    // moment without having to subscribe to a live flow we don't have.
    var upcoming by remember { mutableStateOf<List<Track>>(emptyList()) }
    var recent by remember { mutableStateOf<List<Track>>(emptyList()) }
    LaunchedEffect(queueSheetOpen, nowPlaying?.trackId) {
        if (queueSheetOpen) {
            upcoming = stationPlayback.upcoming(count = 20)
            recent = stationPlayback.recentSnapshot().reversed()  // newest first
        }
    }

    var rating by remember { mutableStateOf(0.0) }
    var liked by remember { mutableStateOf(false) }
    LaunchedEffect(nowPlaying?.trackId) {
        rating = nowPlaying?.let { ratingRepo.getRating(it.trackId) } ?: 0.0
        liked = nowPlaying?.let { ratingRepo.isLiked(it.trackId) } ?: false
    }

    // Read window width once at the screen root so both the topBar and
    // the body share a single source of truth for breakpoints. Below
    // 360dp the overflow DropdownMenu submenu items used to clip ("Go
    // to album"/"Go to artist" wrapped or got cut off); we route to a
    // ModalBottomSheet on narrow widths instead.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isNarrow = maxWidth < 360.dp
        val isCompact = maxWidth < 600.dp

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Now Playing", style = MaterialTheme.typography.titleSmall)
                        SleepTimerLabel(sleepState)
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Close")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenLyrics) {
                        Icon(
                            imageVector = Icons.Filled.Lyrics,
                            contentDescription = "Lyrics",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    // Shuffle session toggle. Doesn't persist to the
                    // station — flips between Sequential and SmartShuffle
                    // for the in-flight queue only, resets on station
                    // change. The on-state glyph (ShuffleOn) carries a
                    // dot indicator so colour-blind users can read it
                    // without relying on the primary tint alone.
                    IconButton(onClick = { stationPlayback.toggleShuffle() }) {
                        Icon(
                            imageVector = if (shuffleSession) Icons.Filled.ShuffleOn
                                          else Icons.Filled.Shuffle,
                            contentDescription = if (shuffleSession) "Shuffle on" else "Shuffle off",
                            tint = if (shuffleSession) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    // Repeat-mode cycler. Off → All → One → Off. Visual
                    // state matches the sleep icon's pattern: dim when
                    // off, primary tint when on, with the "One" badge
                    // riding on the icon glyph itself.
                    IconButton(onClick = { stationPlayback.cycleRepeatMode() }) {
                        Icon(
                            imageVector = if (repeatMode == com.visorcraft.radioshed.stations.StationPlayback.RepeatMode.One)
                                Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            contentDescription = when (repeatMode) {
                                com.visorcraft.radioshed.stations.StationPlayback.RepeatMode.Off -> "Repeat off"
                                com.visorcraft.radioshed.stations.StationPlayback.RepeatMode.All -> "Repeat all"
                                com.visorcraft.radioshed.stations.StationPlayback.RepeatMode.One -> "Repeat one"
                            },
                            tint = if (repeatMode == com.visorcraft.radioshed.stations.StationPlayback.RepeatMode.Off)
                                MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = { queueSheetOpen = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "Queue",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Box {
                        IconButton(onClick = { sleepMenuOpen = true }) {
                            Icon(
                                imageVector = if (sleepState is SleepTimer.State.Idle)
                                    Icons.Outlined.Bedtime else Icons.Filled.Bedtime,
                                contentDescription = "Sleep timer",
                                tint = if (sleepState is SleepTimer.State.Idle)
                                    MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.primary,
                            )
                        }
                        SleepTimerMenu(
                            expanded = sleepMenuOpen,
                            currentState = sleepState,
                            onDismiss = { sleepMenuOpen = false },
                            onPickMinutes = { mins ->
                                sleepMenuOpen = false
                                sleepTimer.start(mins * 60_000L)
                            },
                            onEndOfTrack = {
                                sleepMenuOpen = false
                                sleepTimer.startEndOfTrack()
                            },
                            onExtend = { mins ->
                                sleepMenuOpen = false
                                sleepTimer.extend(mins * 60_000L)
                            },
                            onCancel = {
                                sleepMenuOpen = false
                                sleepTimer.cancel()
                            },
                        )
                    }
                    Box {
                        IconButton(onClick = { overflowMenuOpen = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        // Wide screens: classic anchored DropdownMenu.
                        // Narrow screens get a ModalBottomSheet rendered
                        // outside the topBar slot (below) — DropdownMenus
                        // anchored to a tight icon position clip their
                        // submenu items at <360dp.
                        if (!isNarrow) {
                            DropdownMenu(
                                expanded = overflowMenuOpen,
                                onDismissRequest = { overflowMenuOpen = false },
                            ) {
                                val track = nowPlaying
                                DropdownMenuItem(
                                    text = { Text("Add to playlist") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                                    enabled = track != null,
                                    onClick = {
                                        overflowMenuOpen = false
                                        track?.trackId?.let { addToPlaylistTrackId = it }
                                    },
                                )
                                // For Go-to-album / Go-to-artist we use the
                                // metadata stamped on the Playable rather
                                // than DB lookups for the enabled state, so
                                // tracks with no album/artist tags don't
                                // appear actionable when they aren't.
                                DropdownMenuItem(
                                    text = { Text("Go to album") },
                                    leadingIcon = { Icon(Icons.Filled.Album, null) },
                                    enabled = track != null && !track.album.isNullOrBlank(),
                                    onClick = {
                                        overflowMenuOpen = false
                                        val tid = track?.trackId ?: return@DropdownMenuItem
                                        scope.launch {
                                            libraryRepo.getTrackById(tid)?.albumId?.let { onOpenAlbum(it) }
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Go to artist") },
                                    leadingIcon = { Icon(Icons.Filled.Person, null) },
                                    enabled = track != null && !track.artist.isNullOrBlank(),
                                    onClick = {
                                        overflowMenuOpen = false
                                        val tid = track?.trackId ?: return@DropdownMenuItem
                                        scope.launch {
                                            libraryRepo.artistsForTrack(tid)
                                                .firstOrNull()?.id?.let { onOpenArtist(it) }
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Track info") },
                                    leadingIcon = { Icon(Icons.Filled.Info, null) },
                                    enabled = track != null,
                                    onClick = {
                                        overflowMenuOpen = false
                                        trackInfoOpen = true
                                    },
                                )
                            }
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
        val playable = nowPlaying
        if (playable == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Nothing playing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                )
            }
            return@Scaffold
        }

        if (queueSheetOpen) {
            QueueBottomSheet(
                upcoming = upcoming,
                recent = recent,
                isShuffleMode = currentStation?.playback?.order != com.visorcraft.radioshed.domain.PlayOrder.Sequential
                    && currentStation != null,
                onDismiss = { queueSheetOpen = false },
                onJumpUpcoming = { idx ->
                    if (upcoming.isNotEmpty()) {
                        stationPlayback.playTracks(upcoming, startIndex = idx)
                        queueSheetOpen = false
                    }
                },
            )
        }
        addToPlaylistTrackId?.let { id ->
            AddToPlaylistSheet(
                trackId = id,
                onDismiss = { addToPlaylistTrackId = null },
            )
        }
        if (trackInfoOpen) {
            val track = nowPlaying
            if (track != null) {
                TrackInfoDialog(
                    playable = track,
                    libraryRepo = libraryRepo,
                    onDismiss = { trackInfoOpen = false },
                )
            } else {
                trackInfoOpen = false
            }
        }
        if (isNarrow && overflowMenuOpen) {
            OverflowSheet(
                track = nowPlaying,
                onDismiss = { overflowMenuOpen = false },
                onAddToPlaylist = { tid ->
                    overflowMenuOpen = false
                    addToPlaylistTrackId = tid
                },
                onGoToAlbum = { tid ->
                    overflowMenuOpen = false
                    scope.launch {
                        libraryRepo.getTrackById(tid)?.albumId?.let { onOpenAlbum(it) }
                    }
                },
                onGoToArtist = { tid ->
                    overflowMenuOpen = false
                    scope.launch {
                        libraryRepo.artistsForTrack(tid)
                            .firstOrNull()?.id?.let { onOpenArtist(it) }
                    }
                },
                onTrackInfo = {
                    overflowMenuOpen = false
                    trackInfoOpen = true
                },
            )
        }
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val accent = stationAccent(currentStation?.color)

            BlurredBackdrop(
                artworkUri = playable.artworkUri,
                reduceMotion = reduceMotion,
            )

            // Heart toggle handler — flips local state immediately for
            // snappy UI, then writes through to the repo. The optimistic
            // flip is safe because the repo is idempotent on like/unlike.
            val onToggleLike: () -> Unit = {
                val target = !liked
                liked = target
                scope.launch {
                    if (target) ratingRepo.like(playable.trackId)
                    else ratingRepo.unlike(playable.trackId)
                }
            }

            if (isCompact) {
                CompactLayout(
                    playable = playable,
                    state = state,
                    positionMs = position.currentMs,
                    durationMs = position.durationMs,
                    rating = rating,
                    liked = liked,
                    volume = userVolume,
                    accent = accent,
                    stationName = currentStation?.name,
                    onSeek = { audioEngine.seekTo(it) },
                    onTogglePlayPause = { audioEngine.togglePlayPause() },
                    onPrev = { stationPlayback.skipPrevious() },
                    onNext = { stationPlayback.skipNext() },
                    onRate = { stars ->
                        scope.launch {
                            ratingRepo.setRating(playable.trackId, stars)
                            rating = stars
                        }
                    },
                    onToggleLike = onToggleLike,
                    onVolume = { stationPlayback.setUserVolume(it) },
                    onDislikeOnStation = {
                        val stationId = currentStation?.id ?: return@CompactLayout
                        scope.launch {
                            ratingRepo.dislikeForStation(playable.trackId, stationId)
                            stationPlayback.skipNext()
                        }
                    },
                    driveMode = driveMode,
                )
            } else {
                TwoPaneLayout(
                    playable = playable,
                    state = state,
                    positionMs = position.currentMs,
                    durationMs = position.durationMs,
                    rating = rating,
                    liked = liked,
                    volume = userVolume,
                    accent = accent,
                    stationName = currentStation?.name,
                    onSeek = { audioEngine.seekTo(it) },
                    onTogglePlayPause = { audioEngine.togglePlayPause() },
                    onPrev = { stationPlayback.skipPrevious() },
                    onNext = { stationPlayback.skipNext() },
                    onRate = { stars ->
                        scope.launch {
                            ratingRepo.setRating(playable.trackId, stars)
                            rating = stars
                        }
                    },
                    onToggleLike = onToggleLike,
                    onVolume = { stationPlayback.setUserVolume(it) },
                    onDislikeOnStation = {
                        val stationId = currentStation?.id ?: return@TwoPaneLayout
                        scope.launch {
                            ratingRepo.dislikeForStation(playable.trackId, stationId)
                            stationPlayback.skipNext()
                        }
                    },
                    driveMode = driveMode,
                )
            }
        }
    }
    }  // BoxWithConstraints
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverflowSheet(
    track: com.visorcraft.radioshed.audio.Playable?,
    onDismiss: () -> Unit,
    onAddToPlaylist: (Long) -> Unit,
    onGoToAlbum: (Long) -> Unit,
    onGoToArtist: (Long) -> Unit,
    onTrackInfo: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            OverflowSheetItem(
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                label = "Add to playlist",
                enabled = track != null,
                onClick = { track?.trackId?.let(onAddToPlaylist) },
            )
            OverflowSheetItem(
                icon = Icons.Filled.Album,
                label = "Go to album",
                enabled = track != null && !track.album.isNullOrBlank(),
                onClick = { track?.trackId?.let(onGoToAlbum) },
            )
            OverflowSheetItem(
                icon = Icons.Filled.Person,
                label = "Go to artist",
                enabled = track != null && !track.artist.isNullOrBlank(),
                onClick = { track?.trackId?.let(onGoToArtist) },
            )
            OverflowSheetItem(
                icon = Icons.Filled.Info,
                label = "Track info",
                enabled = track != null,
                onClick = onTrackInfo,
            )
        }
    }
}

@Composable
private fun OverflowSheetItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        )
    }
}

@Composable
private fun CompactLayout(
    playable: com.visorcraft.radioshed.audio.Playable,
    state: PlayerState,
    positionMs: Long,
    durationMs: Long,
    rating: Double,
    liked: Boolean,
    volume: Float,
    accent: Color,
    stationName: String?,
    onSeek: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRate: (Double) -> Unit,
    onToggleLike: () -> Unit,
    onVolume: (Float) -> Unit,
    onDislikeOnStation: () -> Unit,
    driveMode: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AlbumArtPlaceholder(
            accent = accent,
            artworkUri = playable.artworkUri,
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Spacer(Modifier.height(24.dp))
        TrackInfo(playable, stationName, alignment = Alignment.CenterHorizontally)
        Spacer(Modifier.height(16.dp))
        Scrubber(positionMs, durationMs, onSeek)
        Spacer(Modifier.height(16.dp))
        TransportRow(
            state = state,
            onTogglePlayPause = onTogglePlayPause,
            onPrev = onPrev,
            onNext = onNext,
            onDislikeOnStation = onDislikeOnStation,
            driveMode = driveMode,
        )
        Spacer(Modifier.height(20.dp))
        RatingRow(
            current = rating,
            liked = liked,
            onRate = onRate,
            onToggleLike = onToggleLike,
        )
        Spacer(Modifier.height(8.dp))
        VolumeRow(volume = volume, onVolume = onVolume)
    }
}

@Composable
private fun TwoPaneLayout(
    playable: com.visorcraft.radioshed.audio.Playable,
    state: PlayerState,
    positionMs: Long,
    durationMs: Long,
    rating: Double,
    liked: Boolean,
    volume: Float,
    accent: Color,
    stationName: String?,
    onSeek: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRate: (Double) -> Unit,
    onToggleLike: () -> Unit,
    onVolume: (Float) -> Unit,
    onDislikeOnStation: () -> Unit,
    driveMode: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Art is sized by share-of-width, not by share-of-height. Without
        // this, on tall-wide screens like the Galaxy Fold 7's unfolded
        // inner display the square `fillMaxHeight + aspectRatio(1f)` art
        // would consume the full width and crush the right column to a
        // few dp wide, where the track title and scrubber would render
        // character-by-character vertically. `weight(1f)` shares width
        // 50/50 with the right column; `widthIn(max=)` caps art on
        // ultra-wide displays so it doesn't dominate.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            AlbumArtPlaceholder(
                accent = accent,
                artworkUri = playable.artworkUri,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .aspectRatio(1f),
            )
        }
        Spacer(Modifier.width(32.dp))
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
        ) {
            TrackInfo(playable, stationName, alignment = Alignment.Start)
            Spacer(Modifier.height(24.dp))
            Scrubber(positionMs, durationMs, onSeek)
            Spacer(Modifier.height(24.dp))
            TransportRow(
                state = state,
                onTogglePlayPause = onTogglePlayPause,
                onPrev = onPrev,
                onNext = onNext,
                onDislikeOnStation = onDislikeOnStation,
                driveMode = driveMode,
            )
            Spacer(Modifier.height(28.dp))
            RatingRow(
                current = rating,
                liked = liked,
                onRate = onRate,
                onToggleLike = onToggleLike,
            )
            Spacer(Modifier.height(8.dp))
            VolumeRow(volume = volume, onVolume = onVolume)
        }
    }
}

/**
 * Ambient cover-art backdrop. Pulled out into its own composable so the
 * (expensive) blurred AsyncImage layer only recomposes when the
 * `artworkUri` actually changes — not on every position-tick / slider-
 * drag / rating-update happening upstream. Coil's memory cache holds
 * the decoded bitmap, so the same URI reuses the same bytes; this
 * composable just stops Compose from re-walking the modifier chain
 * 10× per second.
 *
 * Modifier.blur is GPU-accelerated on Android 12+; older versions
 * silently no-op the blur, leaving the dimmed image + scrim still
 * reading as a tinted backdrop.
 *
 * When [reduceMotion] is on we skip the blurred image layer entirely
 * — leaving just a flat scrim. The blur isn't a moving animation,
 * but it can still feel busy on motion-sensitive displays, and it's
 * the single most expensive paint on Now Playing.
 */
@Composable
private fun BlurredBackdrop(artworkUri: String?, reduceMotion: Boolean) {
    if (artworkUri == null) return
    val scrimColor = MaterialTheme.colorScheme.background.copy(alpha = 0.55f)
    Box(modifier = Modifier.fillMaxSize()) {
        if (!reduceMotion) {
            coil3.compose.AsyncImage(
                model = artworkUri,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(radius = 80.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                alpha = 0.6f,
            )
        }
        Box(modifier = Modifier.fillMaxSize().background(scrimColor))
    }
}

@Composable
private fun AlbumArtPlaceholder(
    accent: Color,
    artworkUri: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(accent, accent.copy(alpha = 0.55f)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (artworkUri != null) {
            // Real album art (file:// URI from AndroidArtworkPersister).
            // Coil renders it on top of the gradient; gradient stays visible
            // briefly during load and as fallback if decode fails.
            coil3.compose.AsyncImage(
                model = artworkUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            Text(
                text = "♫",
                style = MaterialTheme.typography.displayLarge,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun TrackInfo(
    playable: com.visorcraft.radioshed.audio.Playable,
    stationName: String?,
    alignment: Alignment.Horizontal,
) {
    Column(horizontalAlignment = alignment, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = playable.title.ifBlank { "Unknown title" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (alignment == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start,
        )
        val subtitle = listOfNotNull(playable.artist, playable.album).joinToString(" · ")
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (alignment == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start,
            )
        }
        if (stationName != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "from $stationName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                textAlign = if (alignment == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start,
            )
        }
    }
}

@Composable
private fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    var isUserScrubbing by remember { mutableStateOf(false) }
    var pendingFraction by remember { mutableFloatStateOf(0f) }

    val durationSafe = durationMs.coerceAtLeast(0L)
    val currentFraction = if (durationSafe == 0L) 0f
                          else (positionMs.toFloat() / durationSafe).coerceIn(0f, 1f)
    val displayFraction = if (isUserScrubbing) pendingFraction else currentFraction

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = displayFraction,
            onValueChange = {
                isUserScrubbing = true
                pendingFraction = it
            },
            onValueChangeFinished = {
                if (durationSafe > 0L) {
                    onSeek((pendingFraction * durationSafe).toLong())
                }
                isUserScrubbing = false
            },
            colors = SliderDefaults.colors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatMs(if (isUserScrubbing) (pendingFraction * durationSafe).toLong() else positionMs),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
            Text(
                text = formatMs(durationSafe),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransportRow(
    state: PlayerState,
    onTogglePlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onDislikeOnStation: () -> Unit,
    driveMode: Boolean,
) {
    var dislikeMenuOpen by remember { mutableStateOf(false) }
    val playing = state == PlayerState.Playing

    // Drive Mode bumps every transport hit-target by 25% so they're
    // unambiguous to hit while glancing at the screen at speed. Non-
    // drive sizes match the original Material recommendation.
    val baseSize = if (driveMode) 80.dp else 64.dp
    val emphasizedSize = if (driveMode) 100.dp else 80.dp
    val iconSize = if (driveMode) 44.dp else 36.dp
    val emphasizedIconSize = if (driveMode) 60.dp else 48.dp

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BigIconButton(
            icon = Icons.Filled.SkipPrevious,
            description = "Previous",
            buttonSize = baseSize,
            iconSize = iconSize,
            onClick = onPrev,
        )

        BigIconButton(
            icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            description = if (playing) "Pause" else "Play",
            buttonSize = emphasizedSize,
            iconSize = emphasizedIconSize,
            onClick = onTogglePlayPause,
        )

        // Skip-next: tap = skip; long-press opens "don't play on this station" menu.
        Box {
            Surface(
                color = Color.Transparent,
                modifier = Modifier
                    .size(baseSize)
                    .clip(RoundedCornerShape(baseSize / 2))
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onNext,
                        onLongClick = { dislikeMenuOpen = true },
                    )
                    .semantics { contentDescription = "Next (long-press for options)" },
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = null,
                        modifier = Modifier.size(iconSize),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
            DropdownMenu(
                expanded = dislikeMenuOpen,
                onDismissRequest = { dislikeMenuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Skip this track") },
                    onClick = {
                        onNext()
                        dislikeMenuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Don't play on this station again") },
                    onClick = {
                        onDislikeOnStation()
                        dislikeMenuOpen = false
                    },
                )
            }
        }
    }
}

@Composable
private fun BigIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    buttonSize: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    // Vector icons everywhere — see MiniPlayer.TransportIconButton for why
    // we don't use Unicode media glyphs (Samsung Noto vs Roboto vs SF Pro
    // render U+23F8 with different metrics; vector icons are font-free).
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(buttonSize)
            .semantics { contentDescription = description },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,  // outer modifier already labels it
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun RatingRow(
    current: Double,
    liked: Boolean,
    onRate: (Double) -> Unit,
    onToggleLike: () -> Unit,
) {
    // Half-star precision: each star is split into two 24dp-wide tap zones.
    // Tapping the LEFT half sets rating to (n - 0.5), RIGHT half sets to n.
    // Tapping the same value you've already given clears the rating.
    // Display rules per star n:
    //   current >= n          → Filled
    //   current >= n - 0.5    → Half
    //   else                   → Outlined
    //
    // The heart sits leading, separate from the star rating — like and
    // rating are independent (a 3★ can still be liked, an unliked
    // track can still be 5★).
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleLike, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = if (liked) Icons.Filled.Favorite
                              else Icons.Outlined.FavoriteBorder,
                contentDescription = if (liked) "Unlike" else "Like",
                modifier = Modifier.size(28.dp),
                tint = if (liked) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            )
        }
        Spacer(Modifier.width(4.dp))
        (1..5).forEach { n ->
            val nDouble = n.toDouble()
            val halfValue = nDouble - 0.5
            val starState = when {
                current >= nDouble -> StarState.Full
                current >= halfValue -> StarState.Half
                else -> StarState.Empty
            }
            // Each star occupies one 48dp box. Inside, we render the icon
            // centered, then overlay TWO transparent tap zones (left half
            // → half-star value, right half → full-star value). Compose
            // dispatches taps to the topmost composable, so the overlay
            // wins.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Rating star $n" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when (starState) {
                        StarState.Full -> Icons.Filled.Star
                        StarState.Half -> Icons.AutoMirrored.Filled.StarHalf
                        StarState.Empty -> Icons.Outlined.StarOutline
                    },
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = when (starState) {
                        StarState.Empty -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable {
                                val target = if (current == halfValue) 0.0 else halfValue
                                onRate(target)
                            },
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable {
                                val target = if (current == nDouble) 0.0 else nDouble
                                onRate(target)
                            },
                    )
                }
            }
        }
    }
}

/**
 * Slim in-app volume slider. Distinct from the hardware volume rocker
 * (which controls the system music stream); this controls the engine's
 * playback gain so users can fine-tune output without changing the
 * system level. Multiplies with ReplayGain attenuation in
 * StationPlayback.applyEffectiveVolume — a 50% slider on a track
 * with -6 dB ReplayGain ends up at ~25% engine volume.
 */
@Composable
private fun VolumeRow(volume: Float, onVolume: (Float) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when {
                volume < 0.02f -> Icons.AutoMirrored.Filled.VolumeOff
                volume < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
                else -> Icons.AutoMirrored.Filled.VolumeUp
            },
            contentDescription = "Volume",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
        Spacer(Modifier.width(8.dp))
        Slider(
            value = volume,
            onValueChange = onVolume,
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(),
        )
    }
}

private enum class StarState { Empty, Half, Full }

private fun stationAccent(packedColor: Int?): Color {
    if (packedColor == null || packedColor == 0) return Color(0xFFFF7F1A) // brand amber default
    return Color(packedColor.toLong() and 0xFFFFFFFFL)
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000L
    val mins = totalSeconds / 60L
    val secs = totalSeconds % 60L
    return "$mins:${secs.toString().padStart(2, '0')}"
}

@Composable
private fun TrackInfoDialog(
    playable: com.visorcraft.radioshed.audio.Playable,
    libraryRepo: LibraryRepository,
    onDismiss: () -> Unit,
) {
    val stationRepo: com.visorcraft.radioshed.data.StationRepository = koinInject()
    val folderRevealer: com.visorcraft.radioshed.source.FolderRevealer = koinInject()
    val ringtoneSetter: com.visorcraft.radioshed.source.RingtoneSetter = koinInject()
    val trackSharer: com.visorcraft.radioshed.source.TrackSharer = koinInject()
    val scope = rememberCoroutineScope()
    var track by remember { mutableStateOf<Track?>(null) }
    var ringtoneStatus by remember { mutableStateOf<String?>(null) }
    var stats by remember {
        mutableStateOf<com.visorcraft.radioshed.data.TrackHistoryStats?>(null)
    }
    var genres by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(playable.trackId) {
        track = libraryRepo.getTrackById(playable.trackId)
        stats = stationRepo.historyStatsForTrack(playable.trackId)
        genres = libraryRepo.tagsForTrack(playable.trackId)
            .filter { it.key.equals("GENRE", ignoreCase = true) }
            .map { it.value }
            .filter { it.isNotBlank() }
            .distinct()
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        // Open folder lives on the dismissButton slot so it sits to
        // the LEFT of Close per Material's button-bar conventions.
        // Disabled until the track row resolves so we don't fire
        // with a null path.
        dismissButton = {
            val t = track
            TextButton(
                onClick = {
                    if (t != null) {
                        scope.launch { folderRevealer.revealParent(t.path) }
                        onDismiss()
                    }
                },
                enabled = t != null,
            ) {
                Text("Open folder")
            }
        },
        title = { Text("Track info") },
        text = {
            val t = track
            if (t == null) {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                    ringtoneStatus?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    Row(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    ) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                scope.launch {
                                    ringtoneStatus = "Setting ringtone…"
                                    val result = ringtoneSetter.setAsRingtone(
                                        absolutePath = t.path,
                                        displayName = t.title.ifBlank { t.fileName },
                                    )
                                    ringtoneStatus = when (result) {
                                        is com.visorcraft.radioshed.source.RingtoneSetter.Result.Ok ->
                                            "Set as ringtone."
                                        is com.visorcraft.radioshed.source.RingtoneSetter.Result.NeedsPermission ->
                                            "Grant Modify system settings, then tap again."
                                        is com.visorcraft.radioshed.source.RingtoneSetter.Result.NotSupported ->
                                            "Not supported on this platform."
                                        is com.visorcraft.radioshed.source.RingtoneSetter.Result.Failed ->
                                            "Couldn't set: ${result.reason}"
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Ringtone")
                        }
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val ok = trackSharer.share(
                                        absolutePath = t.path,
                                        displayName = t.title.ifBlank { t.fileName },
                                    )
                                    if (!ok) {
                                        ringtoneStatus = "Couldn't open share sheet."
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Share")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    InfoLine("Title", t.title)
                    playable.artist?.let { InfoLine("Artist", it) }
                    playable.album?.let { InfoLine("Album", it) }
                    if (genres.isNotEmpty()) {
                        InfoLine("Genre", genres.joinToString(", "))
                    }
                    InfoLine("Format", t.format.uppercase())
                    InfoLine("Duration", formatMs(t.durationMs))
                    t.bitrateKbps?.let { InfoLine("Bitrate", "$it kbps") }
                    t.sampleRateHz?.let { InfoLine("Sample rate", "${it / 1000.0} kHz") }
                    t.channels?.let { InfoLine("Channels", "$it") }
                    t.bpm?.let { InfoLine("BPM", "%.1f".format(it)) }
                    InfoLine("File size", formatFileSize(t.fileSize))
                    InfoLine("Added", formatRelativeDate(t.addedAt))
                    t.replayGainTrack?.let { InfoLine("ReplayGain (track)", "%.2f dB".format(it)) }
                    t.replayGainAlbum?.let { InfoLine("ReplayGain (album)", "%.2f dB".format(it)) }
                    // Listening stats from station_history. Hidden
                    // until the track has at least one play recorded
                    // — a 0-stat block is just noise on first listen.
                    stats?.takeIf { it.plays > 0L }?.let { s ->
                        Spacer(Modifier.height(4.dp))
                        InfoLine(
                            "Plays",
                            "${s.completes} completed · ${s.skips} skipped (${s.plays} total)",
                        )
                        InfoLine("Listened", formatStatDuration(s.listenedMs))
                    }
                    InfoLine("Path", t.path, monospace = true)
                }
            }
        },
    )
}

/** Bytes → human-readable size: "12.3 MB" / "942 KB" / "8 B". */
private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val mb = bytes / 1_048_576.0
    if (mb >= 1.0) return "%.1f MB".format(mb)
    val kb = bytes / 1024.0
    if (kb >= 1.0) return "%.0f KB".format(kb)
    return "$bytes B"
}

/** "2 days ago" / "3 weeks ago" / "Jan 14, 2024" — same vocabulary
 *  as backups but without the "Just now" cutoff since added-at is
 *  always in the past from when this dialog opens. */
private fun formatRelativeDate(ms: Long): String {
    val deltaMs = (com.visorcraft.radioshed.data.nowMillis() - ms).coerceAtLeast(0L)
    val day = deltaMs / 86_400_000L
    return when {
        day < 1L -> "Today"
        day < 2L -> "Yesterday"
        day < 7L -> "${day} days ago"
        day < 30L -> "${day / 7L} weeks ago"
        day < 365L -> "${day / 30L} months ago"
        else -> "${day / 365L} years ago"
    }
}

/** "Xh Ym" / "Ym Zs" / "Zs" — same vocabulary as Stats. */
private fun formatStatDuration(ms: Long): String {
    if (ms <= 0L) return "0s"
    val totalSeconds = ms / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

@Composable
private fun InfoLine(label: String, value: String, monospace: Boolean = false) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueBottomSheet(
    upcoming: List<Track>,
    recent: List<Track>,
    isShuffleMode: Boolean,
    onDismiss: () -> Unit,
    onJumpUpcoming: (Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp, vertical = 8.dp,
            ),
        ) {
            if (recent.isNotEmpty()) {
                item { QueueSectionHeader("Just played") }
                items(recent, key = { "r:${it.id}" }) { track ->
                    QueueTrackRow(track = track, dim = true, onTap = null)
                }
                item { Spacer(Modifier.height(8.dp)) }
                item { HorizontalDivider() }
                item { Spacer(Modifier.height(8.dp)) }
            }
            item {
                QueueSectionHeader(
                    if (isShuffleMode) "Up next (shuffle pool)" else "Up next",
                )
            }
            if (upcoming.isEmpty()) {
                item {
                    Text(
                        text = "Nothing queued.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                itemsIndexed(
                    upcoming, key = { _, t -> "u:${t.id}" },
                ) { idx, track ->
                    QueueTrackRow(
                        track = track,
                        dim = false,
                        onTap = { onJumpUpcoming(idx) },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun QueueSectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun QueueTrackRow(
    track: Track,
    dim: Boolean,
    onTap: (() -> Unit)?,
) {
    val baseColor = if (dim) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.onSurface
    val rowMod = if (onTap != null) Modifier.fillMaxWidth().clickable(onClick = onTap)
                 else Modifier.fillMaxWidth()
    Row(
        modifier = rowMod.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = baseColor.copy(alpha = 0.6f),
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = track.title.ifBlank { track.fileName },
            style = MaterialTheme.typography.bodyMedium,
            color = baseColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Compact subtitle in the TopAppBar that shows what the sleep timer
 * will do next. Hidden when idle so the title doesn't reflow on toggle.
 */
@Composable
private fun SleepTimerLabel(state: SleepTimer.State) {
    val text = when (state) {
        SleepTimer.State.Idle -> null
        is SleepTimer.State.Running -> "Sleep in ${formatMs(state.remainingMs)}"
        SleepTimer.State.EndOfTrack -> "Sleep at end of track"
    } ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SleepTimerMenu(
    expanded: Boolean,
    currentState: SleepTimer.State,
    onDismiss: () -> Unit,
    onPickMinutes: (Long) -> Unit,
    onEndOfTrack: () -> Unit,
    onExtend: (Long) -> Unit,
    onCancel: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // When a timer is already running, surface the +15 min extend
        // FIRST. The user just opened the menu — odds are they wanted
        // to push the deadline back, not start a fresh one.
        if (currentState is SleepTimer.State.Running) {
            DropdownMenuItem(
                text = { Text("+15 minutes") },
                onClick = { onExtend(15L) },
            )
            HorizontalDivider()
        }
        // Discrete durations covering common use cases (commute,
        // before-bed). Skipping anything > 60 min — for longer "playlists"
        // the queue itself naturally bounds runtime.
        listOf(5L, 15L, 30L, 45L, 60L).forEach { mins ->
            DropdownMenuItem(
                text = { Text("$mins minutes") },
                onClick = { onPickMinutes(mins) },
            )
        }
        DropdownMenuItem(
            text = { Text("End of track") },
            onClick = onEndOfTrack,
        )
        if (currentState !is SleepTimer.State.Idle) {
            DropdownMenuItem(
                text = {
                    Text(
                        "Cancel timer",
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = onCancel,
            )
        }
    }
}
