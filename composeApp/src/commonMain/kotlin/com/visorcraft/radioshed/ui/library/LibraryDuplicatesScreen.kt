package com.visorcraft.radioshed.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.ui.util.TooltipIconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Read-only duplicate-track finder. Groups tracks by tag-fuzz key
 * (lowercased primary-artist name + lowercased title + durationMs
 * rounded to the second) so the same recording surfaces once even if
 * it was re-ripped at a different bitrate.
 *
 * Why tag fuzz over content hash:
 *  - Hashing N-thousand FLAC/MP3 files is very slow and battery-hungry.
 *  - The same recording often re-rips at slightly different bitrates
 *    or formats — content differs but it's still a duplicate from the
 *    user's point of view.
 *  - Tag fuzz catches both "same album imported twice" and "iTunes
 *    re-ripped at higher bitrate" in one pass.
 *
 * Limitations:
 *  - Mistagged files won't group even if they're the same recording.
 *  - Sub-second rounding means a 3:32.499 and 3:32.001 group together
 *    — usually correct (rounding artefact from a tag rewrite), but a
 *    near-miss is possible.
 *
 * No delete action — surfaces duplicates only. Deleting the wrong
 * copy of a "duplicate" that's actually a remaster vs. original is
 * unrecoverable; user should fall back to Track Info → Open folder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryDuplicatesScreen(
    onBack: () -> Unit,
    onTrackClick: (Track) -> Unit,
    libraryRepo: LibraryRepository = koinInject(),
) {
    var loading by remember { mutableStateOf(true) }
    var groups by remember { mutableStateOf<List<DupGroup>>(emptyList()) }
    var totalDup by remember { mutableStateOf(0) }
    var wastedBytes by remember { mutableStateOf(0L) }
    var artistByTrack by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var albumByTrack by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }

    LaunchedEffect(Unit) {
        loading = true
        val result =
            withContext(Dispatchers.Default) {
                // 100k cap dwarfs realistic libraries; if a bigger one
                // ever showed up we'd page, but for now this keeps the
                // implementation simple and one-shot. Available-only
                // because deleted/missing tracks aren't a real duplicate
                // candidate to surface.
                val all = libraryRepo.listAvailableTracks(limit = 100_000L, offset = 0L)
                val ids = all.map { it.id }
                val artistIds = libraryRepo.primaryArtistMap(ids)
                // Pre-fetch every artist + album once so the per-track
                // fuzz-key build runs in pure memory rather than re-hitting
                // the DB for each row.
                val artistNames: Map<Long, String> =
                    artistIds.values
                        .toSet()
                        .mapNotNull { aid -> libraryRepo.getArtistById(aid)?.let { aid to it.name } }
                        .toMap()
                val albumIds = all.mapNotNull { it.albumId }.toSet()
                val albumTitles: Map<Long, String> =
                    albumIds
                        .mapNotNull { aid -> libraryRepo.getAlbumById(aid)?.let { aid to it.title } }
                        .toMap()

                val artistByTrackMap =
                    ids.mapNotNull { tid ->
                        val aid = artistIds[tid] ?: return@mapNotNull null
                        val name = artistNames[aid] ?: return@mapNotNull null
                        tid to name
                    }.toMap()
                val albumByTrackMap =
                    all.mapNotNull { t ->
                        val aid = t.albumId ?: return@mapNotNull null
                        val title = albumTitles[aid] ?: return@mapNotNull null
                        t.id to title
                    }.toMap()

                val byKey = all.groupBy { fuzzKey(it, artistByTrackMap[it.id]) }
                val dups =
                    byKey.values
                        .filter { it.size > 1 }
                        .map { tracks ->
                            val sorted = tracks.sortedBy { it.path }
                            DupGroup(
                                key = fuzzKey(sorted.first(), artistByTrackMap[sorted.first().id]),
                                artistName = artistByTrackMap[sorted.first().id],
                                tracks = sorted,
                            )
                        }
                        .sortedByDescending { it.tracks.size }
                val dupTrackCount = dups.sumOf { it.tracks.size - 1 }
                val wasted =
                    dups.sumOf { g ->
                        // Pessimistic estimate: every duplicate copy beyond the
                        // largest is "wasted" disk. Assumes the user keeps the
                        // highest-quality rip — typical, but a user with the
                        // opposite preference (smallest copy) gets a misleading
                        // number. Surfaced as "≈" to set expectations.
                        val sizes = g.tracks.map { it.fileSize }.filter { it > 0 }
                        if (sizes.size <= 1) 0L else sizes.sortedDescending().drop(1).sum()
                    }
                DupResult(
                    groups = dups,
                    totalDup = dupTrackCount,
                    wastedBytes = wasted,
                    artistByTrack = artistByTrackMap,
                    albumByTrack = albumByTrackMap,
                )
            }
        groups = result.groups
        totalDup = result.totalDup
        wastedBytes = result.wastedBytes
        artistByTrack = result.artistByTrack
        albumByTrack = result.albumByTrack
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Duplicate tracks") },
                navigationIcon = {
                    TooltipIconButton(tooltip = "Back", onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            if (loading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Scanning library for duplicates…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                return@Box
            }
            if (groups.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "No duplicate tracks found.",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Library appears tag-fuzz unique. Mistagged files won't be detected — fix the tags and re-scan to surface them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                return@Box
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "${groups.size} group${if (groups.size == 1) "" else "s"} · $totalDup extra cop${if (totalDup == 1) "y" else "ies"}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (wastedBytes > 0) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "≈ ${formatBytes(wastedBytes)} potentially recoverable",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }
                items(groups, key = { it.key }) { group ->
                    DupGroupRow(
                        group = group,
                        albumByTrack = albumByTrack,
                        onTrackClick = onTrackClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun DupGroupRow(
    group: DupGroup,
    albumByTrack: Map<Long, String>,
    onTrackClick: (Track) -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val artist = group.artistName ?: "Unknown artist"
            Text(
                text = "$artist — ${group.tracks.first().title}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${group.tracks.size} copies",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
            Spacer(Modifier.height(8.dp))
            group.tracks.forEach { track ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onTrackClick(track) }
                            .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = track.path,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sub =
                            buildString {
                                albumByTrack[track.id]?.let {
                                    append(it)
                                    append(" · ")
                                }
                                append(formatDuration(track.durationMs))
                                if (track.fileSize > 0) {
                                    append(" · ")
                                    append(formatBytes(track.fileSize))
                                }
                            }
                        Text(
                            text = sub,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}

private data class DupGroup(
    val key: String,
    val artistName: String?,
    val tracks: List<Track>,
)

private data class DupResult(
    val groups: List<DupGroup>,
    val totalDup: Int,
    val wastedBytes: Long,
    val artistByTrack: Map<Long, String>,
    val albumByTrack: Map<Long, String>,
)

private fun fuzzKey(
    t: Track,
    artist: String?,
): String {
    val a = (artist ?: "").trim().lowercase()
    val title = t.title.trim().lowercase()
    // Round to the second so a tag-rewrite that re-encoded the
    // duration with a slightly different fractional value still
    // collapses into the same group.
    val sec = (t.durationMs / 1000L)
    return "$a|$title|$sec"
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "—"
    val totalSec = (ms / 1000L).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val gb = bytes / 1_073_741_824.0
    if (gb >= 1.0) return "%.2f GB".format(gb)
    val mb = bytes / 1_048_576.0
    if (mb >= 1.0) return "%.1f MB".format(mb)
    val kb = bytes / 1024.0
    return "%.0f KB".format(kb)
}
