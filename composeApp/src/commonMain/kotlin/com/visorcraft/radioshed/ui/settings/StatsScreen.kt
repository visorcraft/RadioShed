package com.visorcraft.radioshed.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.ContentScale
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.data.TopAlbumEntry
import com.visorcraft.radioshed.data.TopArtistEntry
import com.visorcraft.radioshed.domain.Station
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.stations.StationPlayback
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import org.koin.compose.koinInject

/**
 * Listening stats — total time, playback count, top tracks. Pulled
 * straight from `station_history` rows recorded by [StationPlayback]
 * on each track transition / stop.
 *
 * Tracks-as-stat is honest: we count rows in station_history, which
 * means a track played for 5 seconds and skipped counts identically to
 * one played to completion. A future pass could surface "completed
 * plays only" by filtering rows with `completed = 1`. For v1 the
 * top-N is more useful as raw popularity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    onArtistTap: (Long) -> Unit = {},
    onAlbumTap: (Long) -> Unit = {},
    stationRepo: StationRepository = koinInject(),
    libraryRepo: LibraryRepository = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    ratingRepo: com.visorcraft.radioshed.data.RatingRepository = koinInject(),
) {
    var totalMs by remember { mutableStateOf(0L) }
    var totalPlays by remember { mutableStateOf(0L) }
    var topTracks by remember {
        mutableStateOf<List<Pair<Track, Long>>>(emptyList())
    }
    var topArtists by remember { mutableStateOf<List<TopArtistEntry>>(emptyList()) }
    var topAlbums by remember { mutableStateOf<List<TopAlbumEntry>>(emptyList()) }
    var topGenres by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    var topStations by remember { mutableStateOf<List<Station>>(emptyList()) }
    // Tracks the user skips most often. Surfaced to invite a "you
    // don't seem to like this" decision — long-press to dislike on
    // station, etc. Capped at 10 because a long Most-skipped list
    // becomes psychological clutter.
    var mostSkipped by remember {
        mutableStateOf<List<Pair<Track, Long>>>(emptyList())
    }
    // 30-day daily play activity for the listening-timeline bar
    // chart. Populated from the same station_history table that
    // backs the rest of the stats.
    var dailyActivity by remember {
        mutableStateOf<List<com.visorcraft.radioshed.data.DailyActivity>>(emptyList())
    }
    var hourActivity by remember { mutableStateOf<LongArray>(LongArray(24)) }
    var weekdayActivity by remember { mutableStateOf<LongArray>(LongArray(7)) }
    var completionStats by remember { mutableStateOf<Pair<Long, Long>>(0L to 0L) }
    var streaks by remember { mutableStateOf<Pair<Int, Int>>(0 to 0) }
    // Time-window selector. Persisted via SettingsStore so the
    // user's choice (7 / 30 / 90 days) survives a screen exit.
    // The streak window stays at 90 regardless — that stat reads
    // "longest streak in the past 90 days" in copy already, and a
    // user picking 7 days still wants to see their longer-term
    // streak record.
    val settings: com.visorcraft.radioshed.data.SettingsStore = koinInject()
    var windowDays by remember { mutableStateOf(settings.getStatsWindowDays()) }
    LaunchedEffect(windowDays) { settings.setStatsWindowDays(windowDays) }
    val scope = rememberCoroutineScope()

    // Week-over-week delta for the Total listened tile. Computed in
    // the same effect that hydrates the rest of the screen so the
    // arrow chip never shows a stale figure relative to the tile.
    var weekDeltaPct by remember { mutableStateOf<Int?>(null) }
    // Earliest played_at across all of station_history. Powers a
    // "Listening since…" tile so the user reads at a glance how
    // much listening lifetime is captured. Independent of windowDays
    // — this is a lifetime stat, not a window stat.
    var listeningSinceMs by remember { mutableStateOf<Long?>(null) }
    // Distinct-reach counters. Lifetime stats — independent of
    // windowDays. Surfaced as a "you've heard X artists across Y
    // albums" tile so the user reads "breadth" rather than just
    // "depth" (Most-played top-N already covers depth).
    var distinctArtists by remember { mutableStateOf(0L) }
    var distinctAlbums by remember { mutableStateOf(0L) }
    LaunchedEffect(windowDays) {
        totalMs = stationRepo.totalListenedMs()
        totalPlays = stationRepo.countAllPlays()
        listeningSinceMs = stationRepo.earliestPlayedAt()
        distinctArtists = stationRepo.distinctArtistsPlayed()
        distinctAlbums = stationRepo.distinctAlbumsPlayed()
        // Last-7-days vs the prior 7. Both are inclusive of "now",
        // exclusive of the lower bound, so the boundary
        // overlaps cleanly. Returns null when last week had zero
        // listened time — a "+∞%" arrow would lie about a 1ms blip
        // being explosive growth.
        run {
            val now = com.visorcraft.radioshed.data.nowMillis()
            val weekMs = 7L * 24L * 60L * 60L * 1000L
            val thisWeek = stationRepo.listenedMsBetween(now - weekMs, now)
            val lastWeek = stationRepo.listenedMsBetween(now - 2 * weekMs, now - weekMs)
            weekDeltaPct = if (lastWeek <= 0L) null
            else (((thisWeek - lastWeek) * 100.0) / lastWeek).toInt()
        }
        // top track ids → resolve to Track objects via LibraryRepo;
        // skip ids whose tracks have been deleted from the library.
        topTracks = stationRepo.topPlayedTracks(limit = 25L)
            .mapNotNull { (id, count) ->
                libraryRepo.getTrackById(id)?.let { it to count }
            }
        topArtists = stationRepo.topPlayedArtists(limit = 10L)
        topAlbums = stationRepo.topPlayedAlbums(limit = 10L)
        topGenres = stationRepo.topPlayedGenres(limit = 8L)
        // Top stations come from the existing play_count column on the
        // stations table — distinct from the station_history-based
        // lists above, since "play count" here is "times the user
        // started this station" (cheaper signal than per-track plays).
        topStations = stationRepo.list()
            .filter { it.stats.playCount > 0 }
            .sortedByDescending { it.stats.playCount }
            .take(5)
        mostSkipped = ratingRepo.mostSkippedTracks(limit = 10L)
        dailyActivity = stationRepo.dailyActivity(windowDays = windowDays)
        hourActivity = stationRepo.playsByHourOfDay(windowDays = windowDays)
        weekdayActivity = stationRepo.playsByWeekday(windowDays = windowDays)
        completionStats = stationRepo.completionStats()
        // Streak stays on a fixed 90-day lookback — the
        // "longest streak" stat reads as a 90-day max regardless
        // of the user's chosen view window.
        streaks = stationRepo.listeningStreaks(windowDays = 90)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Listening stats") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                // Time-window picker. Three stops (7d / 30d / 90d)
                // cover near-term scrutiny through quarter-level
                // overview. Wider than 90 starts to lose the chart's
                // utility (a daily bar across 365 columns is
                // unreadable on a phone) so we cap there.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                ) {
                    listOf(7, 30, 90).forEach { days ->
                        androidx.compose.material3.FilterChip(
                            selected = windowDays == days,
                            onClick = { windowDays = days },
                            label = { Text("$days days") },
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    StatTile(
                        label = "Total listened",
                        value = formatTotalDuration(totalMs),
                        modifier = Modifier.weight(1f),
                        trailing = weekDeltaPct?.let { pct ->
                            {
                                val arrow = when {
                                    pct > 0 -> "▲"
                                    pct < 0 -> "▼"
                                    else -> "•"
                                }
                                val color = when {
                                    pct > 0 -> MaterialTheme.colorScheme.primary
                                    pct < 0 -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                }
                                Text(
                                    "$arrow ${kotlin.math.abs(pct)}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = color,
                                )
                            }
                        },
                    )
                    StatTile(
                        label = "Tracks played",
                        value = totalPlays.toString(),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (completionStats.second > 0L) {
                    Spacer(Modifier.height(12.dp))
                    val pct = (completionStats.first.toDouble() /
                        completionStats.second.toDouble() * 100.0).toInt()
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        StatTile(
                            label = "Completion rate",
                            value = "$pct%",
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            label = "Finished",
                            value = "${completionStats.first} / ${completionStats.second}",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                // Avg tracks/day must use a WINDOWED play count, not
                // the lifetime totalPlays — otherwise selecting "7
                // days" with 10k lifetime plays renders "1428.57/day"
                // which is meaningless. dailyActivity is already
                // windowed by the same picker, so its sum is the
                // correct numerator. Two decimals when below 10 so
                // fledgling habits (~0.45/day) still show; integer at
                // higher counts to keep tiles tidy.
                val windowedPlays = dailyActivity.sumOf { it.plays }
                if (windowedPlays > 0L && windowDays > 0) {
                    Spacer(Modifier.height(12.dp))
                    val rawAvg = windowedPlays.toDouble() / windowDays.toDouble()
                    val avgLabel = when {
                        rawAvg >= 10.0 -> rawAvg.toInt().toString()
                        else -> {
                            val rounded = (rawAvg * 100.0).toInt() / 100.0
                            rounded.toString()
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        StatTile(
                            label = "Avg tracks/day",
                            value = avgLabel,
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            label = "Window",
                            value = "$windowDays day${if (windowDays == 1) "" else "s"}",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (streaks.second > 0) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        StatTile(
                            label = "Current streak",
                            value = "${streaks.first} day${if (streaks.first == 1) "" else "s"}",
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            label = "Longest streak (90d)",
                            value = "${streaks.second} day${if (streaks.second == 1) "" else "s"}",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                listeningSinceMs?.let { ms ->
                    Spacer(Modifier.height(12.dp))
                    val daysAgo = remember(ms) {
                        val DAY_MS = 24L * 60L * 60L * 1000L
                        ((com.visorcraft.radioshed.data.nowMillis() - ms) / DAY_MS)
                            .coerceAtLeast(0L)
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        StatTile(
                            label = "Listening since",
                            value = com.visorcraft.radioshed.data.formatShortDate(ms),
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            label = "Days tracked",
                            value = daysAgo.toString(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (distinctArtists > 0L || distinctAlbums > 0L) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        StatTile(
                            label = "Distinct artists",
                            value = distinctArtists.toString(),
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            label = "Distinct albums",
                            value = distinctAlbums.toString(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            if (dailyActivity.any { it.plays > 0 }) {
                item {
                    // Concrete date range reads better than a generic
                    // "Last N days". Derived against the picker's
                    // `windowDays` value AND `dailyActivity` so the
                    // label moves in lock-step with the underlying
                    // data fetch (important for users who leave the
                    // screen open across midnight).
                    val DAY_MS_LOCAL = 24L * 60L * 60L * 1000L
                    val rangeLabel = remember(dailyActivity, windowDays) {
                        runCatching {
                            val nowMs = com.visorcraft.radioshed.data.nowMillis()
                            val start = com.visorcraft.radioshed.data.formatShortDate(
                                nowMs - (windowDays - 1).toLong() * DAY_MS_LOCAL,
                            )
                            val end = com.visorcraft.radioshed.data.formatShortDate(nowMs)
                            "$start – $end"
                        }.getOrDefault("Last $windowDays days")
                    }
                    SectionHeader(rangeLabel)
                    Spacer(Modifier.height(4.dp))
                    ActivityTimeline(dailyActivity)
                    Spacer(Modifier.height(20.dp))
                }
            }

            if (hourActivity.any { it > 0L }) {
                item {
                    SectionHeader("By hour of day")
                    Spacer(Modifier.height(4.dp))
                    HourHistogram(hourActivity)
                    Spacer(Modifier.height(20.dp))
                }
            }

            if (weekdayActivity.any { it > 0L }) {
                item {
                    SectionHeader("By weekday")
                    Spacer(Modifier.height(4.dp))
                    WeekdayHistogram(weekdayActivity)
                    Spacer(Modifier.height(20.dp))
                }
            }

            if (
                topTracks.isEmpty() &&
                topArtists.isEmpty() &&
                topAlbums.isEmpty() &&
                topGenres.isEmpty() &&
                topStations.isEmpty() &&
                mostSkipped.isEmpty()
            ) {
                item {
                    Text(
                        "No listening data yet — play some music to populate.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                }
                return@LazyColumn
            }

            if (topStations.isNotEmpty()) {
                item { SectionHeader("Top stations") }
                items(topStations, key = { "st:${it.id}" }) { station ->
                    TopStationRow(
                        station = station,
                        rank = topStations.indexOfFirst { it.id == station.id } + 1,
                        onTap = {
                            scope.launch { stationPlayback.playStation(station.id) }
                        },
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }

            if (topArtists.isNotEmpty()) {
                item { SectionHeader("Top played artists") }
                items(topArtists, key = { "ar:${it.id}" }) { artist ->
                    TopArtistRow(
                        artist = artist,
                        rank = topArtists.indexOfFirst { it.id == artist.id } + 1,
                        onTap = { onArtistTap(artist.id) },
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }

            if (topAlbums.isNotEmpty()) {
                item { SectionHeader("Top played albums") }
                items(topAlbums, key = { "al:${it.id}" }) { album ->
                    TopAlbumRow(
                        album = album,
                        rank = topAlbums.indexOfFirst { it.id == album.id } + 1,
                        onTap = { onAlbumTap(album.id) },
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }

            if (topGenres.isNotEmpty()) {
                item { SectionHeader("Top played genres") }
                item { TopGenresChips(topGenres) }
                item { Spacer(Modifier.height(16.dp)) }
            }

            if (topTracks.isNotEmpty()) {
                item { SectionHeader("Top played tracks") }
                items(topTracks, key = { (t, _) -> "tr:${t.id}" }) { (track, plays) ->
                    TopTrackRow(
                        track = track,
                        plays = plays,
                        rank = topTracks.indexOfFirst { it.first.id == track.id } + 1,
                        onTap = {
                            // Tap-to-play a top track seeds an ad-hoc queue
                            // from the top list itself, mirroring how
                            // Library Browser tap-to-play works.
                            val list = topTracks.map { it.first }
                            val idx = list.indexOf(track).coerceAtLeast(0)
                            stationPlayback.playTracks(list, startIndex = idx)
                        },
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }

            if (mostSkipped.isNotEmpty()) {
                item { SectionHeader("Most skipped") }
                items(mostSkipped, key = { (t, _) -> "sk:${t.id}" }) { (track, skips) ->
                    TopTrackRow(
                        track = track,
                        plays = skips,
                        rank = mostSkipped.indexOfFirst { it.first.id == track.id } + 1,
                        playsLabel = "skip",
                        onTap = {
                            // Tap-to-play seeds the same list — useful
                            // for "let me actually listen and decide
                            // whether I should dislike this".
                            val list = mostSkipped.map { it.first }
                            val idx = list.indexOf(track).coerceAtLeast(0)
                            stationPlayback.playTracks(list, startIndex = idx)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun TopStationRow(station: Station, rank: Int, onTap: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$rank",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        // Station accent fallback to brand amber when
                        // color is unset (built-ins always set color).
                        if (station.color == 0) MaterialTheme.colorScheme.primary
                        else androidx.compose.ui.graphics.Color(
                            station.color.toLong() and 0xFFFFFFFFL,
                        ),
                    ),
            )
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = station.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "${station.stats.playCount} play${if (station.stats.playCount == 1L) "" else "s"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun TopArtistRow(artist: TopArtistEntry, rank: Int, onTap: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$rank",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "${artist.plays} play${if (artist.plays == 1L) "" else "s"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun TopAlbumRow(album: TopAlbumEntry, rank: Int, onTap: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$rank",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (album.artworkUri != null) {
                    coil3.compose.AsyncImage(
                        model = album.artworkUri,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Album,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "${album.plays} play${if (album.plays == 1L) "" else "s"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (trailing != null) {
                Spacer(Modifier.height(2.dp))
                trailing()
            }
        }
    }
}

/**
 * 24-bar hour-of-day histogram. Each bar is the play count at that
 * local hour. The label row underneath shows 0 / 6 / 12 / 18 / 24
 * gridlines so the user can read "morning" vs "evening" at a glance
 * without us cluttering with 24 labels.
 */
@Composable
private fun HourHistogram(plays: LongArray) {
    val peak = remember(plays) { (plays.maxOrNull() ?: 0L).coerceAtLeast(1L) }
    val peakHour = remember(plays) {
        val idx = plays.indices.maxByOrNull { plays[it] } ?: 0
        idx
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Peak around ${formatHour(peakHour)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (h in 0..23) {
                    val v = plays[h]
                    val ratio = if (v == 0L) 0.04f
                                else (0.12f + 0.88f * (v.toFloat() / peak.toFloat()))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((72f * ratio).dp.coerceAtLeast(2.dp))
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (v == 0L)
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                    else
                                        MaterialTheme.colorScheme.tertiary.copy(
                                            alpha = (0.45f + 0.55f * (v.toFloat() / peak.toFloat()))
                                                .coerceAtMost(1f),
                                        ),
                                ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf("12am", "6am", "noon", "6pm", "12am").forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

/** Format a 0..23 hour as "7am" / "1pm" / "midnight" / "noon". */
private fun formatHour(h: Int): String = when (h) {
    0 -> "midnight"
    12 -> "noon"
    in 1..11 -> "${h}am"
    else -> "${h - 12}pm"
}

/**
 * 7-bar weekday histogram. Index 0 = Monday … 6 = Sunday so the
 * work week reads on the left of the chart. Rendered with the same
 * bar-styling vocabulary as [HourHistogram] for visual consistency
 * within the Stats screen.
 */
@Composable
private fun WeekdayHistogram(plays: LongArray) {
    val peak = remember(plays) { (plays.maxOrNull() ?: 0L).coerceAtLeast(1L) }
    val peakIdx = remember(plays) { plays.indices.maxByOrNull { plays[it] } ?: 0 }
    val labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Busiest day: ${labels[peakIdx]}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (d in 0..6) {
                    val v = plays[d]
                    val ratio = if (v == 0L) 0.04f
                                else (0.12f + 0.88f * (v.toFloat() / peak.toFloat()))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((72f * ratio).dp.coerceAtLeast(2.dp))
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    if (v == 0L)
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                    else
                                        MaterialTheme.colorScheme.tertiary.copy(
                                            alpha = (0.45f + 0.55f * (v.toFloat() / peak.toFloat()))
                                                .coerceAtMost(1f),
                                        ),
                                ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                labels.forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * 30-day daily-plays bar chart. Each entry in [activity] is rendered
 * as one column scaled against the busiest day in the window. Empty
 * days become a hairline at the baseline so the row reads as a
 * uniform timeline rather than a sparse one. Today is the rightmost
 * bar.
 */
@Composable
private fun ActivityTimeline(
    activity: List<com.visorcraft.radioshed.data.DailyActivity>,
) {
    val peak = remember(activity) { (activity.maxOfOrNull { it.plays } ?: 0L).coerceAtLeast(1L) }
    val totalPlays = remember(activity) { activity.sumOf { it.plays } }
    val totalListenedMs = remember(activity) { activity.sumOf { it.listenedMs } }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = "$totalPlays plays · ${formatTotalDuration(totalListenedMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Text(
                    text = "peak ${peak.takeIf { it > 0L } ?: 0L}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                activity.forEach { day ->
                    // Logarithmic-ish feel: a single play should
                    // still be visible against a peak of 50, so we
                    // floor empty bars at 6% and scale the rest
                    // linearly. Looks better than a strict linear
                    // mapping where lone plays disappear.
                    val ratio = if (day.plays == 0L) 0.04f
                                else (0.12f + 0.88f * (day.plays.toFloat() / peak.toFloat()))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .padding(horizontal = 0.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((72f * ratio).dp.coerceAtLeast(2.dp))
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (day.plays == 0L)
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                    else
                                        MaterialTheme.colorScheme.primary.copy(
                                            alpha = (0.45f + 0.55f * (day.plays.toFloat() / peak.toFloat()))
                                                .coerceAtMost(1f),
                                        ),
                                ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "30 days ago",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                Text(
                    text = "today",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun TopTrackRow(
    track: Track,
    plays: Long,
    rank: Int,
    onTap: () -> Unit,
    playsLabel: String = "play",
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$rank",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
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
                Text(
                    text = track.title.ifBlank { track.fileName },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "$plays $playsLabel${if (plays == 1L) "" else "s"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * Format milliseconds into a compact "Xh Ym" or "Ym Zs" string. We
 * skip seconds once the duration crosses one hour (precision becomes
 * irrelevant for total-listened-time which spans days/weeks).
 */
private fun formatTotalDuration(ms: Long): String {
    if (ms <= 0L) return "0m"
    val totalSeconds = ms / 1_000L
    val days = totalSeconds / 86_400L
    val hours = (totalSeconds % 86_400L) / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    return when {
        days > 0L -> "${days}d ${hours}h"
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m"
        else -> "${totalSeconds}s"
    }
}

/**
 * Compact chip-cloud for top played genres. Each chip shows the
 * genre name + plays count. Wraps via FlowRow when there are more
 * than fit on a single line — Material's recommended pattern for
 * "top N tags" surfaces. Capped at 8 entries from the SQL side.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TopGenresChips(genres: List<Pair<String, Long>>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
    ) {
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            genres.forEach { (genre, plays) ->
                androidx.compose.material3.AssistChip(
                    onClick = { /* tap-to-filter is a future hook */ },
                    label = {
                        Text("$genre · $plays")
                    },
                    colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        }
    }
}
