package com.visorcraft.radioshed.ui.settings

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
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.db.RadioShedDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Read-only database stats screen — power-user diagnostic tool. Lists
 * every major table along with its row count so the user (or a bug
 * report) can answer "is the scanner stuck?", "did the migration take
 * out my ratings?", "how many backups are accumulating?" without
 * needing `adb pull` and a SQLite CLI.
 *
 * Counts only — no row inspection. A full table browser is on the
 * deferred list and would require exposing the SQLDelight driver
 * directly to run ad-hoc `SELECT *`. Counts via existing queries are
 * cheap, side-effect-free, and answer the most common diagnostic
 * questions.
 *
 * Reachable from About → long-press on the version row, so casual
 * users don't trip over it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DbBrowserScreen(
    onBack: () -> Unit,
    db: RadioShedDatabase = koinInject(),
) {
    var loading by remember { mutableStateOf(true) }
    var rows by remember { mutableStateOf<List<TableStat>>(emptyList()) }

    LaunchedEffect(Unit) {
        loading = true
        rows = withContext(Dispatchers.Default) {
            // Each entry below names a logical table or category and
            // pulls its count via the existing SQLDelight query of
            // record. Wrapped in runCatching individually so a single
            // missing migration doesn't blank the whole screen — failed
            // counts surface as "?".
            buildList {
                add(stat("tracks (total)") {
                    db.libraryQueries.countTracks().executeAsOne()
                })
                add(stat("artists") {
                    db.libraryQueries.countArtists().executeAsOne()
                })
                add(stat("albums") {
                    db.libraryQueries.countAlbums().executeAsOne()
                })
                add(stat("albums missing artwork") {
                    db.libraryQueries.countAlbumsMissingArtwork().executeAsOne()
                })
                add(stat("folders") {
                    db.foldersQueries.count().executeAsOne()
                })
                add(stat("stations") {
                    db.stationsQueries.countStations().executeAsOne()
                })
                add(stat("station plays (history rows)") {
                    db.stationsQueries.countAllPlays().executeAsOne()
                })
                add(stat("playlists") {
                    db.playlistsQueries.countPlaylists().executeAsOne()
                })
                add(stat("liked tracks") {
                    db.ratingsQueries.countLikedTracks().executeAsOne()
                })
                add(stat("backups on disk (last seen)") {
                    db.backupsQueries.countBackups().executeAsOne()
                })
            }
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Database stats") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (loading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Counting rows…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                return@Box
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            ) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Read-only counts. Nothing here writes to the database.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                items(rows, key = { it.label }) { stat ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stat.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = stat.value?.toString() ?: "?",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class TableStat(val label: String, val value: Long?)

private inline fun stat(label: String, block: () -> Long): TableStat =
    TableStat(label = label, value = runCatching(block).getOrNull())
