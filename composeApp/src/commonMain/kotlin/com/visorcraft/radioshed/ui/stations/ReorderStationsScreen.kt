package com.visorcraft.radioshed.ui.stations

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.domain.Station
import com.visorcraft.radioshed.ui.util.dragReorderItem
import com.visorcraft.radioshed.ui.util.rememberDragReorderState
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Dedicated drag-to-reorder surface for the user's station list.
 *
 * The Stations Home grid mixes header + Continue Listening + multiple
 * carousel rows with the station tiles, so drag-on-grid would force
 * either grid-index translation logic with edge cases or a separate
 * reorder mode. We picked the separate mode: the user taps "Reorder
 * stations" from the home page and lands here, sees a stripped-down
 * vertical list that's nothing but stations, drags them around, and
 * taps Done to return to the home grid with the new order persisted.
 *
 * The list is rendered from a working copy of the observed stations
 * — drag swaps mutate the copy locally, then on drop a transactional
 * pass renumbers `sort_position` so the change persists across
 * relaunches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReorderStationsScreen(
    onDone: () -> Unit,
    stationRepo: StationRepository = koinInject(),
) {
    val observed by stationRepo.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    var working by remember { mutableStateOf<List<Station>>(emptyList()) }
    val scope = rememberCoroutineScope()
    // Re-sync the working copy whenever the observed list changes, but
    // only when there's no in-flight drag. Without this guard the
    // observed re-emission after we persist a swap would clobber the
    // user's still-in-progress next drag.
    var pendingDragId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(observed) {
        if (pendingDragId == null) working = observed
    }

    val listState = rememberLazyListState()
    val dragState =
        rememberDragReorderState(
            lazyListState = listState,
            onMove = { from, to ->
                pendingDragId = working.getOrNull(from)?.id
                working =
                    working.toMutableList().also {
                        val item = it.removeAt(from)
                        it.add(to, item)
                    }
            },
            onDrop = { _ ->
                // Persist the new sort_positions in one transaction-ish
                // batch; the repo's reorder helper updates a single row at
                // a time but they're indexed and cheap.
                val orderSnapshot = working
                scope.launch {
                    orderSnapshot.forEachIndexed { idx, s ->
                        if (s.sortPosition != idx) {
                            stationRepo.reorder(s.id, idx)
                        }
                    }
                    pendingDragId = null
                }
            },
        )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reorder stations") },
                navigationIcon = { TextButton(onClick = onDone) { Text("Done") } },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (working.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No stations to reorder yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
            return@Scaffold
        }
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = "Long-press a row and drag to reorder. Changes save automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(working, key = { _, s -> s.id }) { index, station ->
                    ReorderRow(
                        station = station,
                        isDragging = dragState.draggedIndex == index,
                        modifier = Modifier.dragReorderItem(dragState, index),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReorderRow(
    station: Station,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
) {
    val color =
        if (station.color == 0) {
            MaterialTheme.colorScheme.primary
        } else {
            Color(station.color.toLong() and 0xFFFFFFFFL)
        }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isDragging) {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ),
        elevation =
            CardDefaults.cardElevation(
                defaultElevation = if (isDragging) 6.dp else 1.dp,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(color),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = station.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                station.customSubtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
            )
        }
    }
}
