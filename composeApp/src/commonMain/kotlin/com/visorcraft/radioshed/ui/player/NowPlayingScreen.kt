package com.visorcraft.radioshed.ui.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ShuffleOn
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.VerticalSlider
import androidx.compose.material3.rememberSliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.PlayerState
import com.visorcraft.radioshed.audio.SleepTimer
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.RatingRepository
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.domain.withoutBracketGroups
import com.visorcraft.radioshed.stations.StationPlayback
import com.visorcraft.radioshed.ui.playlists.AddToPlaylistSheet
import com.visorcraft.radioshed.ui.util.dragReorderItem
import com.visorcraft.radioshed.ui.util.formatFileSize
import com.visorcraft.radioshed.ui.util.formatFixed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Lightweight projection for the TriplePane station list. Decoding the
 *  full Station JSON on every [StationRepository.observeAll] emission
 *  (which happens on every track start) is unnecessary for a list that
 *  only shows id / name / subtitle.
 */
private data class StationTriplePaneEntry(val id: Long, val name: String, val customSubtitle: String?)

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
    onOpenStation: (Long) -> Unit = {},
    onOpenFolder: (String) -> Unit = {},
    audioEngine: AudioEngine = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    ratingRepo: RatingRepository = koinInject(),
    sleepTimer: SleepTimer = koinInject(),
    libraryRepo: LibraryRepository = koinInject(),
    stationRepo: com.visorcraft.radioshed.data.StationRepository = koinInject(),
    settings: SettingsStore = koinInject(),
) {
    // Drive Mode is observed live so a toggle from Settings takes
    // effect immediately, even if Now Playing is the active screen
    // (e.g. settings opened in a different tab on a foldable). The
    // mirror StateFlow in SettingsStore propagates writes from any
    // setDriveMode() call.
    val driveMode by settings.driveModeFlow.collectAsStateWithLifecycle()
    val finiteQueueProgress by stationPlayback.finiteQueueProgress.collectAsStateWithLifecycle()
    val reduceMotion = remember(settings) { settings.getReduceMotion() }
    val nowPlaying by audioEngine.nowPlaying.collectAsStateWithLifecycle()
    val state by audioEngine.state.collectAsStateWithLifecycle()
    val position by audioEngine.position.collectAsStateWithLifecycle()
    val currentStation by stationPlayback.currentStation.collectAsStateWithLifecycle()
    val repeatMode by stationPlayback.repeatMode.collectAsStateWithLifecycle()
    val shuffleSession by stationPlayback.shuffleSession.collectAsStateWithLifecycle()
    val userVolume by stationPlayback.userVolume.collectAsStateWithLifecycle()
    // Station list for the TriplePane layout's left column.
    // Collected unconditionally — even when the user is on Compact
    // or TwoPane the cost is one observed flow whose collector is
    // tied to NowPlaying's lifecycle. Collecting only inside the
    // `if (resolvedLayout == TriplePane)` branch is illegal in
    // Compose: composables must be invoked unconditionally so the
    // composition slot table stays stable across layout-pref toggles.
    // We map to a lightweight projection so each emission doesn't
    // force a full Station JSON decode for the whole list.
    val stationsForTriplePane by remember(stationRepo) {
        stationRepo.observeAll()
            .map { list -> list.map { StationTriplePaneEntry(it.id, it.name, it.customSubtitle) } }
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    // Pre-mute level for the drive-mode transport mute button. Kept at
    // screen scope so a layout switch does not reset the unmute level.
    var preMuteLevel by remember { mutableFloatStateOf(0.6f) }
    val sleepState by sleepTimer.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var sleepMenuOpen by remember { mutableStateOf(false) }
    var queueSheetOpen by remember { mutableStateOf(false) }
    var overflowMenuOpen by remember { mutableStateOf(false) }
    var addToPlaylistTrackId by remember { mutableStateOf<Long?>(null) }
    var trackInfoOpen by remember { mutableStateOf(false) }
    // Playback-speed selector. The chosen rate is persisted in
    // SettingsStore so it survives restarts and gets re-applied on
    // every track load. The bottom sheet renders chips for 0.75 / 1 /
    // 1.25 / 1.5 / 2× — the common spread for podcasts / audiobooks.
    var playbackSpeed by remember { mutableStateOf(settings.getPlaybackSpeed()) }
    var speedSheetOpen by remember { mutableStateOf(false) }
    // Re-apply the saved speed whenever a new track loads (Media3
    // resets `setPlaybackSpeed` per item by default), and once on
    // first composition so the very first track honours the pref.
    LaunchedEffect(nowPlaying?.trackId) {
        if (playbackSpeed != 1f) audioEngine.setPlaybackSpeed(playbackSpeed)
    }
    // Keep-screen-on: only while actively playing AND the user
    // opted in. Pause/Idle release the flag so the screen times out
    // normally between tracks, mirroring how Spotify / Apple Music
    // behave when the user has tapped "keep screen on".
    // Live-observed so toggling the Settings switch takes effect on
    // the active session. A plain `remember { settings.getKeepScreenOn() }`
    // reads once at first composition and ignores subsequent writes
    // — a stale-toggle bug we hit before (see #292's fix-pattern).
    val keepScreenOnPref by settings.keepScreenOnFlow.collectAsStateWithLifecycle()
    com.visorcraft.radioshed.ui.util.KeepScreenOn(
        active = keepScreenOnPref && state == com.visorcraft.radioshed.audio.PlayerState.Playing,
    )
    val hapticTick = com.visorcraft.radioshed.ui.util.rememberHapticTick()
    // Snapshot dialog: saves the current Up Next list as a new
    // playlist. We keep the candidate tracks alongside the open
    // flag so closing/reopening the queue sheet doesn't lose the
    // selection mid-dialog.
    var saveQueueDialog by remember {
        mutableStateOf<List<Track>?>(null)
    }
    val playlistRepo: com.visorcraft.radioshed.data.PlaylistRepository = koinInject()
    // Tap-to-expand lightbox for the album artwork. Renders a
    // fullscreen overlay above the rest of NowPlaying so the user
    // can stare at the cover at full resolution. Tap or back closes.
    var artworkLightboxOpen by remember { mutableStateOf(false) }
    // Snapshot taken when the sheet opens; refresh each time the sheet is
    // re-opened so the user sees the current upcoming/recent at that
    // moment without having to subscribe to a live flow we don't have.
    var upcoming by remember { mutableStateOf<List<Track>>(emptyList()) }
    var recent by remember { mutableStateOf<List<Track>>(emptyList()) }
    // How many of `upcoming` are user-queued (Play next / Add to
    // queue) vs. coming from the rule pipeline. Used by the Up Next
    // sheet to decide where to render the X (only on user-queued
    // entries since removing a rule-driven row would just regenerate
    // the same track on next peek).
    var priorityCount by remember { mutableStateOf(0) }
    // Album-id → artworkUri map for the queue sheet's track rows.
    // Built lazily from the union of upcoming + recent each time the
    // sheet opens — small enough (<=40 distinct albums in practice)
    // that the round-trip per album is cheap and beats subscribing to
    // a live observable for a transient bottom sheet.
    var queueArtwork by remember { mutableStateOf<Map<Long, String?>>(emptyMap()) }
    // Re-fetch the canonical state whenever the sheet opens or the
    // current track changes (which is also when the priority head
    // gets consumed). Optimistic local mutations from the X / Clear
    // affordances handle the in-sheet remove paths without needing
    // a separate refresh tick.
    LaunchedEffect(queueSheetOpen, nowPlaying?.trackId) {
        if (queueSheetOpen) {
            upcoming = stationPlayback.upcoming(count = 20)
            priorityCount = stationPlayback.priorityCount()
            recent = stationPlayback.recentSnapshot().reversed() // newest first
            val albumIds = (upcoming + recent).mapNotNull { it.albumId }.distinct()
            // Batched album lookup — was one getAlbumById per distinct album
            // (up to ~40) each time the queue sheet opens.
            queueArtwork = libraryRepo.albumsByIds(albumIds).mapValues { it.value.artworkUri }
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
    // Now Playing layout preference. "Auto" uses width breakpoints
    // (Compact <600dp, TriplePane >=1100dp, TwoPane otherwise);
    // explicit choices force the picked layout regardless of
    // available width — useful when the user mounts a phone in
    // landscape on a dashboard and wants the SXM-style three-column
    // view even though the breakpoint hasn't been crossed.
    //
    // Read via the StateFlow mirror so a layout change in
    // AppSettings propagates immediately. The previous
    // `remember { settings.getNowPlayingLayout() }` cached at first
    // composition — fine for the AppSettings-back-then-NowPlaying
    // path that destroys composition, fragile for any future router
    // change that keeps NowPlaying alive across nav.
    val layoutPref by settings.nowPlayingLayoutFlow.collectAsStateWithLifecycle()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isNarrow = maxWidth < 360.dp
        val resolvedLayout =
            when (layoutPref) {
                "Compact" -> NowPlayingLayoutChoice.Compact
                "TwoPane" -> NowPlayingLayoutChoice.TwoPane
                "TriplePane" -> NowPlayingLayoutChoice.TriplePane
                else ->
                    when {
                        maxWidth < 600.dp -> NowPlayingLayoutChoice.Compact
                        maxWidth >= 1100.dp -> NowPlayingLayoutChoice.TriplePane
                        else -> NowPlayingLayoutChoice.TwoPane
                    }
            }
        val isCompact = resolvedLayout == NowPlayingLayoutChoice.Compact

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            // The TopAppBar title slot gets squeezed by
                            // the many action icons (Lyrics / Shuffle /
                            // Repeat / Queue / Sleep / MoreVert) on a
                            // narrow phone. Without softWrap=false +
                            // maxLines=1, the default Text wraps per-
                            // character into "No w Pla yin g" rather
                            // than ellipsizing. Hiding the title outright
                            // on isNarrow would also be defensible since
                            // the user knows they're on Now Playing — but
                            // ellipsis keeps the affordance consistent
                            // across breakpoints.
                            Text(
                                text = "Now Playing",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
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
                    actions = { /* moved to Scaffold bottomBar — see below */ },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                )
            },
            bottomBar = {
                // Action bar — moved from the topBar so users coming from
                // the main page (where the MiniPlayer lives at the bottom)
                // don't have their navigation jump to the top mid-flight.
                // Volume sits immediately left of the other actions, which
                // stay grouped at the bottom right.
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BottomVolumeButton(
                            volume = userVolume,
                            onVolumeLive = { stationPlayback.previewUserVolume(it) },
                            onVolumeCommit = { stationPlayback.commitUserVolume(it) },
                        )
                        com.visorcraft.radioshed.ui.util.TooltipIconButton(
                            tooltip = "Lyrics",
                            onClick = onOpenLyrics,
                        ) {
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
                        com.visorcraft.radioshed.ui.util.TooltipIconButton(
                            tooltip = if (shuffleSession) "Shuffle on" else "Shuffle off",
                            onClick = { stationPlayback.toggleShuffle() },
                        ) {
                            Icon(
                                imageVector =
                                    if (shuffleSession) {
                                        Icons.Filled.ShuffleOn
                                    } else {
                                        Icons.Filled.Shuffle
                                    },
                                contentDescription = if (shuffleSession) "Shuffle on" else "Shuffle off",
                                tint =
                                    if (shuffleSession) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                            )
                        }
                        // Repeat-mode cycler. Tap: Off → All → One → Off.
                        // Long-press: reverse direction (One → All → Off →
                        // One). Saves the over-shoot tap when the user
                        // wants to back out of "Repeat one" without going
                        // through Off and All again. Surface + combinedClickable
                        // is the codebase pattern for tap-vs-long-press on
                        // a single icon (matches the BigIconButton play /
                        // pause + Stations Home Surprise tile).
                        val repeatTooltip =
                            when (repeatMode) {
                                com.visorcraft.radioshed.stations.StationPlayback.RepeatMode.Off -> "Repeat off"
                                com.visorcraft.radioshed.stations.StationPlayback.RepeatMode.All -> "Repeat all"
                                com.visorcraft.radioshed.stations.StationPlayback.RepeatMode.One -> "Repeat one"
                            }
                        com.visorcraft.radioshed.ui.util.Tooltipped(tooltip = repeatTooltip) {
                            androidx.compose.material3.Surface(
                                color = androidx.compose.ui.graphics.Color.Transparent,
                                modifier =
                                    Modifier
                                        .size(48.dp)
                                        .combinedClickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = { stationPlayback.cycleRepeatMode() },
                                            onLongClick = {
                                                val next =
                                                    when (repeatMode) {
                                                        com.visorcraft.radioshed.stations.StationPlayback.RepeatMode.Off ->
                                                            com.visorcraft.radioshed.stations.StationPlayback.RepeatMode.One
                                                        com.visorcraft.radioshed.stations.StationPlayback.RepeatMode.All ->
                                                            com.visorcraft.radioshed.stations.StationPlayback.RepeatMode.Off
                                                        com.visorcraft.radioshed.stations.StationPlayback.RepeatMode.One ->
                                                            com.visorcraft.radioshed.stations.StationPlayback.RepeatMode.All
                                                    }
                                                stationPlayback.setRepeatMode(next)
                                                hapticTick()
                                            },
                                        ),
                            ) {
                                androidx.compose.foundation.layout.Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    Icon(
                                        imageVector =
                                            if (repeatMode == com.visorcraft.radioshed.stations.StationPlayback.RepeatMode.One) {
                                                Icons.Filled.RepeatOne
                                            } else {
                                                Icons.Filled.Repeat
                                            },
                                        contentDescription = repeatTooltip,
                                        tint =
                                            if (repeatMode == com.visorcraft.radioshed.stations.StationPlayback.RepeatMode.Off) {
                                                MaterialTheme.colorScheme.onSurface
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            },
                                    )
                                }
                            }
                        } // close Tooltipped(repeatTooltip)
                        val priorityBadgeCount by stationPlayback.priorityCountFlow.collectAsStateWithLifecycle()
                        androidx.compose.material3.BadgedBox(
                            badge = {
                                if (priorityBadgeCount > 0) {
                                    androidx.compose.material3.Badge {
                                        Text(priorityBadgeCount.toString())
                                    }
                                }
                            },
                            modifier =
                                Modifier
                                    .combinedClickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { queueSheetOpen = true },
                                        onLongClick = {
                                            // Long-press on the queue icon
                                            // jumps straight to the "Save
                                            // queue as playlist" dialog —
                                            // skips the bottom-sheet step
                                            // when the user already knows
                                            // they want to save what's
                                            // queued. Snapshot the upcoming
                                            // list so it doesn't drift if
                                            // the queue mutates while the
                                            // dialog is open.
                                            val snap = stationPlayback.upcoming(64)
                                            if (snap.isNotEmpty()) {
                                                saveQueueDialog = snap
                                                hapticTick()
                                            }
                                        },
                                    )
                                    .semantics { contentDescription = "Queue" },
                        ) {
                            com.visorcraft.radioshed.ui.util.TooltipIconButton(
                                tooltip = "Queue",
                                onClick = { queueSheetOpen = true },
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        Box {
                            // Long-press shortcut: when idle, arm 30min
                            // (the median sleep-timer pick); when armed,
                            // cancel without going through the menu. Tap
                            // still opens the full menu either way.
                            Box(
                                modifier =
                                    Modifier.combinedClickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { sleepMenuOpen = true },
                                        onLongClick = {
                                            if (sleepState is SleepTimer.State.Idle) {
                                                sleepTimer.start(30L * 60_000L)
                                            } else {
                                                sleepTimer.cancel()
                                            }
                                            hapticTick()
                                        },
                                    ),
                            ) {
                                com.visorcraft.radioshed.ui.util.TooltipIconButton(
                                    tooltip = "Sleep timer",
                                    onClick = { sleepMenuOpen = true },
                                ) {
                                    Icon(
                                        imageVector =
                                            if (sleepState is SleepTimer.State.Idle) {
                                                Icons.Outlined.Bedtime
                                            } else {
                                                Icons.Filled.Bedtime
                                            },
                                        contentDescription = "Sleep timer",
                                        tint =
                                            if (sleepState is SleepTimer.State.Idle) {
                                                MaterialTheme.colorScheme.onSurface
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            },
                                    )
                                }
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
                            com.visorcraft.radioshed.ui.util.TooltipIconButton(
                                tooltip = "More",
                                onClick = { overflowMenuOpen = true },
                            ) {
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
                                        text = { Text("Go to station") },
                                        leadingIcon = {
                                            Icon(Icons.Filled.Whatshot, null)
                                        },
                                        enabled = currentStation != null,
                                        onClick = {
                                            overflowMenuOpen = false
                                            currentStation?.id?.let { onOpenStation(it) }
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
                                    DropdownMenuItem(
                                        text = {
                                            val s = playbackSpeed
                                            val label =
                                                if (s == 1f) {
                                                    "Speed (1×)"
                                                } else {
                                                    "Speed (${formatSpeed(s)}×)"
                                                }
                                            Text(label)
                                        },
                                        leadingIcon = { Icon(Icons.Filled.Speed, null) },
                                        onClick = {
                                            overflowMenuOpen = false
                                            speedSheetOpen = true
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            val playable = nowPlaying
            // One-line tech summary below the scrubber: format · bitrate ·
            // sample rate. Lazy-fetched via getTrackById on each track
            // change. The full breakdown still lives on the Track Info
            // dialog; this just answers "what am I listening to right
            // now" at a glance, the way Foobar/Winamp status bars used to.
            var formatLine by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(playable?.trackId) {
                // Clear before the async getTrackById so rapid track
                // changes (skip-five) don't keep showing the previous
                // track's format/bitrate while the next read resolves.
                formatLine = null
                val tid = playable?.trackId
                formatLine =
                    if (tid == null) {
                        null
                    } else {
                        val t = libraryRepo.getTrackById(tid)
                        if (t == null) {
                            null
                        } else {
                            val parts =
                                buildList {
                                    if (t.format.isNotBlank()) add(t.format.uppercase())
                                    t.bitrateKbps?.let { add("$it kbps") }
                                    t.sampleRateHz?.let { add("${(it / 1000.0).formatFixed(1)} kHz") }
                                }
                            if (parts.isEmpty()) null else parts.joinToString(" · ")
                        }
                    }
            }
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
                    artworkByAlbumId = queueArtwork,
                    priorityCount = priorityCount,
                    isShuffleMode =
                        currentStation?.playback?.order != com.visorcraft.radioshed.domain.PlayOrder.Sequential &&
                            currentStation != null,
                    onDismiss = { queueSheetOpen = false },
                    onJumpUpcoming = { idx ->
                        if (upcoming.isNotEmpty()) {
                            stationPlayback.playTracks(upcoming, startIndex = idx)
                            queueSheetOpen = false
                        }
                    },
                    onRemoveQueueItem = { idx ->
                        // Optimistic local update — the UI removes the
                        // row immediately so rapid taps don't address the
                        // pre-removal indices. The mutex-protected async
                        // remove on the queue then persists the change.
                        // This avoids the off-by-one bug where two quick
                        // taps would remove rows N and N+1 instead of N
                        // and N+2 (because the second tap saw the stale
                        // pre-first-remove list).
                        if (idx in upcoming.indices && idx < priorityCount) {
                            upcoming = upcoming.toMutableList().also { it.removeAt(idx) }
                            priorityCount -= 1
                            stationPlayback.removeFromQueue(idx)
                        }
                    },
                    onClearQueue = {
                        // Optimistic: drop all priority items from the
                        // visible list immediately. Rule-driven peeks
                        // remain so users still see what's coming up
                        // from the station. The async clearQueue catches up.
                        if (priorityCount > 0) {
                            upcoming = upcoming.drop(priorityCount)
                            priorityCount = 0
                            stationPlayback.clearQueue()
                        }
                    },
                    onMoveQueueItem = { from, to ->
                        // Optimistic local reorder of the displayed list,
                        // then mutex-protected commit to the queue. Both
                        // indices must be in priority range — rule-driven
                        // peeks aren't reorderable (they regenerate every
                        // peek anyway).
                        if (from in 0 until priorityCount && to in 0 until priorityCount) {
                            upcoming =
                                upcoming.toMutableList().also {
                                    val item = it.removeAt(from)
                                    it.add(to, item)
                                }
                            stationPlayback.moveQueueItem(from, to)
                        }
                    },
                    onSaveAsPlaylist = {
                        if (upcoming.isNotEmpty()) {
                            saveQueueDialog = upcoming.toList()
                            queueSheetOpen = false
                        }
                    },
                    onReplayPlayed = { track ->
                        // Re-inject at the head of the priority queue.
                        // Distinct from "jumpUpcoming" — the played row
                        // is no longer in `upcoming`, so we route through
                        // stationPlayback.playNext for a fresh enqueue.
                        stationPlayback.playNext(track)
                        hapticTick()
                    },
                    onMoveUpcomingToTop = { idx ->
                        // Optimistic local reorder + mutex-protected
                        // commit, mirroring onMoveQueueItem. Bail when
                        // the row is already at position 0 — saves a
                        // wasted DB round-trip.
                        if (idx in 1 until priorityCount) {
                            upcoming =
                                upcoming.toMutableList().also {
                                    val item = it.removeAt(idx)
                                    it.add(0, item)
                                }
                            stationPlayback.moveQueueItem(idx, 0)
                            hapticTick()
                        }
                    },
                )
            }
            saveQueueDialog?.let { tracks ->
                SaveQueueAsPlaylistDialog(
                    trackCount = tracks.size,
                    onDismiss = { saveQueueDialog = null },
                    onConfirm = { name ->
                        val toSave = tracks
                        saveQueueDialog = null
                        scope.launch {
                            val pid = playlistRepo.insert(name = name, description = null)
                            // One transaction via appendTracks — was one addTrack
                            // call per queued track, each in its own transaction and
                            // swallowing failures silently.
                            runCatching { playlistRepo.appendTracks(pid, toSave.map { it.id }) }
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
                        onOpenAlbum = onOpenAlbum,
                        onOpenArtist = onOpenArtist,
                        onOpenFolder = onOpenFolder,
                    )
                } else {
                    trackInfoOpen = false
                }
            }
            if (speedSheetOpen) {
                PlaybackSpeedSheet(
                    current = playbackSpeed,
                    onPick = { v ->
                        playbackSpeed = v
                        settings.setPlaybackSpeed(v)
                        audioEngine.setPlaybackSpeed(v)
                        speedSheetOpen = false
                    },
                    onDismiss = { speedSheetOpen = false },
                )
            }
            if (isNarrow && overflowMenuOpen) {
                OverflowSheet(
                    track = nowPlaying,
                    stationId = currentStation?.id,
                    playbackSpeed = playbackSpeed,
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
                    onGoToStation = { sid ->
                        overflowMenuOpen = false
                        onOpenStation(sid)
                    },
                    onTrackInfo = {
                        overflowMenuOpen = false
                        trackInfoOpen = true
                    },
                    onSpeedPicker = {
                        overflowMenuOpen = false
                        speedSheetOpen = true
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
                        if (target) {
                            ratingRepo.like(playable.trackId)
                        } else {
                            ratingRepo.unlike(playable.trackId)
                        }
                    }
                }

                if (resolvedLayout == NowPlayingLayoutChoice.TriplePane) {
                    TriplePaneLayout(
                        playable = playable,
                        state = state,
                        positionMs = { position.currentMs },
                        durationMs = { position.durationMs },
                        rating = rating,
                        liked = liked,
                        accent = accent,
                        stationName = currentStation?.name,
                        formatLine = formatLine,
                        stations = stationsForTriplePane,
                        currentStationId = currentStation?.id,
                        // playStation isn't a suspend fun — it launches
                        // its own internal stationLoadJob. Calling it
                        // directly avoids a misleading scope.launch
                        // wrapper that would suggest otherwise.
                        onStationTap = { id -> stationPlayback.playStation(id) },
                        onSeek = { audioEngine.seekTo(it) },
                        onTogglePlayPause = { audioEngine.togglePlayPause() },
                        onLongPressTogglePlayPause = {
                            stationPlayback.toggleShuffle()
                            hapticTick()
                        },
                        onPrev = { stationPlayback.skipPrevious() },
                        onNext = { stationPlayback.skipNext() },
                        onSkipFive = { repeat(5) { stationPlayback.skipNext() } },
                        onRate = { stars ->
                            scope.launch {
                                ratingRepo.setRating(playable.trackId, stars)
                                rating = stars
                            }
                        },
                        onToggleLike = onToggleLike,
                        preMuteLevel = preMuteLevel,
                        onPreMuteLevelChange = { preMuteLevel = it },
                        onDislikeOnStation = {
                            val stationId = currentStation?.id ?: return@TriplePaneLayout
                            scope.launch {
                                ratingRepo.dislikeForStation(playable.trackId, stationId)
                                stationPlayback.skipNext()
                            }
                        },
                        onTapArtwork = { artworkLightboxOpen = true },
                        onPinchOpenLyrics = onOpenLyrics,
                        onTapArtist = {
                            scope.launch {
                                libraryRepo.artistsForTrack(playable.trackId)
                                    .firstOrNull()?.id?.let { onOpenArtist(it) }
                            }
                        },
                        onTapAlbum = {
                            scope.launch {
                                libraryRepo.getTrackById(playable.trackId)
                                    ?.albumId?.let { onOpenAlbum(it) }
                            }
                        },
                        onTapStation = { currentStation?.id?.let(onOpenStation) },
                        driveMode = driveMode,
                        queueProgress = finiteQueueProgress,
                    )
                } else if (isCompact) {
                    CompactLayout(
                        playable = playable,
                        state = state,
                        positionMs = { position.currentMs },
                        durationMs = { position.durationMs },
                        rating = rating,
                        liked = liked,
                        accent = accent,
                        stationName = currentStation?.name,
                        formatLine = formatLine,
                        onSeek = { audioEngine.seekTo(it) },
                        onTogglePlayPause = { audioEngine.togglePlayPause() },
                        onLongPressTogglePlayPause = {
                            stationPlayback.toggleShuffle()
                            hapticTick()
                        },
                        onPrev = { stationPlayback.skipPrevious() },
                        onNext = { stationPlayback.skipNext() },
                        onSkipFive = {
                            // Five rapid skipNext() calls. Each advances the
                            // queue inside StationPlayback's mutex — running
                            // them in a tight loop is safe because each
                            // re-acquires the lock; we just want the user
                            // to see five tracks fly past in their notification
                            // / now-playing UI without separately tapping.
                            repeat(5) { stationPlayback.skipNext() }
                        },
                        onRate = { stars ->
                            scope.launch {
                                ratingRepo.setRating(playable.trackId, stars)
                                rating = stars
                            }
                        },
                        onToggleLike = onToggleLike,
                        preMuteLevel = preMuteLevel,
                        onPreMuteLevelChange = { preMuteLevel = it },
                        onDislikeOnStation = {
                            val stationId = currentStation?.id ?: return@CompactLayout
                            scope.launch {
                                ratingRepo.dislikeForStation(playable.trackId, stationId)
                                stationPlayback.skipNext()
                            }
                        },
                        onTapArtwork = { artworkLightboxOpen = true },
                        onPinchOpenLyrics = onOpenLyrics,
                        onTapArtist = {
                            scope.launch {
                                libraryRepo.artistsForTrack(playable.trackId)
                                    .firstOrNull()?.id?.let { onOpenArtist(it) }
                            }
                        },
                        onTapAlbum = {
                            scope.launch {
                                libraryRepo.getTrackById(playable.trackId)
                                    ?.albumId?.let { onOpenAlbum(it) }
                            }
                        },
                        onTapStation = {
                            currentStation?.id?.let(onOpenStation)
                        },
                        driveMode = driveMode,
                        queueProgress = finiteQueueProgress,
                    )
                } else {
                    TwoPaneLayout(
                        playable = playable,
                        state = state,
                        positionMs = { position.currentMs },
                        durationMs = { position.durationMs },
                        rating = rating,
                        liked = liked,
                        accent = accent,
                        stationName = currentStation?.name,
                        formatLine = formatLine,
                        onSeek = { audioEngine.seekTo(it) },
                        onTogglePlayPause = { audioEngine.togglePlayPause() },
                        onLongPressTogglePlayPause = {
                            stationPlayback.toggleShuffle()
                            hapticTick()
                        },
                        onPrev = { stationPlayback.skipPrevious() },
                        onNext = { stationPlayback.skipNext() },
                        onSkipFive = {
                            // Five rapid skipNext() calls. Each advances the
                            // queue inside StationPlayback's mutex — running
                            // them in a tight loop is safe because each
                            // re-acquires the lock; we just want the user
                            // to see five tracks fly past in their notification
                            // / now-playing UI without separately tapping.
                            repeat(5) { stationPlayback.skipNext() }
                        },
                        onRate = { stars ->
                            scope.launch {
                                ratingRepo.setRating(playable.trackId, stars)
                                rating = stars
                            }
                        },
                        onToggleLike = onToggleLike,
                        preMuteLevel = preMuteLevel,
                        onPreMuteLevelChange = { preMuteLevel = it },
                        onDislikeOnStation = {
                            val stationId = currentStation?.id ?: return@TwoPaneLayout
                            scope.launch {
                                ratingRepo.dislikeForStation(playable.trackId, stationId)
                                stationPlayback.skipNext()
                            }
                        },
                        onTapArtwork = { artworkLightboxOpen = true },
                        onPinchOpenLyrics = onOpenLyrics,
                        onTapArtist = {
                            scope.launch {
                                libraryRepo.artistsForTrack(playable.trackId)
                                    .firstOrNull()?.id?.let { onOpenArtist(it) }
                            }
                        },
                        onTapAlbum = {
                            scope.launch {
                                libraryRepo.getTrackById(playable.trackId)
                                    ?.albumId?.let { onOpenAlbum(it) }
                            }
                        },
                        onTapStation = {
                            currentStation?.id?.let(onOpenStation)
                        },
                        driveMode = driveMode,
                        queueProgress = finiteQueueProgress,
                    )
                }
                if (artworkLightboxOpen) {
                    ArtworkLightbox(
                        artworkUri = playable.artworkUri,
                        accent = accent,
                        onDismiss = { artworkLightboxOpen = false },
                    )
                }
            }
        }
    } // BoxWithConstraints
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverflowSheet(
    track: com.visorcraft.radioshed.audio.Playable?,
    stationId: Long?,
    playbackSpeed: Float,
    onDismiss: () -> Unit,
    onAddToPlaylist: (Long) -> Unit,
    onGoToAlbum: (Long) -> Unit,
    onGoToArtist: (Long) -> Unit,
    onGoToStation: (Long) -> Unit,
    onTrackInfo: () -> Unit,
    onSpeedPicker: () -> Unit,
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
                icon = Icons.Filled.Whatshot,
                label = "Go to station",
                enabled = stationId != null,
                onClick = { stationId?.let(onGoToStation) },
            )
            // Playback-speed picker mirrors the wide-screen DropdownMenu
            // entry. Without this, narrow-screen users (Galaxy Fold cover,
            // small budget phones <360dp) had no way to reach the speed
            // sheet because the menu was the only entry point.
            OverflowSheetItem(
                icon = Icons.Filled.Speed,
                label =
                    if (playbackSpeed == 1f) {
                        "Speed (1×)"
                    } else {
                        "Speed (${formatSpeed(playbackSpeed)}×)"
                    },
                enabled = true,
                onClick = onSpeedPicker,
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
        modifier =
            Modifier
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
    positionMs: () -> Long,
    durationMs: () -> Long,
    rating: Double,
    liked: Boolean,
    accent: Color,
    stationName: String?,
    formatLine: String?,
    onSeek: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onLongPressTogglePlayPause: (() -> Unit)? = null,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSkipFive: (() -> Unit)? = null,
    onRate: (Double) -> Unit,
    onToggleLike: () -> Unit,
    preMuteLevel: Float,
    onPreMuteLevelChange: (Float) -> Unit,
    onDislikeOnStation: () -> Unit,
    onTapArtwork: () -> Unit,
    onPinchOpenLyrics: () -> Unit,
    onTapArtist: () -> Unit,
    onTapAlbum: () -> Unit,
    onTapStation: () -> Unit,
    driveMode: Boolean,
    queueProgress: Pair<Int, Int>? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AlbumArtPlaceholder(
            accent = accent,
            artworkUri = playable.artworkUri,
            modifier =
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f),
            onTap = onTapArtwork,
            onPinchOpen = onPinchOpenLyrics,
        )
        Spacer(Modifier.height(24.dp))
        TrackInfo(
            playable = playable,
            stationName = stationName,
            alignment = Alignment.CenterHorizontally,
            onTapArtist = onTapArtist,
            onTapAlbum = onTapAlbum,
            onTapStation = onTapStation,
            queueProgress = queueProgress,
        )
        Spacer(Modifier.height(16.dp))
        Scrubber(positionMs, durationMs, onSeek)
        if (!formatLine.isNullOrBlank()) {
            Text(
                text = formatLine,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        TransportRow(
            state = state,
            onTogglePlayPause = onTogglePlayPause,
            onLongPressTogglePlayPause = onLongPressTogglePlayPause,
            onPrev = onPrev,
            onLongPressPrev = { onSeek(0L) },
            onNext = onNext,
            onSkipFive = onSkipFive,
            onReplay = { delta -> onSeek((positionMs() - delta).coerceAtLeast(0L)) },
            onForward = { delta ->
                val cap = if (durationMs() > 0) (durationMs() - 250L).coerceAtLeast(0L) else Long.MAX_VALUE
                onSeek((positionMs() + delta).coerceAtMost(cap))
            },
            onDislikeOnStation = onDislikeOnStation,
            driveMode = driveMode,
            preMuteLevel = preMuteLevel,
            onPreMuteLevelChange = onPreMuteLevelChange,
        )
        Spacer(Modifier.height(20.dp))
        RatingRow(
            current = rating,
            liked = liked,
            onRate = onRate,
            onToggleLike = onToggleLike,
        )
    }
}

@Composable
private fun TwoPaneLayout(
    playable: com.visorcraft.radioshed.audio.Playable,
    state: PlayerState,
    positionMs: () -> Long,
    durationMs: () -> Long,
    rating: Double,
    liked: Boolean,
    accent: Color,
    stationName: String?,
    formatLine: String?,
    onSeek: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onLongPressTogglePlayPause: (() -> Unit)? = null,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSkipFive: (() -> Unit)? = null,
    onRate: (Double) -> Unit,
    onToggleLike: () -> Unit,
    preMuteLevel: Float,
    onPreMuteLevelChange: (Float) -> Unit,
    onDislikeOnStation: () -> Unit,
    onTapArtwork: () -> Unit,
    onPinchOpenLyrics: () -> Unit,
    onTapArtist: () -> Unit,
    onTapAlbum: () -> Unit,
    onTapStation: () -> Unit,
    driveMode: Boolean,
    queueProgress: Pair<Int, Int>? = null,
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
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            AlbumArtPlaceholder(
                accent = accent,
                artworkUri = playable.artworkUri,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = 560.dp)
                        .aspectRatio(1f),
                onTap = onTapArtwork,
                onPinchOpen = onPinchOpenLyrics,
            )
        }
        Spacer(Modifier.width(32.dp))
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
        ) {
            TrackInfo(
                playable = playable,
                stationName = stationName,
                alignment = Alignment.Start,
                onTapArtist = onTapArtist,
                onTapAlbum = onTapAlbum,
                onTapStation = onTapStation,
                queueProgress = queueProgress,
            )
            Spacer(Modifier.height(24.dp))
            Scrubber(positionMs, durationMs, onSeek)
            if (!formatLine.isNullOrBlank()) {
                Text(
                    text = formatLine,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            TransportRow(
                state = state,
                onTogglePlayPause = onTogglePlayPause,
                onLongPressTogglePlayPause = onLongPressTogglePlayPause,
                onPrev = onPrev,
                onLongPressPrev = { onSeek(0L) },
                onNext = onNext,
                onSkipFive = onSkipFive,
                onReplay = { delta -> onSeek((positionMs() - delta).coerceAtLeast(0L)) },
                onForward = { delta ->
                    val cap = if (durationMs() > 0) (durationMs() - 250L).coerceAtLeast(0L) else Long.MAX_VALUE
                    onSeek((positionMs() + delta).coerceAtMost(cap))
                },
                onDislikeOnStation = onDislikeOnStation,
                driveMode = driveMode,
                preMuteLevel = preMuteLevel,
                onPreMuteLevelChange = onPreMuteLevelChange,
            )
            Spacer(Modifier.height(28.dp))
            RatingRow(
                current = rating,
                liked = liked,
                onRate = onRate,
                onToggleLike = onToggleLike,
            )
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
private fun BlurredBackdrop(
    artworkUri: String?,
    reduceMotion: Boolean,
) {
    if (artworkUri == null) return
    val scrimColor = MaterialTheme.colorScheme.background.copy(alpha = 0.55f)
    Box(modifier = Modifier.fillMaxSize()) {
        if (!reduceMotion) {
            coil3.compose.AsyncImage(
                model = artworkUri,
                contentDescription = null,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .blur(radius = 80.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                alpha = 0.6f,
            )
        }
        Box(modifier = Modifier.fillMaxSize().background(scrimColor))
    }
}

/**
 * Fullscreen lightbox for the album artwork. Tap the cover anywhere
 * on Now Playing → this overlay slides on top of the player. Tap the
 * dimmed scrim or back-press to dismiss. Renders the art at the
 * largest square that fits the viewport, on a near-black scrim that
 * lets the image dominate. Fallback gradient when no artwork URI —
 * still useful so the lightbox isn't a no-op for files without art.
 */
@Composable
private fun ArtworkLightbox(
    artworkUri: String?,
    accent: Color,
    onDismiss: () -> Unit,
) {
    com.visorcraft.radioshed.nav.PlatformBackHandler(onBack = onDismiss)
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
                    .widthIn(max = 720.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(accent, accent.copy(alpha = 0.55f)),
                        ),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (artworkUri != null) {
                coil3.compose.AsyncImage(
                    model = artworkUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
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
}

@Composable
private fun AlbumArtPlaceholder(
    accent: Color,
    artworkUri: String?,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
    onPinchOpen: () -> Unit = {},
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(accent, accent.copy(alpha = 0.55f)),
                    ),
                )
                .clickable(onClick = onTap)
                // Pinch-to-lyrics gesture: a quick outward pinch on the
                // artwork jumps to the lyrics screen, mirroring the
                // gestural pattern of "zoom in to see more". Cumulative
                // zoom threshold of 1.20× avoids accidental triggers and
                // a `fired` flag inside awaitEachGesture ensures the
                // navigation only fires once per gesture even though
                // detectTransformGestures' callback is called per move
                // event (it would otherwise push Lyrics multiple times
                // onto the back stack on a fast continuous pinch).
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var cumulativeZoom = 1f
                        var fired = false
                        while (true) {
                            val event = awaitPointerEvent()
                            cumulativeZoom *= event.calculateZoom()
                            if (!fired && cumulativeZoom > 1.20f) {
                                fired = true
                                onPinchOpen()
                            }
                            if (event.changes.none { it.pressed }) break
                        }
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        if (artworkUri != null) {
            // Two-layer composition for non-square artwork: the
            // blurred backdrop is the same image cropped to fill,
            // and the foreground uses `Fit` so non-square sources
            // letterbox cleanly INTO the blurred edges instead of
            // showing dead gradient strips. For square art the two
            // layers are pixel-identical inside the frame and the
            // composition reads as a single image — there's no
            // visible cost. The tradeoff vs the previous straight-
            // crop approach: square art is unchanged; non-square
            // art now shows the full image (no edge clipping) with
            // its own blurred halo filling the letterbox.
            coil3.compose.AsyncImage(
                model = artworkUri,
                contentDescription = null,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .blur(28.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                alpha = 0.85f,
            )
            coil3.compose.AsyncImage(
                model = artworkUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
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
    onTapArtist: () -> Unit = {},
    onTapAlbum: () -> Unit = {},
    onTapStation: () -> Unit = {},
    queueProgress: Pair<Int, Int>? = null,
) {
    Column(horizontalAlignment = alignment, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = playable.title.withoutBracketGroups().ifBlank { "Unknown title" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (alignment == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start,
        )
        // Artist · album rendered as separate spans so each is its
        // own tap target. Saves a trip through the overflow menu when
        // the user wants to drill into the artist's catalogue or the
        // current album. Falls back to a non-clickable Text if both
        // fields are blank — neither span is meaningful then.
        val artist = playable.artist?.takeIf { it.isNotBlank() }
        val album = playable.album?.takeIf { it.isNotBlank() }
        if (artist != null || album != null) {
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement =
                    if (alignment == Alignment.CenterHorizontally) {
                        Arrangement.Center
                    } else {
                        Arrangement.Start
                    },
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val subStyle = MaterialTheme.typography.bodyLarge
                val subColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
                if (artist != null) {
                    Text(
                        text = artist,
                        style = subStyle,
                        color = subColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier
                                .clickable(onClick = onTapArtist)
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                if (artist != null && album != null) {
                    Text(
                        text = " · ",
                        style = subStyle,
                        color = subColor,
                    )
                }
                if (album != null) {
                    Text(
                        text = album,
                        style = subStyle,
                        color = subColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier
                                .clickable(onClick = onTapAlbum)
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
        if (stationName != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "from $stationName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                textAlign = if (alignment == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start,
                modifier =
                    Modifier
                        .clickable(onClick = onTapStation)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        // Track N of M chip — only shown when we're playing a finite
        // queue (the user tapped a track in a list view). Hidden in
        // station mode since the candidate pool is unbounded there.
        queueProgress?.let { (current, total) ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Track $current of $total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                textAlign = if (alignment == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start,
            )
        }
    }
}

@Composable
private fun Scrubber(
    position: () -> Long,
    duration: () -> Long,
    onSeek: (Long) -> Unit,
) {
    var isUserScrubbing by remember { mutableStateOf(false) }
    var pendingFraction by remember { mutableFloatStateOf(0f) }
    var seekDialogOpen by remember { mutableStateOf(false) }

    // Deferred reads: the ~4 Hz position tick is a StateFlow that updates
    // several times a second. Invoking the providers HERE — inside the
    // Scrubber's own recompose scope — is what confines that tick to this
    // seekbar. If the caller read position.currentMs eagerly and passed a
    // Long, the whole NowPlaying screen (and the station side-pane it hosts)
    // would recompose on every tick. Keep these reads local to Scrubber.
    val positionMs = position()
    val durationMs = duration()
    val durationSafe = durationMs.coerceAtLeast(0L)
    val currentFraction =
        if (durationSafe == 0L) {
            0f
        } else {
            (positionMs.toFloat() / durationSafe).coerceIn(0f, 1f)
        }
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
            // Tap the position label to open a precise jump-to-time
            // dialog. The slider handles coarse seeks; a typed input
            // is the right tool for "skip to 1:23" on long
            // podcasts/sets where the slider's hit zone is ~10s wide.
            val positionClipboard = com.visorcraft.radioshed.ui.util.rememberCopyToClipboard()
            Text(
                text = formatMs(if (isUserScrubbing) (pendingFraction * durationSafe).toLong() else positionMs),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier =
                    Modifier.combinedClickable(
                        enabled = durationSafe > 0L,
                        onClick = { seekDialogOpen = true },
                        onLongClick = {
                            val elapsed = formatMs(positionMs)
                            val total = formatMs(durationSafe)
                            positionClipboard("$elapsed / $total")
                        },
                    ),
            )
            Text(
                text = formatMs(durationSafe),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
    }

    if (seekDialogOpen) {
        SeekToTimeDialog(
            currentMs = positionMs,
            durationMs = durationSafe,
            onDismiss = { seekDialogOpen = false },
            onConfirm = { targetMs ->
                seekDialogOpen = false
                onSeek(targetMs.coerceIn(0L, durationSafe))
            },
        )
    }
}

/**
 * Precise seek prompt — accepts `M:SS` / `H:MM:SS` / bare seconds.
 * Pre-fills with the current position formatted as `M:SS` so the
 * user can edit incrementally rather than retype. Invalid entries
 * keep the dialog open with no-op (the user can clear and retry).
 */
@Composable
private fun SeekToTimeDialog(
    currentMs: Long,
    durationMs: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var input by remember { mutableStateOf(formatMs(currentMs)) }
    val parsed = remember(input) { parseSeekInput(input) }
    val confirm: () -> Unit = {
        parsed?.let { ms -> onConfirm(ms) }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Seek to time") },
        text = {
            Column {
                Text(
                    "Track length: ${formatMs(durationMs)}. Accepts " +
                        "M:SS, H:MM:SS, or seconds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Time") },
                    singleLine = true,
                    isError = parsed == null,
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        ),
                    keyboardActions =
                        androidx.compose.foundation.text.KeyboardActions(
                            onDone = { confirm() },
                        ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = confirm, enabled = parsed != null) { Text("Seek") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Parse a user-entered seek target into milliseconds. Supports:
 *  - Plain seconds:        "83"     → 83_000 ms
 *  - Minutes:seconds:      "1:23"   → 83_000 ms
 *  - Hours:minutes:seconds:"1:02:03"→ 3_723_000 ms
 * Returns null on malformed input. Trailing/leading whitespace OK.
 */
internal fun parseSeekInput(raw: String): Long? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val parts = trimmed.split(":")
    return runCatching {
        when (parts.size) {
            1 -> parts[0].toLong() * 1000L
            2 -> parts[0].toLong() * 60_000L + parts[1].toLong() * 1000L
            3 -> parts[0].toLong() * 3_600_000L + parts[1].toLong() * 60_000L + parts[2].toLong() * 1000L
            else -> return null
        }.takeIf { it >= 0L }
    }.getOrNull()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransportRow(
    state: PlayerState,
    onTogglePlayPause: () -> Unit,
    onLongPressTogglePlayPause: (() -> Unit)? = null,
    onPrev: () -> Unit,
    onLongPressPrev: (() -> Unit)? = null,
    onNext: () -> Unit,
    onSkipFive: (() -> Unit)? = null,
    compact: Boolean = false,
    /**
     * Tap = 10s, long-press = 30s. The argument is the delta in
     * milliseconds (always positive); the parent decides direction
     * via which callback is invoked.
     */
    onReplay: (deltaMs: Long) -> Unit,
    onForward: (deltaMs: Long) -> Unit,
    onDislikeOnStation: () -> Unit,
    driveMode: Boolean,
    // Threaded through to DriveTransportRow's mute button. Unused by the
    // non-drive layout below.
    preMuteLevel: Float = 0.6f,
    onPreMuteLevelChange: (Float) -> Unit = {},
) {
    var dislikeMenuOpen by remember { mutableStateOf(false) }
    val playing = state == PlayerState.Playing

    // Drive Mode swaps the whole transport row for the configurable
    // big-button bar (Task 2.4). The configured set + ordering + the
    // station-picker / voice-search / mute actions all live in
    // DriveTransportRow; the three transport-core gestures pass straight
    // through. Returning early here keeps the non-drive layout below
    // 100% untouched.
    if (driveMode) {
        com.visorcraft.radioshed.ui.car.DriveTransportRow(
            state = state,
            onPrev = onPrev,
            onLongPressPrev = onLongPressPrev,
            onTogglePlayPause = onTogglePlayPause,
            onLongPressTogglePlayPause = onLongPressTogglePlayPause,
            onNext = onNext,
            // Long-press Next mirrors the dropdown's "don't play on this
            // station again" so the dislike affordance survives the re-skin.
            onLongPressNext = onDislikeOnStation,
            preMuteLevel = preMuteLevel,
            onPreMuteLevelChange = onPreMuteLevelChange,
        )
        return
    }

    // Sizes match the original Material recommendation; drive-mode sizing
    // now lives in DriveTransportRow (returned early above).
    val baseSize = 64.dp
    val emphasizedSize = 80.dp
    val iconSize = 36.dp
    val emphasizedIconSize = 48.dp

    // Smaller seek-±10s buttons; hidden in compact mode so the primary
    // three transport targets stay roomy. Useful for podcast-y catch-up
    // and missed-chorus rewind.
    val seekButtonSize = baseSize * 0.7f
    val seekIconSize = iconSize * 0.7f

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BigIconButton(
            icon = Icons.Filled.SkipPrevious,
            description = "Previous (long-press to restart current)",
            buttonSize = baseSize,
            iconSize = iconSize,
            onClick = onPrev,
            onLongClick = onLongPressPrev,
        )

        if (!compact) {
            // Replay: tap = 10s back, long-press = 30s back. Pattern
            // mirrors podcast apps' fast-rewind affordance.
            Box {
                Surface(
                    color = Color.Transparent,
                    modifier =
                        Modifier
                            .size(seekButtonSize)
                            .clip(RoundedCornerShape(seekButtonSize / 2))
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onReplay(10_000L) },
                                onLongClick = { onReplay(30_000L) },
                            )
                            .semantics { contentDescription = "Back 10 seconds (long-press 30)" },
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Replay10,
                            contentDescription = null,
                            modifier = Modifier.size(seekIconSize),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
        }

        BigIconButton(
            icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            description = if (playing) "Pause" else "Play",
            buttonSize = emphasizedSize,
            iconSize = emphasizedIconSize,
            onClick = onTogglePlayPause,
            onLongClick = onLongPressTogglePlayPause,
        )

        if (!compact) {
            // Forward: tap = 10s, long-press = 30s. See onReplay above
            // for the rationale.
            Box {
                Surface(
                    color = Color.Transparent,
                    modifier =
                        Modifier
                            .size(seekButtonSize)
                            .clip(RoundedCornerShape(seekButtonSize / 2))
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onForward(10_000L) },
                                onLongClick = { onForward(30_000L) },
                            )
                            .semantics { contentDescription = "Forward 10 seconds (long-press 30)" },
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Forward10,
                            contentDescription = null,
                            modifier = Modifier.size(seekIconSize),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
        }

        // Skip-next: tap = skip; long-press opens "don't play on this station" menu.
        Box {
            Surface(
                color = Color.Transparent,
                modifier =
                    Modifier
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
                if (onSkipFive != null) {
                    DropdownMenuItem(
                        text = { Text("Skip 5 ahead") },
                        onClick = {
                            onSkipFive()
                            dislikeMenuOpen = false
                        },
                    )
                }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BigIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    buttonSize: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    // Vector icons everywhere — see MiniPlayer.TransportIconButton for why
    // we don't use Unicode media glyphs (Samsung Noto vs Roboto vs SF Pro
    // render U+23F8 with different metrics; vector icons are font-free).
    // The Tooltipped wrapper is no-op on touch builds and adds the
    // hover tooltip on desktop, so every transport button gets one
    // for free.
    if (onLongClick == null) {
        com.visorcraft.radioshed.ui.util.TooltipIconButton(
            tooltip = description,
            onClick = onClick,
            modifier =
                Modifier
                    .size(buttonSize)
                    .semantics { contentDescription = description },
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
    } else {
        // IconButton has no built-in long-click, so we drop down to a
        // Surface + combinedClickable when we need both gestures. The
        // hit-target stays the same `buttonSize` so the visual layout
        // doesn't shift when long-press is wired in.
        com.visorcraft.radioshed.ui.util.Tooltipped(tooltip = description) {
            Surface(
                color = Color.Transparent,
                modifier =
                    Modifier
                        .size(buttonSize)
                        .clip(RoundedCornerShape(buttonSize / 2))
                        .combinedClickable(
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )
                        .semantics { contentDescription = description },
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(iconSize),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        } // close Tooltipped(description)
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
        com.visorcraft.radioshed.ui.util.TooltipIconButton(
            tooltip = if (liked) "Unlike" else "Like",
            onClick = onToggleLike,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector =
                    if (liked) {
                        Icons.Filled.Favorite
                    } else {
                        Icons.Outlined.FavoriteBorder
                    },
                contentDescription = if (liked) "Unlike" else "Like",
                modifier = Modifier.size(28.dp),
                tint =
                    if (liked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    },
            )
        }
        Spacer(Modifier.width(4.dp))
        (1..5).forEach { n ->
            val nDouble = n.toDouble()
            val halfValue = nDouble - 0.5
            val starState =
                when {
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
                modifier =
                    Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "Rating star $n" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector =
                        when (starState) {
                            StarState.Full -> Icons.Filled.Star
                            StarState.Half -> Icons.AutoMirrored.Filled.StarHalf
                            StarState.Empty -> Icons.Outlined.StarOutline
                        },
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint =
                        when (starState) {
                            StarState.Empty -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            else -> MaterialTheme.colorScheme.primary
                        },
                )
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable {
                                    val target = if (current == halfValue) 0.0 else halfValue
                                    onRate(target)
                                },
                    )
                    Box(
                        modifier =
                            Modifier
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
 * Speaker button immediately left of the other Now Playing actions.
 * Opens a vertical engine-gain slider above itself. The hardware
 * rocker still changes the system stream.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BottomVolumeButton(
    volume: Float,
    onVolumeLive: (Float) -> Unit,
    onVolumeCommit: (Float) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    // Tall enough to clear the button and sit above the bottom bar.
    val popupOffsetY = with(LocalDensity.current) { (-248).dp.roundToPx() }
    val sliderState = rememberSliderState(value = volume)
    LaunchedEffect(sliderState) {
        sliderState.onValueChange = { onVolumeLive(it) }
        sliderState.onValueChangeFinished = { onVolumeCommit(sliderState.value) }
    }
    Box {
        com.visorcraft.radioshed.ui.util.TooltipIconButton(
            tooltip = "Volume",
            onClick = { open = !open },
        ) {
            Icon(
                imageVector =
                    when {
                        sliderState.value < 0.02f -> Icons.AutoMirrored.Filled.VolumeOff
                        sliderState.value < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
                        else -> Icons.AutoMirrored.Filled.VolumeUp
                    },
                contentDescription = "Volume",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (open) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, popupOffsetY),
                onDismissRequest = {
                    onVolumeCommit(sliderState.value)
                    open = false
                },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "Volume",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(Modifier.height(8.dp))
                        // reverseDirection keeps full volume at the top.
                        VerticalSlider(
                            state = sliderState,
                            modifier =
                                Modifier
                                    .height(200.dp)
                                    .focusRequester(focusRequester),
                            reverseDirection = true,
                        )
                    }
                }
            }
            LaunchedEffect(Unit) {
                runCatching { focusRequester.requestFocus() }
            }
        }
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
    onOpenAlbum: (Long) -> Unit = {},
    onOpenArtist: (Long) -> Unit = {},
    onOpenFolder: (String) -> Unit = {},
) {
    val stationRepo: com.visorcraft.radioshed.data.StationRepository = koinInject()
    val ringtoneSetter: com.visorcraft.radioshed.source.RingtoneSetter = koinInject()
    val trackSharer: com.visorcraft.radioshed.source.TrackSharer = koinInject()
    val scope = rememberCoroutineScope()
    var track by remember { mutableStateOf<Track?>(null) }
    var ringtoneStatus by remember { mutableStateOf<String?>(null) }
    var stats by remember {
        mutableStateOf<com.visorcraft.radioshed.data.TrackHistoryStats?>(null)
    }
    var genres by remember { mutableStateOf<List<String>>(emptyList()) }
    var primaryArtistId by remember { mutableStateOf<Long?>(null) }
    var showEditTags by remember { mutableStateOf(false) }
    var artistBio by remember {
        mutableStateOf<com.visorcraft.radioshed.source.WikipediaSummary?>(null)
    }
    val wikipediaFetcher: com.visorcraft.radioshed.source.WikipediaBioFetcher = koinInject()
    val networkPolicy: com.visorcraft.radioshed.source.NetworkPolicyGate = koinInject()
    // Bumped after a successful tag-edit save so the LaunchedEffect
    // below re-fetches the track row + stats; otherwise the dialog
    // would still show the pre-edit values until the user closed and
    // re-opened it.
    var refreshTick by remember { mutableStateOf(0) }
    LaunchedEffect(playable.trackId, refreshTick) {
        track = libraryRepo.getTrackById(playable.trackId)
        stats = stationRepo.historyStatsForTrack(playable.trackId)
        genres =
            libraryRepo.tagsForTrack(playable.trackId)
                .filter { it.key.equals("GENRE", ignoreCase = true) }
                .map { it.value }
                .filter { it.isNotBlank() }
                .distinct()
        primaryArtistId = libraryRepo.artistsForTrack(playable.trackId).firstOrNull()?.id
        // Auto-fetch Wikipedia lead-paragraph artist bio. Gated by
        // the Connectivity policy + auto-fetch toggle so a user on
        // Always/Wi-Fi/Never gets the right behaviour without
        // re-checking. Disambiguation pages and 404s surface as
        // null and we just don't render the bio panel.
        artistBio = null
        val artistName = playable.artist?.takeIf { it.isNotBlank() }
        if (artistName != null && networkPolicy.allowAutomaticFetch()) {
            artistBio =
                runCatching {
                    wikipediaFetcher.fetchSummary(artistName)
                }.getOrNull()
        }
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
                        // Open the in-app folder browser at the
                        // track's parent directory. We used to fire
                        // a DocumentsContract intent to the system
                        // file manager, which silently no-op'd on
                        // Samsung / Xiaomi / restricted profiles
                        // because no installed app actually
                        // registered for vnd.android.document/
                        // directory. The internal browser ships our
                        // own UI so behavior is consistent across
                        // OEMs and we don't depend on a third-party
                        // app at all.
                        // parentPath() is separator-agnostic — handles
                        // POSIX `/` AND Windows `\` so an "Open folder"
                        // tap on desktop lands the user in the song's
                        // real directory rather than the filesystem
                        // root. (Pre-fix: the substringBeforeLast('/')
                        // path returned the default "/" on Windows
                        // because Windows paths contain no '/'.)
                        val parent =
                            com.visorcraft.radioshed.source.parentPath(t.path)
                                .ifBlank { "/" }
                        onDismiss()
                        onOpenFolder(parent)
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
                // verticalScroll: Material 3's AlertDialog text slot
                // doesn't auto-scroll on desktop when the body
                // overflows the dialog's max height. The Path row
                // lives at the bottom of a long Column (ringtone /
                // share buttons, MBID rows, history stats, lyrics
                // overrides, …) so on a tall body it would silently
                // clip — which is what produced the "filepath isn't
                // shown on Windows" report.
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                ) {
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
                                    val result =
                                        ringtoneSetter.setAsRingtone(
                                            absolutePath = t.path,
                                            displayName = t.displayTitle(),
                                        )
                                    ringtoneStatus =
                                        when (result) {
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
                                    val ok =
                                        trackSharer.share(
                                            absolutePath = t.path,
                                            displayName = t.displayTitle(),
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
                    // Tag editor — opens a dialog that writes ID3 /
                    // Vorbis / MP4 frames directly to the file via
                    // jaudiotagger and triggers a folder rescan on
                    // success. Sits below the main action row to keep
                    // the most-used buttons visually primary.
                    Spacer(Modifier.height(4.dp))
                    androidx.compose.material3.OutlinedButton(
                        onClick = { showEditTags = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Edit tags…")
                    }
                    Spacer(Modifier.height(4.dp))
                    // One-tap "Artist – Title" copy. Useful for
                    // pasting into a chat or Spotify/YouTube search
                    // when the user wants to share what they're
                    // hearing without sending the actual file.
                    val copyToClipboard = com.visorcraft.radioshed.ui.util.rememberCopyToClipboard()
                    val combined =
                        remember(playable.artist, t.title, t.fileName) {
                            val artistPart = playable.artist?.takeIf { it.isNotBlank() }
                            val titlePart = t.displayTitle()
                            if (artistPart != null) "$artistPart – $titlePart" else titlePart
                        }
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            copyToClipboard(combined)
                            ringtoneStatus = "Copied \"$combined\" to clipboard."
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Copy artist – title")
                    }
                    // Markdown export — packages every visible field
                    // into a fenced block with bold labels, suitable
                    // for pasting into Obsidian / a chat that renders
                    // Markdown / a README. Picks up artist + album
                    // names from the resolved metadata so labels read
                    // correctly even when the file's path is opaque.
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            val md =
                                buildString {
                                    appendLine("**Track:** ${t.displayTitle()}")
                                    playable.artist?.takeIf { it.isNotBlank() }?.let {
                                        appendLine("**Artist:** $it")
                                    }
                                    playable.album?.takeIf { it.isNotBlank() }?.let {
                                        appendLine("**Album:** $it")
                                    }
                                    appendLine("**Format:** ${t.format.uppercase()}")
                                    appendLine("**Duration:** ${formatMs(t.durationMs)}")
                                    t.bitrateKbps?.let { appendLine("**Bitrate:** $it kbps") }
                                    t.sampleRateHz?.let {
                                        appendLine("**Sample rate:** ${(it / 1000.0).formatFixed(1)} kHz")
                                    }
                                    t.trackNumber?.let { appendLine("**Track #:** $it") }
                                    t.bpm?.let { appendLine("**BPM:** ${it.formatFixed(1)}") }
                                }.trimEnd()
                            copyToClipboard(md)
                            ringtoneStatus = "Copied Track Info as Markdown."
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Copy as Markdown")
                    }
                    // Web look-ups. Each opens the system browser at a
                    // search-form URL for the relevant service so the
                    // user can read the band's bio / cross-reference
                    // discographies / find the original pressing
                    // without leaving the listening session for too
                    // long. The artist tag is mandatory for the
                    // artist-only buttons (Wikipedia, MusicBrainz,
                    // Discogs); YouTube combines artist + title for a
                    // direct hit on the music video. We percent-encode
                    // by hand because LRClib/Cover Art Archive are the
                    // only Ktor consumers and we don't want to pull a
                    // URL-encoder dep into commonMain just for these.
                    val artistForWeb = playable.artist?.takeIf { it.isNotBlank() }

                    fun encodeForUrl(s: String): String =
                        s
                            // Order matters: % first (so subsequent escapes
                            // don't double-encode), then literal + (which
                            // would otherwise survive into the result and
                            // be parsed as a space when the URL deserialises),
                            // then space, then path-significant characters.
                            // / matters for artists like AC/DC — without
                            // it the URL path parser splits the artist
                            // name across segments.
                            .replace("%", "%25")
                            .replace("+", "%2B")
                            .replace(" ", "+")
                            .replace("&", "%26")
                            .replace("#", "%23")
                            .replace("?", "%3F")
                            .replace("/", "%2F")
                            .replace("=", "%3D")
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            artistForWeb?.let { name ->
                                com.visorcraft.radioshed.source.openWebUrl(
                                    "https://en.wikipedia.org/wiki/Special:Search?search=${encodeForUrl(name)}",
                                )
                            }
                        },
                        enabled = artistForWeb != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Search Wikipedia for artist")
                    }
                    // Album-scoped Wikipedia. Disambiguating an album
                    // page with the artist name pulls the right hit
                    // (Wikipedia indexes most album titles under the
                    // artist) — bare album titles like "Greatest Hits"
                    // would otherwise return the disambig page first.
                    val albumForWeb = playable.album?.takeIf { it.isNotBlank() }
                    if (albumForWeb != null) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                val q =
                                    if (artistForWeb != null) {
                                        "$albumForWeb $artistForWeb album"
                                    } else {
                                        "$albumForWeb album"
                                    }
                                com.visorcraft.radioshed.source.openWebUrl(
                                    "https://en.wikipedia.org/wiki/Special:Search?search=${encodeForUrl(q)}",
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Search Wikipedia for album")
                        }
                    }
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            artistForWeb?.let { name ->
                                com.visorcraft.radioshed.source.openWebUrl(
                                    "https://musicbrainz.org/search?type=artist&query=${encodeForUrl(name)}",
                                )
                            }
                        },
                        enabled = artistForWeb != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Search MusicBrainz for artist")
                    }
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            artistForWeb?.let { name ->
                                com.visorcraft.radioshed.source.openWebUrl(
                                    "https://www.discogs.com/search/?type=artist&q=${encodeForUrl(name)}",
                                )
                            }
                        },
                        enabled = artistForWeb != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Search Discogs for artist")
                    }
                    // YouTube uses "artist title" if both are present,
                    // falls back to the title alone when the track has
                    // no artist tag (still useful for instrumental /
                    // public-domain rips). Always enabled.
                    val ytQuery =
                        run {
                            val titlePart = t.displayTitle()
                            if (artistForWeb != null) "$artistForWeb $titlePart" else titlePart
                        }
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            com.visorcraft.radioshed.source.openWebUrl(
                                "https://www.youtube.com/results?search_query=${encodeForUrl(ytQuery)}",
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Search YouTube for track")
                    }
                    // SoundCloud + Bandcamp round out the indie / DJ-mix /
                    // unsigned-artist surfaces that MusicBrainz / Discogs
                    // miss. Both use straightforward search-form URLs;
                    // SoundCloud's `/search?q=` and Bandcamp's
                    // `/search?q=` accept percent-encoded queries.
                    // Artist-only when present, falls back to the title
                    // for lone-track searches (some Bandcamp listings
                    // are by track title only).
                    val artistOrTitle = artistForWeb ?: t.displayTitle()
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            com.visorcraft.radioshed.source.openWebUrl(
                                "https://soundcloud.com/search?q=${encodeForUrl(artistOrTitle)}",
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Search SoundCloud")
                    }
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            com.visorcraft.radioshed.source.openWebUrl(
                                "https://bandcamp.com/search?q=${encodeForUrl(artistOrTitle)}",
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Search Bandcamp")
                    }
                    Spacer(Modifier.height(4.dp))
                    InfoLine("Title", t.title)
                    // Artist + Album rows: tappable when the host
                    // installed nav handlers AND we have a target id
                    // to navigate to. Without an id we still show the
                    // text — just non-tappable, since the user
                    // probably still wants to see WHO the artist is
                    // even if drill-in isn't possible.
                    playable.artist?.let { artistName ->
                        val aid = primaryArtistId
                        if (aid != null) {
                            InfoLineTappable("Artist", artistName) {
                                onDismiss()
                                onOpenArtist(aid)
                            }
                        } else {
                            InfoLine("Artist", artistName)
                        }
                    }
                    playable.album?.let { albumName ->
                        val aid = t.albumId
                        if (aid != null) {
                            InfoLineTappable("Album", albumName) {
                                onDismiss()
                                onOpenAlbum(aid)
                            }
                        } else {
                            InfoLine("Album", albumName)
                        }
                    }
                    if (genres.isNotEmpty()) {
                        InfoLine("Genre", genres.joinToString(", "))
                    }
                    artistBio?.let { bio ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "About ${bio.title}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = bio.extract,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            maxLines = 8,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Wikipedia",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier =
                                Modifier.clickable {
                                    com.visorcraft.radioshed.source.openWebUrl(bio.pageUrl)
                                },
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    // Spec rows are tappable-to-copy. Useful when a
                    // user wants to drop "FLAC · 44.1 kHz · 1024 kbps"
                    // into a chat or compare tracks. Each line copies
                    // its own value (the label is implicit context);
                    // a Format-and-Bitrate combo would be nice but the
                    // existing single-line copy keeps the gesture
                    // unambiguous.
                    InfoLineTappable("Format", t.format.uppercase()) {
                        copyToClipboard(t.format.uppercase())
                        ringtoneStatus = "Format copied to clipboard."
                    }
                    InfoLineTappable("Duration", formatMs(t.durationMs)) {
                        copyToClipboard(formatMs(t.durationMs))
                        ringtoneStatus = "Duration copied to clipboard."
                    }
                    t.bitrateKbps?.let { kbps ->
                        InfoLineTappable("Bitrate", "$kbps kbps") {
                            copyToClipboard("$kbps kbps")
                            ringtoneStatus = "Bitrate copied to clipboard."
                        }
                    }
                    t.sampleRateHz?.let { hz ->
                        InfoLineTappable("Sample rate", "${hz / 1000.0} kHz") {
                            copyToClipboard("${hz / 1000.0} kHz")
                            ringtoneStatus = "Sample rate copied to clipboard."
                        }
                    }
                    t.channels?.let { InfoLine("Channels", "$it") }
                    // MusicBrainz recording ID surfaced when the
                    // scanner picked one up from the track's tags
                    // (most modern rippers write it). Tap to open
                    // the recording's MB page directly — no
                    // search-form roundtrip. Trailing "Open"
                    // affordance signals it's a link-out, not a
                    // copy. Long-press copies the raw UUID for
                    // pasting into other tools.
                    t.musicbrainzId?.takeIf { it.isNotBlank() }?.let { mbid ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            com.visorcraft.radioshed.source.openWebUrl(
                                                "https://musicbrainz.org/recording/$mbid",
                                            )
                                        },
                                        onLongClick = {
                                            copyToClipboard(mbid)
                                            ringtoneStatus = "MusicBrainz ID copied to clipboard."
                                        },
                                    ),
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                InfoLine("MusicBrainz ID", mbid, monospace = true)
                            }
                            Text(
                                text = "Open",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    t.bpm?.let { bpm ->
                        InfoLineTappable("BPM", bpm.formatFixed(1)) {
                            copyToClipboard(bpm.formatFixed(1))
                            ringtoneStatus = "BPM copied to clipboard."
                        }
                    }
                    InfoLine("File size", formatFileSize(t.fileSize))
                    InfoLine("Added", formatRelativeDate(t.addedAt))
                    t.replayGainTrack?.let { InfoLine("ReplayGain (track)", "${it.formatFixed(2)} dB") }
                    t.replayGainAlbum?.let { InfoLine("ReplayGain (album)", "${it.formatFixed(2)} dB") }
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        copyToClipboard(t.path)
                                        ringtoneStatus = "Path copied to clipboard."
                                    },
                                    // Long-press the path row copies just
                                    // the file name (basename). Useful for
                                    // pasting into a tag-fix workflow that
                                    // doesn't want the leading directory
                                    // tree.
                                    onLongClick = {
                                        copyToClipboard(t.fileName)
                                        ringtoneStatus = "File name copied to clipboard."
                                    },
                                ),
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            InfoLine("Path", t.path, monospace = true)
                        }
                        Text(
                            text = "Copy",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
    )

    val tEdit = track
    if (showEditTags && tEdit != null) {
        com.visorcraft.radioshed.ui.library.EditTagsDialog(
            track = tEdit,
            onDismiss = { showEditTags = false },
            onSaved = {
                // Bump the refresh tick so the dialog re-fetches the
                // track row + stats and renders the new title /
                // artist / album immediately rather than waiting for
                // the user to close-and-reopen the dialog.
                refreshTick += 1
            },
        )
    }
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
        day < 7L -> "$day days ago"
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
private fun InfoLine(
    label: String,
    value: String,
    monospace: Boolean = false,
) {
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

@Composable
private fun InfoLineTappable(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            // Primary tint flags this row as tappable so users notice
            // the affordance — the dialog has many similar lines and
            // a tappable one needs to stand out a bit.
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueBottomSheet(
    upcoming: List<Track>,
    recent: List<Track>,
    artworkByAlbumId: Map<Long, String?>,
    priorityCount: Int,
    isShuffleMode: Boolean,
    onDismiss: () -> Unit,
    onJumpUpcoming: (Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onMoveQueueItem: (from: Int, to: Int) -> Unit,
    onSaveAsPlaylist: () -> Unit = {},
    onReplayPlayed: (Track) -> Unit = {},
    onMoveUpcomingToTop: (Int) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Convert between LazyColumn-absolute indices (which the
    // DragReorderState reasons over) and upcoming-list indices
    // (0..upcoming.size). The header offset is the count of
    // non-upcoming items rendered ABOVE the upcoming rows.
    val headerOffset =
        remember(recent.size) {
            if (recent.isEmpty()) {
                1 // "Up next" header
            } else {
                1 + recent.size + 1 + 1 + 1 + 1 // played header + recent + spacer + divider + spacer + "Up next"
            }
        }
    val dragState =
        com.visorcraft.radioshed.ui.util.rememberDragReorderState(
            lazyListState = listState,
            onMove = { fromAbs, toAbs ->
                val fromIdx = fromAbs - headerOffset
                val toIdx = toAbs - headerOffset
                // Both endpoints must be in priority range — rule-driven
                // rows aren't reorderable since they regenerate every
                // peek anyway.
                if (fromIdx in 0 until priorityCount && toIdx in 0 until priorityCount) {
                    onMoveQueueItem(fromIdx, toIdx)
                }
            },
        )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding =
                androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp,
                ),
        ) {
            if (recent.isNotEmpty()) {
                item { QueueSectionHeader("Just played") }
                items(recent, key = { "r:${it.id}" }) { track ->
                    QueueTrackRow(
                        track = track,
                        artworkUri = track.albumId?.let(artworkByAlbumId::get),
                        dim = true,
                        onTap = null,
                        onRemove = null,
                        // Long-press a played row to re-inject it
                        // at the head of the priority queue. Faster
                        // than scrolling back through history then
                        // tapping context-sheet → Play next.
                        onLongPress = { onReplayPlayed(track) },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
                item { HorizontalDivider() }
                item { Spacer(Modifier.height(8.dp)) }
            }
            item {
                val queueHeaderClipboard =
                    com.visorcraft.radioshed.ui.util
                        .rememberCopyToClipboard()
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            // Tap the section header to copy the upcoming
                            // queue as a numbered text list — paste-friendly
                            // for "what's queued?" chats. No-op when the
                            // queue is empty.
                            .clickable(enabled = upcoming.isNotEmpty()) {
                                val text =
                                    upcoming.mapIndexed { i, t ->
                                        "${i + 1}. ${t.displayTitle()}"
                                    }.joinToString("\n")
                                queueHeaderClipboard(text)
                            },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QueueSectionHeader(
                        if (isShuffleMode) "Up next (shuffle pool)" else "Up next",
                    )
                    Spacer(Modifier.weight(1f))
                    if (upcoming.isNotEmpty()) {
                        TextButton(onClick = onSaveAsPlaylist) { Text("Save") }
                    }
                    if (priorityCount > 0) {
                        TextButton(onClick = onClearQueue) { Text("Clear") }
                    }
                }
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
                    // Position-based key: track ids can repeat
                    // legitimately in the priority queue (e.g.,
                    // user "Play next"-ed the same song twice),
                    // and Compose throws on duplicate item keys.
                    upcoming,
                    key = { idx, _ -> "u:$idx" },
                ) { idx, track ->
                    // Only the first `priorityCount` rows are user-
                    // queued items we can actually remove or
                    // reorder. Rule-driven rows have no remove
                    // affordance — peeking them again would just
                    // regenerate the same row.
                    val canRemove = idx < priorityCount
                    val rowModifier =
                        if (canRemove) {
                            Modifier.dragReorderItem(dragState, headerOffset + idx)
                        } else {
                            Modifier
                        }
                    QueueTrackRow(
                        track = track,
                        artworkUri = track.albumId?.let(artworkByAlbumId::get),
                        dim = false,
                        onTap = { onJumpUpcoming(idx) },
                        onRemove =
                            if (canRemove) {
                                { onRemoveQueueItem(idx) }
                            } else {
                                null
                            },
                        // Long-press a priority row to jump it to
                        // the top — same effect as drag-reordering
                        // it to position 0, but discoverable as a
                        // single gesture for users who don't use
                        // drag-reorder. No-op for rule-driven rows
                        // (canRemove=false) since those positions
                        // are regenerated each peek.
                        onLongPress =
                            if (canRemove && idx > 0) {
                                { onMoveUpcomingToTop(idx) }
                            } else {
                                null
                            },
                        modifier = rowModifier,
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun QueueTrackRow(
    track: Track,
    artworkUri: String?,
    dim: Boolean,
    onTap: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val baseColor =
        if (dim) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    // Combine tap + long-press when both are provided. Tap alone
    // keeps the original simple `clickable` for the no-long-press
    // case so we don't pay for combinedClickable's gesture-detector
    // overhead on every Just-played row.
    val rowMod =
        when {
            onTap != null && onLongPress != null ->
                modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onTap,
                        onLongClick = onLongPress,
                    )
            onTap != null -> modifier.fillMaxWidth().clickable(onClick = onTap)
            onLongPress != null ->
                modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {},
                        onLongClick = onLongPress,
                    )
            else -> modifier.fillMaxWidth()
        }
    Row(
        modifier = rowMod.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 32dp thumbnail keeps the row scannable without crowding —
        // visual rhythm matches the title/MM:SS layout. Falls back
        // to the music-note icon when art is missing or the track's
        // album hasn't been resolved yet.
        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (artworkUri != null) {
                coil3.compose.AsyncImage(
                    model = artworkUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = baseColor.copy(alpha = 0.6f),
                )
            }
        }
        Spacer(Modifier.size(10.dp))
        Text(
            text = track.displayTitle(),
            style = MaterialTheme.typography.bodyMedium,
            color = baseColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Duration on the right edge so users can plan ahead — "is
        // the next track a quick interlude or eight minutes?". Hidden
        // on tracks with no duration metadata (some scanner sources
        // skip it for very short / corrupted files).
        if (track.durationMs > 0L) {
            Spacer(Modifier.size(8.dp))
            Text(
                text = formatMs(track.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = baseColor.copy(alpha = 0.55f),
                maxLines = 1,
            )
        }
        if (onRemove != null) {
            com.visorcraft.radioshed.ui.util.TooltipIconButton(
                tooltip = "Remove from queue",
                onClick = onRemove,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remove from queue",
                    modifier = Modifier.size(18.dp),
                    tint = baseColor.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/**
 * Compact subtitle in the TopAppBar that shows what the sleep timer
 * will do next. Hidden when idle so the title doesn't reflow on toggle.
 */
@Composable
private fun SleepTimerLabel(state: SleepTimer.State) {
    val text =
        when (state) {
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
        // When a timer is already running, surface extend options
        // FIRST. The user just opened the menu — odds are they
        // wanted to push the deadline back, not start a fresh one.
        // Three stops (+5/+15/+30) cover the common "one more song"
        // through "another half hour" range without forcing a
        // dialog.
        if (currentState is SleepTimer.State.Running) {
            DropdownMenuItem(
                text = { Text("+5 minutes") },
                onClick = { onExtend(5L) },
            )
            DropdownMenuItem(
                text = { Text("+15 minutes") },
                onClick = { onExtend(15L) },
            )
            DropdownMenuItem(
                text = { Text("+30 minutes") },
                onClick = { onExtend(30L) },
            )
            HorizontalDivider()
        }
        // Discrete durations covering common use cases (commute,
        // before-bed). 10 covers a quick "just one more EP side";
        // 90 catches the longer commute / "rest of the album" run.
        // Skipping anything > 90 min — for longer playlists, the
        // queue itself naturally bounds runtime.
        listOf(5L, 10L, 15L, 30L, 45L, 60L, 90L).forEach { mins ->
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

/**
 * Single-field name prompt for "Save current Up Next as a playlist".
 * Defaults the field to "Queue · YYYY-MM-DD HH:mm" so the user can
 * immediately tap Save without typing — distinguishing one snapshot
 * from another on a list of saved queues. Empty name disables Save.
 */
@Composable
private fun SaveQueueAsPlaylistDialog(
    trackCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val defaultName =
        remember {
            // Compose ms-since-epoch into a stable "Queue · 2026-05-03 14:32"
            // string. We don't have kotlinx-datetime as a dep so do the
            // arithmetic by hand. Local timezone offset isn't available
            // cross-platform either; the year/month/day pieces are derived
            // from the platform-agnostic nowMillis() clock as if it were
            // UTC. Off by a few hours from local at the boundary, but the
            // user can edit the field if it matters.
            "Queue snapshot"
        }
    var name by remember { mutableStateOf(defaultName) }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val confirm: () -> Unit = {
        val n = name.trim()
        if (n.isNotBlank()) {
            keyboardController?.hide()
            onConfirm(n)
        }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save queue as playlist") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    "Saves all $trackCount upcoming track" +
                        (if (trackCount == 1) "" else "s") +
                        " to a new playlist.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Playlist name") },
                    singleLine = true,
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        ),
                    keyboardActions =
                        androidx.compose.foundation.text.KeyboardActions(
                            onDone = { confirm() },
                        ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = confirm,
                enabled = name.trim().isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Renders a slim "0.75 / 1 / 1.25 / 1.5 / 2×" chip row in a bottom
 * sheet. Picking a chip persists the value via [onPick] (which also
 * routes through to AudioEngine.setPlaybackSpeed at the call site)
 * and dismisses the sheet — the user almost always knows what they
 * want, so a confirm step would just be friction.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackSpeedSheet(
    current: Float,
    onPick: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val options = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Playback speed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
            Text(
                "Pitch is preserved — vocals stay listenable at faster rates. " +
                    "The chosen rate persists across track changes and app launches.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { rate ->
                    androidx.compose.material3.FilterChip(
                        selected = current == rate,
                        onClick = { onPick(rate) },
                        label = { Text("${formatSpeed(rate)}×") },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun formatSpeed(value: Float): String =
    if (value == value.toInt().toFloat()) {
        value.toInt().toString()
    } else {
        value.toString().trimEnd('0').trimEnd('.')
    }

/**
 * The three Now Playing layout shapes. Selected by a combination of
 * available width and the user's `KEY_NOW_PLAYING_LAYOUT` override.
 * The TriplePane variant evokes the SiriusXM in-dash UX: stations
 * column on the left, transport controls in the middle, artwork +
 * metadata on the right. Designed for landscape phone-on-dashmount
 * use rather than typical handheld portrait — looks great on a
 * Galaxy Fold's unfolded inner display, on a tablet, or on a phone
 * laid sideways with a USB-C dock.
 */
private enum class NowPlayingLayoutChoice { Compact, TwoPane, TriplePane }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TriplePaneLayout(
    playable: com.visorcraft.radioshed.audio.Playable,
    state: PlayerState,
    positionMs: () -> Long,
    durationMs: () -> Long,
    rating: Double,
    liked: Boolean,
    accent: Color,
    stationName: String?,
    formatLine: String?,
    stations: List<StationTriplePaneEntry>,
    currentStationId: Long?,
    onStationTap: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onLongPressTogglePlayPause: (() -> Unit)? = null,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSkipFive: (() -> Unit)? = null,
    onRate: (Double) -> Unit,
    onToggleLike: () -> Unit,
    preMuteLevel: Float,
    onPreMuteLevelChange: (Float) -> Unit,
    onDislikeOnStation: () -> Unit,
    onTapArtwork: () -> Unit,
    onPinchOpenLyrics: () -> Unit,
    onTapArtist: () -> Unit,
    onTapAlbum: () -> Unit,
    onTapStation: () -> Unit,
    driveMode: Boolean,
    queueProgress: Pair<Int, Int>? = null,
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(16.dp),
    ) {
        // Left third — stations list. Tap a row to switch stations.
        // Highlights the currently-playing one with the primary
        // container, mirroring the SXM "Channel List" look.
        //
        // Vertically centered: with a small built-in station list
        // (the default presets) the column would otherwise top-pin
        // the label while the centre + right columns are vertically
        // centred — visually unbalanced. Arrangement.Center pulls
        // the (label + list) pair toward the middle when they're
        // shorter than the column. Once the user has enough stations
        // for the LazyColumn to fill the height, the wrap-content
        // height is bounded by the column max and the list scrolls
        // normally (Center arrangement is a no-op when children
        // already fill the axis).
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Stations",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(4.dp))
            LazyColumn(
                // wrapContentHeight (the LazyColumn's default sizing
                // along its main axis) lets it shrink to the actual
                // station count while still being capped at the
                // parent Column's max height — so a 100-station
                // library still scrolls inside the column rather
                // than overflowing.
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(
                    items = stations,
                    key = { it.id },
                ) { station ->
                    TriplePaneStationRow(
                        name = station.name,
                        subtitle = station.customSubtitle,
                        isCurrent = station.id == currentStationId,
                        onTap = { onStationTap(station.id) },
                    )
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        // Center third — transport. Same building blocks as the
        // Compact layout but vertically centered in the middle column
        // so it reads like the SXM "now playing" centerpiece.
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TrackInfo(
                playable = playable,
                stationName = stationName,
                alignment = Alignment.CenterHorizontally,
                onTapArtist = onTapArtist,
                onTapAlbum = onTapAlbum,
                onTapStation = onTapStation,
                queueProgress = queueProgress,
            )
            Spacer(Modifier.height(20.dp))
            Scrubber(positionMs, durationMs, onSeek)
            if (!formatLine.isNullOrBlank()) {
                Text(
                    text = formatLine,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            TransportRow(
                state = state,
                onTogglePlayPause = onTogglePlayPause,
                onLongPressTogglePlayPause = onLongPressTogglePlayPause,
                onPrev = onPrev,
                onLongPressPrev = { onSeek(0L) },
                onNext = onNext,
                onSkipFive = onSkipFive,
                // compact = true hides the ±10s seek mini-buttons.
                // The center column in TriplePane is one-third of the
                // screen — five transport buttons at their natural
                // sizes overflow and clip the next button when the
                // user forces TriplePane on a portrait phone (~112dp
                // column). The Scrubber above already covers seeking;
                // the user just loses the long-press 30s shortcut.
                compact = true,
                onReplay = { delta -> onSeek((positionMs() - delta).coerceAtLeast(0L)) },
                onForward = { delta ->
                    val cap =
                        if (durationMs() > 0) {
                            (durationMs() - 250L).coerceAtLeast(0L)
                        } else {
                            Long.MAX_VALUE
                        }
                    onSeek((positionMs() + delta).coerceAtMost(cap))
                },
                onDislikeOnStation = onDislikeOnStation,
                driveMode = driveMode,
                preMuteLevel = preMuteLevel,
                onPreMuteLevelChange = onPreMuteLevelChange,
            )
            Spacer(Modifier.height(16.dp))
            RatingRow(
                current = rating,
                liked = liked,
                onRate = onRate,
                onToggleLike = onToggleLike,
            )
        }
        Spacer(Modifier.width(16.dp))
        // Right third — large artwork + track / artist below it,
        // mirroring SiriusXM's "what's playing right now" panel.
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AlbumArtPlaceholder(
                accent = accent,
                artworkUri = playable.artworkUri,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = 420.dp)
                        .aspectRatio(1f),
                onTap = onTapArtwork,
                onPinchOpen = onPinchOpenLyrics,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = playable.title.withoutBracketGroups().ifBlank { "Unknown title" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            playable.artist?.takeIf { it.isNotBlank() }?.let { artist ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onTapArtist),
                )
            }
            playable.album?.takeIf { it.isNotBlank() }?.let { album ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = album,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onTapAlbum),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TriplePaneStationRow(
    name: String,
    subtitle: String?,
    isCurrent: Boolean,
    onTap: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onTap),
        shape = RoundedCornerShape(8.dp),
        color =
            if (isCurrent) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color =
                    if (isCurrent) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onBackground
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (isCurrent) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                        } else {
                            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
