package com.visorcraft.radioshed.ui.util

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlin.math.abs

/**
 * Lightweight projection of a list/grid item's vertical layout. Lets
 * the drag-reorder logic stay agnostic to whether the host is a
 * LazyColumn or a LazyVerticalGrid — both expose visible-item info
 * but with slightly different shapes.
 */
internal data class ItemSpan(val index: Int, val top: Int, val height: Int) {
    val centerY: Float get() = top + height / 2f
}

/** LazyColumn → ItemSpan list (already linear, top = item.offset). */
private fun LazyListState.itemSpans(): List<ItemSpan> =
    layoutInfo.visibleItemsInfo.map { ItemSpan(it.index, it.offset, it.size) }

/**
 * LazyVerticalGrid → ItemSpan list. We collapse the grid's 2D layout
 * to the dominant Y axis: each row of the grid becomes a single span
 * of the row's tallest cell, but indexes still refer to the linear
 * grid order. For 1-column grids this is identical to a LazyColumn.
 * For N-column grids the dragged tile snaps cell-by-cell along the
 * linear order, which matches what users expect when reordering a
 * grid by dragging.
 */
private fun LazyGridState.itemSpans(): List<ItemSpan> =
    layoutInfo.visibleItemsInfo.map { ItemSpan(it.index, it.offset.y, it.size.height) }

/**
 * Long-press drag-to-reorder helper for [androidx.compose.foundation.lazy.LazyColumn].
 *
 * Usage:
 *  ```
 *  val state = rememberDragReorderState(
 *      lazyListState = lazyListState,
 *      onMove = { from, to -> mutableList = mutableList.move(from, to) },
 *  )
 *  LazyColumn(state = lazyListState) {
 *      itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
 *          Row(modifier = Modifier.dragReorderItem(state, index)) { ... }
 *      }
 *  }
 *  ```
 *
 * The helper handles:
 *  - long-press to grab
 *  - vertical drag with live `onMove` callbacks (caller mutates list state)
 *  - swap detection by walking [LazyListState.layoutInfo]'s visibleItemsInfo
 *  - drop callback so the caller can persist the new order
 *
 * Limitations:
 *  - vertical-only (LazyColumn). LazyVerticalGrid is not supported here.
 *  - lists with mixed item kinds (headers, separators) need to expose
 *    drag only on the items the user can actually reorder; the helper
 *    doesn't know about non-draggable rows.
 *  - the swap fires when the dragged item's center passes a neighbour's
 *    center. For very tall items this can feel slow; for very short
 *    items (< 24dp) the threshold may overshoot. Both edge cases are
 *    rare in practice.
 *
 * The dragged item is rendered via `Modifier.graphicsLayer { translationY = ... }`
 * with a tiny scale + zIndex so it visually floats above its neighbours.
 */
class DragReorderState internal constructor(
    private val itemSpansProvider: () -> List<ItemSpan>,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onDrop: ((finalIndex: Int) -> Unit)?,
) {
    var draggedIndex by mutableStateOf<Int?>(null)
        private set

    /** Visual translation of the dragged row; reset to 0 after each
     *  successful neighbour swap so the row appears continuously. */
    var draggedDistance by mutableFloatStateOf(0f)
        private set

    fun onDragStart(index: Int) {
        draggedIndex = index
        draggedDistance = 0f
    }

    fun onDrag(delta: Float) {
        val current = draggedIndex ?: return
        draggedDistance += delta
        val visible = itemSpansProvider()
        val draggedItem = visible.firstOrNull { it.index == current } ?: return
        val draggedCenter = draggedItem.centerY + draggedDistance
        // Pick the visible item whose center the dragged center has
        // crossed in the direction of the drag. Going down: candidate
        // index > current; going up: candidate index < current. Picking
        // the FARTHEST swappable target each frame avoids jitter when
        // the user drags fast over multiple items at once.
        val target =
            if (draggedDistance > 0f) {
                visible
                    .filter { it.index > current }
                    .lastOrNull { draggedCenter > it.centerY }
            } else {
                visible
                    .filter { it.index < current }
                    .firstOrNull { draggedCenter < it.centerY }
            }
        if (target != null && target.index != current) {
            onMove(current, target.index)
            // Compensate so the row stays visually stable across the
            // swap — the underlying offset jumped by (target.top -
            // draggedItem.top), so subtract that from the visual
            // distance to make the row appear to keep moving in the
            // same direction without snapping.
            draggedDistance -= (target.top - draggedItem.top).toFloat()
            draggedIndex = target.index
        }
    }

    fun onDragEnd() {
        val final = draggedIndex
        draggedIndex = null
        draggedDistance = 0f
        if (final != null) onDrop?.invoke(final)
    }

    fun onDragCancel() {
        draggedIndex = null
        draggedDistance = 0f
    }
}

@Composable
fun rememberDragReorderState(
    lazyListState: LazyListState,
    onMove: (from: Int, to: Int) -> Unit,
    onDrop: ((finalIndex: Int) -> Unit)? = null,
): DragReorderState =
    remember(lazyListState) {
        DragReorderState({ lazyListState.itemSpans() }, onMove, onDrop)
    }

/**
 * LazyVerticalGrid variant. Reorder still operates on the linear
 * index space (which is what `itemsIndexed` produces); the user
 * drags vertically and items shift along the linear order.
 */
@Composable
fun rememberDragReorderState(
    lazyGridState: LazyGridState,
    onMove: (from: Int, to: Int) -> Unit,
    onDrop: ((finalIndex: Int) -> Unit)? = null,
): DragReorderState =
    remember(lazyGridState) {
        DragReorderState({ lazyGridState.itemSpans() }, onMove, onDrop)
    }

/**
 * Per-item modifier. Attach this to each row that should be draggable.
 * The dragged row gets visual elevation (zIndex + tiny scale) and
 * follows the user's finger; non-dragged rows animate to their new
 * positions via the LazyColumn's standard item placement animations.
 *
 * `pointerInput(index)` keys the gesture detector by index so a
 * recomposition with a different index re-installs the detector.
 */
fun Modifier.dragReorderItem(
    state: DragReorderState,
    index: Int,
): Modifier =
    this
        .zIndex(if (state.draggedIndex == index) 1f else 0f)
        .graphicsLayer {
            if (state.draggedIndex == index) {
                translationY = state.draggedDistance
                scaleX = 1.02f
                scaleY = 1.02f
                alpha = 0.95f
            }
        }
        .pointerInput(index) {
            detectDragGesturesAfterLongPress(
                onDragStart = { state.onDragStart(index) },
                onDragEnd = { state.onDragEnd() },
                onDragCancel = { state.onDragCancel() },
                onDrag = { _, dragAmount ->
                    if (abs(dragAmount.y) >= 0.5f) state.onDrag(dragAmount.y)
                },
            )
        }
