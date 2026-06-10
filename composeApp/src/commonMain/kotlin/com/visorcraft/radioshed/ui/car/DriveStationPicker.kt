package com.visorcraft.radioshed.ui.car

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.domain.Station
import com.visorcraft.radioshed.nav.PlatformBackHandler
import com.visorcraft.radioshed.stations.StationPlayback
import com.visorcraft.radioshed.ui.util.rememberHapticTick
import org.koin.compose.koinInject

/** Cap matching [SettingsStore.setDriveQuickList]'s `take(6)`: a 2×3 grid. */
private const val DRIVE_PICKER_MAX = 6

/**
 * Full-screen big-button station picker for Drive Mode (Task 2.4). Renders the
 * user's Drive Mode quick-list as a 2×3 grid of artwork-dominant tiles (one
 * column under 600dp width). When the quick-list is empty it falls back to the
 * six most-played stations so the picker is never blank on a fresh install.
 *
 * Tiles are sized for at-a-glance hitting while driving: each is at least
 * [TILE_MIN_HEIGHT] tall (well past the 96dp minimum) with the station name in
 * [MaterialTheme.typography.headlineMedium]. Tapping a tile starts that station
 * via the same [StationPlayback.playStation] call Stations Home uses, fires a
 * haptic tick, and dismisses the overlay.
 *
 * Dismissal is via the system back gesture ([PlatformBackHandler]) or the large
 * close button pinned top-right.
 */
@Composable
fun DriveStationPicker(
    onDismiss: () -> Unit,
    settings: SettingsStore = koinInject(),
    stationRepo: StationRepository = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
) {
    PlatformBackHandler(onBack = onDismiss)

    val haptic = rememberHapticTick()

    // Resolve the quick-list ids to Station objects in the stored order; on an
    // empty quick-list fall back to the six most-played stations (same
    // play-count sort the Stations Home "Most played" view uses). Read once on
    // entry — the picker is a transient overlay, so a live flow would be
    // overkill; the surrounding NowPlaying recomposition reopens it fresh.
    var stations by remember { mutableStateOf<List<Station>>(emptyList()) }
    LaunchedEffect(Unit) {
        val all = stationRepo.list()
        val quickIds = settings.getDriveQuickList()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        stations = if (quickIds.isEmpty()) {
            all.sortedByDescending { it.stats.playCount }.take(DRIVE_PICKER_MAX)
        } else {
            val byId = all.associateBy { it.id.toString() }
            // Preserve the user's chosen order; drop ids whose station was
            // deleted so a stale quick-list never renders a blank tile.
            quickIds.mapNotNull { byId[it] }.take(DRIVE_PICKER_MAX)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Same width-class idiom Stations Home uses: single column under
            // phone-portrait width, two columns from there up — which yields
            // the 2×3 grid on a typical car-mounted phone / tablet.
            val isCompact = maxWidth < 600.dp
            val columns = if (isCompact) GridCells.Fixed(1) else GridCells.Fixed(2)

            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Pick a station",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                    // Big top-right close target — generous so it's hittable
                    // at a glance, mirroring the oversized transport buttons.
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(56.dp)
                            .clickable {
                                haptic()
                                onDismiss()
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close",
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (stations.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No stations to show yet.",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = columns,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(stations, key = { it.id }) { station ->
                            DriveStationTile(
                                station = station,
                                onTap = {
                                    haptic()
                                    stationPlayback.playStation(station.id)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Minimum tile height — comfortably past the 96dp at-a-glance hit target. */
private val TILE_MIN_HEIGHT = 160.dp

/**
 * A single big-button station tile. Artwork-dominant: the station's custom
 * artwork fills the tile when present, otherwise a tinted gradient (off the
 * station's accent colour) carries a music-note glyph — the same fallback
 * visual language Stations Home's tiles use, kept minimal here rather than
 * reusing StationCard's full body.
 */
@Composable
private fun DriveStationTile(
    station: Station,
    onTap: () -> Unit,
) {
    val accent = driveStationColor(station)
    Surface(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TILE_MIN_HEIGHT)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onTap),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.5f)
                    .background(driveStationGradient(accent)),
                contentAlignment = Alignment.Center,
            ) {
                if (station.artworkUri != null) {
                    coil3.compose.AsyncImage(
                        model = station.artworkUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = Color.White.copy(alpha = 0.92f),
                    )
                }
            }
            Text(
                text = station.name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            )
        }
    }
}

/** Brand-amber default when the station has no custom colour, mirroring
 *  Stations Home's `stationColor`. */
private fun driveStationColor(station: Station): Color {
    if (station.color == 0) return Color(0xFFFF7F1A)
    return Color(station.color.toLong() and 0xFFFFFFFFL)
}

/** Three-stop diagonal gradient off the accent, matching Stations Home's
 *  `stationGradient` so the fallback tiles read consistently. */
private fun driveStationGradient(accent: Color): Brush {
    val darker = Color(
        red = accent.red * 0.55f,
        green = accent.green * 0.55f,
        blue = accent.blue * 0.65f,
        alpha = 1f,
    )
    val lighter = Color(
        red = (accent.red + 0.1f).coerceAtMost(1f),
        green = (accent.green + 0.1f).coerceAtMost(1f),
        blue = (accent.blue + 0.1f).coerceAtMost(1f),
        alpha = 0.85f,
    )
    return Brush.linearGradient(
        colorStops = arrayOf(
            0f to accent,
            0.55f to darker,
            1f to lighter,
        ),
    )
}
