package com.visorcraft.radioshed.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

internal enum class NowPlayingControl {
    Close,
    Scrubber,
    Previous,
    Replay,
    PlayPause,
    Forward,
    Next,
    Like,
    Star1,
    Star2,
    Star3,
    Star4,
    Star5,
    Volume,
    Visualizer,
    Lyrics,
    Shuffle,
    Repeat,
    Queue,
    Sleep,
    More,
}

internal data class DpadNeighbors(
    val left: NowPlayingControl? = null,
    val right: NowPlayingControl? = null,
    val up: NowPlayingControl? = null,
    val down: NowPlayingControl? = null,
)

internal class NowPlayingFocusRegistry {
    private val requesters = mutableMapOf<NowPlayingControl, FocusRequester>()
    private val attachedControls = mutableStateOf(setOf<NowPlayingControl>())
    var hasFocusedControl = false
        private set

    operator fun get(control: NowPlayingControl): FocusRequester = requesters.getOrPut(control) { FocusRequester() }

    fun requesterOrDefault(control: NowPlayingControl): FocusRequester =
        if (attachedControls.value.contains(control)) get(control) else FocusRequester.Default

    fun attach(control: NowPlayingControl) {
        attachedControls.value += control
    }

    fun detach(control: NowPlayingControl) {
        attachedControls.value -= control
    }

    fun markFocused(control: NowPlayingControl) {
        if (control != NowPlayingControl.Close) hasFocusedControl = true
    }
}

internal val LocalNowPlayingFocus = staticCompositionLocalOf<NowPlayingFocusRegistry?> { null }

@Composable
internal fun Modifier.nowPlayingFocusable(
    control: NowPlayingControl,
    compact: Boolean = false,
    shape: Shape = CircleShape,
    onActivate: (() -> Unit)? = null,
    onHorizontalStep: ((Int) -> Unit)? = null,
): Modifier {
    val registry = requireNotNull(LocalNowPlayingFocus.current)
    DisposableEffect(control) {
        registry.attach(control)
        onDispose { registry.detach(control) }
    }
    val focusRequester = registry[control]
    val neighbors = nowPlayingNeighbors(control, compact)
    val leftRequester = neighbors.left?.let { registry.requesterOrDefault(it) }
    val rightRequester = neighbors.right?.let { registry.requesterOrDefault(it) }
    val upRequester = neighbors.up?.let { registry.requesterOrDefault(it) }
    val downRequester = neighbors.down?.let { registry.requesterOrDefault(it) }
    var focused by remember { mutableStateOf(false) }
    val focusColor = MaterialTheme.colorScheme.primary
    return this
        .focusRequester(focusRequester)
        .focusProperties {
            leftRequester?.let { left = it }
            rightRequester?.let { right = it }
            upRequester?.let { up = it }
            downRequester?.let { down = it }
        }.onFocusChanged {
            focused = it.hasFocus
            if (it.hasFocus) registry.markFocused(control)
        }.background(if (focused) focusColor.copy(alpha = 0.24f) else Color.Transparent, shape)
        .border(width = 3.dp, color = if (focused) Color.White else Color.Transparent, shape = shape)
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown &&
                onHorizontalStep != null &&
                (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)
            ) {
                onHorizontalStep(if (event.key == Key.DirectionLeft) -1 else 1)
                true
            } else if (onActivate != null &&
                event.type == KeyEventType.KeyDown &&
                (event.key == Key.DirectionCenter || event.key == Key.Enter)
            ) {
                onActivate()
                true
            } else {
                false
            }
        }.focusable()
}

internal fun nowPlayingNeighbors(
    control: NowPlayingControl,
    compact: Boolean,
): DpadNeighbors =
    when (control) {
        NowPlayingControl.Close -> DpadNeighbors(down = NowPlayingControl.PlayPause)
        NowPlayingControl.Scrubber ->
            DpadNeighbors(up = NowPlayingControl.Close, down = NowPlayingControl.PlayPause)
        NowPlayingControl.Previous ->
            DpadNeighbors(
                right = if (compact) NowPlayingControl.PlayPause else NowPlayingControl.Replay,
                up = NowPlayingControl.Close,
                down = NowPlayingControl.Like,
            )
        NowPlayingControl.Replay ->
            DpadNeighbors(
                left = NowPlayingControl.Previous,
                right = NowPlayingControl.PlayPause,
                up = NowPlayingControl.Close,
                down = NowPlayingControl.Like,
            )
        NowPlayingControl.PlayPause ->
            DpadNeighbors(
                left = if (compact) NowPlayingControl.Previous else NowPlayingControl.Replay,
                right = if (compact) NowPlayingControl.Next else NowPlayingControl.Forward,
                up = NowPlayingControl.Scrubber,
                down = NowPlayingControl.Like,
            )
        NowPlayingControl.Forward ->
            DpadNeighbors(
                left = NowPlayingControl.PlayPause,
                right = NowPlayingControl.Next,
                up = NowPlayingControl.Close,
                down = NowPlayingControl.Like,
            )
        NowPlayingControl.Next ->
            DpadNeighbors(
                left = if (compact) NowPlayingControl.PlayPause else NowPlayingControl.Forward,
                up = NowPlayingControl.Close,
                down = NowPlayingControl.More,
            )
        NowPlayingControl.Like ->
            DpadNeighbors(
                right = NowPlayingControl.Star1,
                up = NowPlayingControl.Previous,
                down = NowPlayingControl.Volume,
            )
        NowPlayingControl.Star1 ->
            DpadNeighbors(
                left = NowPlayingControl.Like,
                right = NowPlayingControl.Star2,
                up = if (compact) NowPlayingControl.Previous else NowPlayingControl.Replay,
                down = if (compact) NowPlayingControl.Lyrics else NowPlayingControl.Visualizer,
            )
        NowPlayingControl.Star2 ->
            DpadNeighbors(
                left = NowPlayingControl.Star1,
                right = NowPlayingControl.Star3,
                up = NowPlayingControl.PlayPause,
                down = NowPlayingControl.Lyrics,
            )
        NowPlayingControl.Star3 ->
            DpadNeighbors(
                left = NowPlayingControl.Star2,
                right = NowPlayingControl.Star4,
                up = NowPlayingControl.PlayPause,
                down = NowPlayingControl.Shuffle,
            )
        NowPlayingControl.Star4 ->
            DpadNeighbors(
                left = NowPlayingControl.Star3,
                right = NowPlayingControl.Star5,
                up = if (compact) NowPlayingControl.PlayPause else NowPlayingControl.Forward,
                down = NowPlayingControl.Repeat,
            )
        NowPlayingControl.Star5 ->
            DpadNeighbors(
                left = NowPlayingControl.Star4,
                up = NowPlayingControl.Next,
                down = NowPlayingControl.Queue,
            )
        NowPlayingControl.Volume ->
            DpadNeighbors(
                right = if (compact) NowPlayingControl.Lyrics else NowPlayingControl.Visualizer,
                up = NowPlayingControl.Like,
            )
        NowPlayingControl.Visualizer ->
            DpadNeighbors(
                left = NowPlayingControl.Volume,
                right = NowPlayingControl.Lyrics,
                up = NowPlayingControl.Star1,
            )
        NowPlayingControl.Lyrics ->
            DpadNeighbors(
                left = if (compact) NowPlayingControl.Volume else NowPlayingControl.Visualizer,
                right = NowPlayingControl.Shuffle,
                up = NowPlayingControl.Star2,
            )
        NowPlayingControl.Shuffle ->
            DpadNeighbors(
                left = NowPlayingControl.Lyrics,
                right = NowPlayingControl.Repeat,
                up = NowPlayingControl.Star3,
            )
        NowPlayingControl.Repeat ->
            DpadNeighbors(
                left = NowPlayingControl.Shuffle,
                right = NowPlayingControl.Queue,
                up = NowPlayingControl.Star4,
            )
        NowPlayingControl.Queue ->
            DpadNeighbors(
                left = NowPlayingControl.Repeat,
                right = NowPlayingControl.Sleep,
                up = NowPlayingControl.Star5,
            )
        NowPlayingControl.Sleep ->
            DpadNeighbors(
                left = NowPlayingControl.Queue,
                right = NowPlayingControl.More,
                up = NowPlayingControl.Next,
            )
        NowPlayingControl.More ->
            DpadNeighbors(left = NowPlayingControl.Sleep, up = NowPlayingControl.Next)
    }
