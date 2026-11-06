package com.visorcraft.radioshed.ui.player.visualizer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.PlayerState
import com.visorcraft.radioshed.audio.visualizer.AmbientSignalGenerator
import com.visorcraft.radioshed.audio.visualizer.ImpactRingPool
import com.visorcraft.radioshed.audio.visualizer.MusicStructure
import com.visorcraft.radioshed.audio.visualizer.MusicStructureAnalyzer
import com.visorcraft.radioshed.audio.visualizer.SpectralHistory
import com.visorcraft.radioshed.audio.visualizer.VisualizerAudioSource
import com.visorcraft.radioshed.audio.visualizer.VisualizerAudioStatus
import com.visorcraft.radioshed.audio.visualizer.VisualizerFrame
import com.visorcraft.radioshed.audio.visualizer.VisualizerMode
import com.visorcraft.radioshed.audio.visualizer.VisualizerPalette
import com.visorcraft.radioshed.audio.visualizer.VisualizerSignalProcessor
import com.visorcraft.radioshed.audio.visualizer.VuNeedle
import com.visorcraft.radioshed.audio.visualizer.WaveformHistory
import com.visorcraft.radioshed.audio.visualizer.advanceVisualizerTime
import com.visorcraft.radioshed.audio.visualizer.sceneIntroProgress
import com.visorcraft.radioshed.audio.visualizer.vinylRotationDegrees
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.nav.PlatformBackHandler
import com.visorcraft.radioshed.permissions.rememberAudioCaptureGate
import com.visorcraft.radioshed.stations.StationPlayback
import com.visorcraft.radioshed.ui.util.ImmersiveMode
import com.visorcraft.radioshed.ui.util.TooltipIconButton
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import kotlin.math.abs

/** Immersive, full-bleed audio light show opened from Now Playing. */
@Composable
fun VisualizerScreen(
    onClose: () -> Unit,
    onOpenLyrics: () -> Unit = {},
    audioEngine: AudioEngine = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    audioSource: VisualizerAudioSource = koinInject(),
    settings: SettingsStore = koinInject(),
) {
    val nowPlaying by audioEngine.nowPlaying.collectAsStateWithLifecycle()
    val playerState by audioEngine.state.collectAsStateWithLifecycle()
    val position by audioEngine.position.collectAsStateWithLifecycle()
    val volume by audioEngine.volume.collectAsStateWithLifecycle()
    val audioStatus by audioSource.status.collectAsStateWithLifecycle()
    val permissionGate = rememberAudioCaptureGate()
    val reduceMotion = remember(settings) { settings.getReduceMotion() }
    var mode by remember { mutableStateOf(VisualizerMode.fromStorage(settings.getVisualizerMode())) }
    var palette by remember { mutableStateOf(VisualizerPalette.fromStorage(settings.getVisualizerPalette())) }
    var intensity by remember { mutableStateOf(settings.getVisualizerIntensity()) }
    var speed by remember { mutableStateOf(settings.getVisualizerSpeed()) }
    var autoCycle by remember { mutableStateOf(settings.getVisualizerAutoCycle()) }
    var controlsVisible by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var sceneStartedAt by remember { mutableLongStateOf(0L) }
    val frameState = remember { mutableStateOf(VisualizerFrame.empty()) }
    val timeState = remember { mutableLongStateOf(0L) }
    val structureState = remember { mutableStateOf(MusicStructure.EMPTY) }
    val vuLevels = remember { mutableStateOf(floatArrayOf(0f, 0f)) }
    val processor = remember { VisualizerSignalProcessor() }
    val analyzer = remember { MusicStructureAnalyzer() }
    val ambient = remember { AmbientSignalGenerator() }
    val history = remember { SpectralHistory() }
    val waveformHistory = remember { WaveformHistory() }
    val impactRings = remember { ImpactRingPool() }
    val bassNeedle = remember { VuNeedle() }
    val trebleNeedle = remember { VuNeedle() }
    val focusRequester = remember { FocusRequester() }
    val theme = remember(palette) { visualizerTheme(palette) }
    val playing = playerState == PlayerState.Playing

    fun revealControls() {
        controlsVisible = true
        interaction++
    }

    fun selectMode(
        value: VisualizerMode,
        reveal: Boolean = true,
    ) {
        if (value != mode) {
            mode = value
            sceneStartedAt = timeState.value
            settings.setVisualizerMode(value.name)
        }
        if (reveal) revealControls()
    }

    PlatformBackHandler(onBack = onClose)
    ImmersiveMode(active = true)

    DisposableEffect(audioSource) {
        audioSource.start()
        onDispose { audioSource.stop() }
    }
    LaunchedEffect(permissionGate.granted) { audioSource.refreshPermission() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) focusRequester.requestFocus()
    }
    LaunchedEffect(nowPlaying?.trackId) { revealControls() }
    LaunchedEffect(controlsVisible, interaction, menuOpen) {
        if (controlsVisible && !menuOpen) {
            delay(4_500L)
            controlsVisible = false
        }
    }
    LaunchedEffect(autoCycle, mode, reduceMotion) {
        if (autoCycle && !reduceMotion) {
            delay(20_000L)
            selectMode(mode.next(), reveal = false)
        }
    }
    LaunchedEffect(playing, nowPlaying?.trackId, speed, reduceMotion) {
        if (!playing) return@LaunchedEffect
        var elapsed = timeState.value.toDouble()
        var lastFrame = 0L
        var lastRender = 0L
        while (true) {
            withFrameMillis { now ->
                if (lastFrame == 0L) lastFrame = now
                val delta = (now - lastFrame).coerceIn(0L, 80L)
                lastFrame = now
                elapsed = advanceVisualizerTime(elapsed, delta, speed, reduceMotion)
                val frameInterval = if (reduceMotion) 100L else 33L
                if (now - lastRender >= frameInterval) {
                    val renderTime = elapsed.toLong()
                    val capture = audioSource.capture.value
                    val nextFrame =
                        if (capture != null) {
                            processor.next(capture.spectrum, capture.waveform, live = true)
                        } else {
                            val generated = ambient.frame(renderTime, nowPlaying?.trackId ?: 0L)
                            processor.next(generated.spectrum, generated.waveform, live = false)
                        }
                    val structure = analyzer.next(nextFrame, (now - lastRender).coerceIn(0L, 500L))
                    history.add(nextFrame.spectrum)
                    waveformHistory.add(nextFrame.waveform)
                    if (!reduceMotion) {
                        impactRings.update(renderTime, nextFrame.beat)
                        if (structure.dropped) impactRings.burst(renderTime, structure.impact)
                    }
                    structureState.value = structure
                    vuLevels.value = floatArrayOf(bassNeedle.next(nextFrame.bass), trebleNeedle.next(nextFrame.treble))
                    frameState.value = nextFrame
                    timeState.value = renderTime
                    lastRender = now
                }
            }
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.Escape -> {
                            onClose()
                            true
                        }
                        Key.Spacebar, Key.Enter, Key.DirectionCenter -> {
                            audioEngine.togglePlayPause()
                            revealControls()
                            true
                        }
                        Key.DirectionLeft -> {
                            selectMode(mode.previous())
                            true
                        }
                        Key.DirectionRight, Key.V -> {
                            selectMode(mode.next())
                            true
                        }
                        Key.P -> {
                            palette = palette.next()
                            settings.setVisualizerPalette(palette.name)
                            revealControls()
                            true
                        }
                        Key.A -> {
                            autoCycle = !autoCycle
                            settings.setVisualizerAutoCycle(autoCycle)
                            revealControls()
                            true
                        }
                        Key.H -> {
                            if (controlsVisible) controlsVisible = false else revealControls()
                            true
                        }
                        Key.DirectionUp, Key.DirectionDown -> {
                            if (!controlsVisible) {
                                val adjustment = if (event.key == Key.DirectionUp) 0.05f else -0.05f
                                audioEngine.setVolume((volume + adjustment).coerceIn(0f, 1f))
                                revealControls()
                                true
                            } else {
                                revealControls()
                                false
                            }
                        }
                        else -> false
                    }
                }.pointerInput(controlsVisible, mode) {
                    var distance = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { distance = 0f },
                        onDragEnd = {
                            if (!controlsVisible && abs(distance) > 80f) {
                                selectMode(if (distance < 0f) mode.next() else mode.previous())
                            }
                        },
                        onHorizontalDrag = { _, amount -> distance += amount },
                    )
                }.pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            audioEngine.togglePlayPause()
                            revealControls()
                        },
                        onTap = {
                            if (controlsVisible) controlsVisible = false else revealControls()
                        },
                    )
                },
    ) {
        VisualizerCanvas(
            frameState = frameState,
            timeState = timeState,
            structureState = structureState,
            vuLevels = vuLevels,
            history = history,
            waveformHistory = waveformHistory,
            impactRings = impactRings,
            mode = mode,
            palette = palette,
            intensity = intensity,
            playing = playing,
            reduceMotion = reduceMotion,
            sceneStartedAt = sceneStartedAt,
            progress = position.fraction,
            modifier =
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "${mode.label} audio visualizer" },
        )
        if (mode == VisualizerMode.Bloom || mode == VisualizerMode.Ripples || mode == VisualizerMode.Vinyl) {
            ArtworkEmblem(
                artworkUri = nowPlaying?.artworkUri,
                mode = mode,
                accent = theme.accent,
                timeState = timeState,
                sceneStartedAt = sceneStartedAt,
                reduceMotion = reduceMotion,
                playing = playing,
            )
        }

        AnimatedVisibility(
            visible = !controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(24.dp),
            ) {
                Box(Modifier.size(6.dp).background(theme.accent.copy(alpha = 0.65f), CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = mode.label.uppercase(),
                    color = Color.White.copy(alpha = 0.38f),
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 2.sp,
                )
            }
        }

        if (!playing) {
            Surface(
                color = Color(0xD914141D),
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(
                    text = if (nowPlaying == null) "Play a track to start the show" else "Paused",
                    color = Color.White.copy(alpha = 0.88f),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = theme.accent.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, theme.accent.copy(alpha = 0.24f)),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Audiotrack,
                        contentDescription = null,
                        tint = theme.accent,
                        modifier = Modifier.padding(12.dp).size(26.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = nowPlaying?.title ?: "RadioShed Visualizer",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = nowPlaying?.artist?.takeIf { it.isNotBlank() } ?: "IMMERSIVE AUDIO LIGHT SHOW",
                        color = Color.White.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${(mode.ordinal + 1).toString().padStart(
                            2,
                            '0',
                        )} / ${VisualizerMode.entries.size}  ·  ${mode.label}",
                        color = theme.accent.copy(alpha = 0.76f),
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.2.sp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                SourceBadge(
                    audioStatus = audioStatus,
                    live = audioStatus == VisualizerAudioStatus.Live && playing,
                    accent = theme.accent,
                    onGrant = permissionGate::request,
                )
                TooltipIconButton(tooltip = "Lyrics", onClick = onOpenLyrics) {
                    Icon(Icons.Filled.Lyrics, contentDescription = "Lyrics", tint = Color.White)
                }
                TooltipIconButton(tooltip = "Close visualizer", onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close visualizer", tint = Color.White)
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                color = Color(0xDB101018),
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TooltipIconButton(
                            tooltip = "Previous track",
                            onClick = {
                                stationPlayback.skipPrevious()
                                revealControls()
                            },
                        ) {
                            Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous track", tint = Color.White)
                        }
                        TooltipIconButton(
                            tooltip = if (playing) "Pause" else "Play",
                            onClick = {
                                audioEngine.togglePlayPause()
                                revealControls()
                            },
                            modifier = Modifier.size(48.dp).background(theme.accent, CircleShape),
                        ) {
                            Icon(
                                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play",
                                tint = theme.backgroundBottom,
                            )
                        }
                        TooltipIconButton(
                            tooltip = "Next track",
                            onClick = {
                                stationPlayback.skipNext()
                                revealControls()
                            },
                        ) {
                            Icon(Icons.Filled.SkipNext, contentDescription = "Next track", tint = Color.White)
                        }
                        Spacer(Modifier.width(12.dp))
                        TooltipIconButton(tooltip = "Previous mode", onClick = { selectMode(mode.previous()) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "Previous mode",
                                tint = Color.White,
                            )
                        }
                        ModePicker(
                            selected = mode,
                            accent = theme.accent,
                            onSelect = ::selectMode,
                            onMenuChange = { open ->
                                menuOpen = open
                                revealControls()
                            },
                        )
                        TooltipIconButton(tooltip = "Next mode", onClick = { selectMode(mode.next()) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Next mode",
                                tint = Color.White,
                            )
                        }
                        PalettePicker(
                            selected = palette,
                            accent = theme.accent,
                            onSelect = {
                                palette = it
                                settings.setVisualizerPalette(it.name)
                                revealControls()
                            },
                            onMenuChange = { open ->
                                menuOpen = open
                                revealControls()
                            },
                        )
                        TextButton(
                            onClick = {
                                autoCycle = !autoCycle
                                settings.setVisualizerAutoCycle(autoCycle)
                                revealControls()
                            },
                        ) {
                            Icon(
                                Icons.Filled.Repeat,
                                contentDescription = null,
                                tint = if (autoCycle) theme.accent else Color.White.copy(alpha = 0.72f),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Auto",
                                color = if (autoCycle) theme.accent else Color.White.copy(alpha = 0.72f),
                            )
                        }
                        Text(
                            text = "V mode  ·  P palette  ·  H hide",
                            color = Color.White.copy(alpha = 0.38f),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 10.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Speed,
                            contentDescription = null,
                            tint = theme.accent.copy(alpha = 0.80f),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Speed ${formatMultiplier(speed)}", color = Color.White.copy(alpha = 0.72f))
                        VisualizerSlider(
                            value = speed,
                            onValueChange = {
                                speed = it
                                revealControls()
                            },
                            onValueChangeFinished = { settings.setVisualizerSpeed(speed) },
                            accent = theme.accent,
                        )
                        Spacer(Modifier.width(24.dp))
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = null,
                            tint = theme.accent.copy(alpha = 0.80f),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Intensity ${formatMultiplier(intensity)}", color = Color.White.copy(alpha = 0.72f))
                        VisualizerSlider(
                            value = intensity,
                            onValueChange = {
                                intensity = it
                                revealControls()
                            },
                            onValueChangeFinished = { settings.setVisualizerIntensity(intensity) },
                            accent = theme.accent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ArtworkEmblem(
    artworkUri: String?,
    mode: VisualizerMode,
    accent: Color,
    timeState: State<Long>,
    sceneStartedAt: Long,
    reduceMotion: Boolean,
    playing: Boolean,
) {
    if (artworkUri == null) return
    val context = LocalPlatformContext.current
    val request =
        remember(artworkUri) {
            ImageRequest
                .Builder(context)
                .data(artworkUri)
                .size(512)
                .build()
        }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val fraction =
            when (mode) {
                VisualizerMode.Bloom -> 0.17f
                VisualizerMode.Ripples -> 0.105f
                VisualizerMode.Vinyl -> 0.245f
                else -> 0f
            }
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .size(minOf(maxWidth, maxHeight) * fraction)
                    .clip(CircleShape)
                    .graphicsLayer {
                        alpha =
                            sceneIntroProgress(timeState.value - sceneStartedAt, reduceMotion, playing) *
                            if (playing) 1f else 0.38f
                        rotationZ =
                            if (mode ==
                                VisualizerMode.Vinyl
                            ) {
                                vinylRotationDegrees(timeState.value, reduceMotion)
                            } else {
                                0f
                            }
                    }.border(2.dp, accent.copy(alpha = 0.68f), CircleShape),
        )
    }
}

@Composable
private fun VisualizerSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    accent: Color,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = 0.25f..2f,
        colors =
            SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.14f),
            ),
        modifier = Modifier.width(180.dp).padding(horizontal = 12.dp),
    )
}

@Composable
private fun SourceBadge(
    audioStatus: VisualizerAudioStatus,
    live: Boolean,
    accent: Color,
    onGrant: () -> Unit,
) {
    val text =
        when {
            live -> "Live audio"
            audioStatus == VisualizerAudioStatus.PermissionRequired -> "Ambient · enable live audio"
            else -> "Ambient · generated visuals"
        }
    Surface(
        color = if (live) accent.copy(alpha = 0.13f) else Color(0x6624242C),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, if (live) accent.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.08f)),
        modifier =
            if (audioStatus == VisualizerAudioStatus.PermissionRequired) {
                Modifier.clickable(onClick = onGrant)
            } else {
                Modifier
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
        ) {
            Box(Modifier.size(6.dp).background(if (live) accent else Color.White.copy(alpha = 0.45f), CircleShape))
            Spacer(Modifier.width(7.dp))
            Text(
                text = text,
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun ModePicker(
    selected: VisualizerMode,
    accent: Color,
    onSelect: (VisualizerMode) -> Unit,
    onMenuChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = {
                expanded = true
                onMenuChange(true)
            },
        ) {
            Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = accent)
            Spacer(Modifier.width(8.dp))
            Text(selected.label, color = Color.White)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                onMenuChange(false)
            },
        ) {
            VisualizerMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text("${(mode.ordinal + 1).toString().padStart(2, '0')}   ${mode.label}") },
                    leadingIcon = {
                        if (mode == selected) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = accent)
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                    onClick = {
                        expanded = false
                        onMenuChange(false)
                        onSelect(mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun PalettePicker(
    selected: VisualizerPalette,
    accent: Color,
    onSelect: (VisualizerPalette) -> Unit,
    onMenuChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = {
                expanded = true
                onMenuChange(true)
            },
        ) {
            Icon(Icons.Filled.Palette, contentDescription = null, tint = accent)
            Spacer(Modifier.width(6.dp))
            Text(selected.label, color = Color.White)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                onMenuChange(false)
            },
        ) {
            VisualizerPalette.entries.forEach { palette ->
                DropdownMenuItem(
                    text = { Text(palette.label) },
                    leadingIcon = {
                        Box(Modifier.size(14.dp).background(visualizerTheme(palette).accent, CircleShape))
                    },
                    onClick = {
                        expanded = false
                        onMenuChange(false)
                        onSelect(palette)
                    },
                )
            }
        }
    }
}

private fun VisualizerPalette.next(): VisualizerPalette =
    VisualizerPalette.entries[(ordinal + 1) % VisualizerPalette.entries.size]

private fun formatMultiplier(value: Float): String = "${(value * 100).toInt()}%"
