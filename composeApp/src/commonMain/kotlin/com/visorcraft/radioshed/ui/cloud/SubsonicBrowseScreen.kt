package com.visorcraft.radioshed.ui.cloud

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.Playable
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.source.NetworkPolicyGate
import com.visorcraft.radioshed.source.SubsonicAlbum
import com.visorcraft.radioshed.source.SubsonicArtist
import com.visorcraft.radioshed.source.SubsonicClient
import com.visorcraft.radioshed.source.SubsonicServer
import com.visorcraft.radioshed.source.SubsonicSong
import com.visorcraft.radioshed.ui.util.formatDurationSeconds
import com.visorcraft.radioshed.ui.util.TooltipIconButton
import org.koin.compose.koinInject

/**
 * Cloud-library browser for Subsonic-protocol servers (Subsonic,
 * Navidrome, Airsonic, OpenSubsonic, Funkwhale, etc.). Three drill-
 * down levels with a back-stack:
 *
 *   Artists  →  Albums (per artist)  →  Songs (per album)
 *
 * Tap a song to stream. We pass the server-built stream URL straight
 * into the existing [AudioEngine.play] call — ExoPlayer handles
 * HTTPS audio URLs natively, so cloud playback uses the same path as
 * local playback (same MediaSession, same lock-screen tile, same
 * notification controls). The track id is set to -1 to mark this as
 * an "ad-hoc" play that doesn't seed a queue or get added to the
 * library DB; that integration is deferred (needs schema work for a
 * `source: 'subsonic'` discriminator on track rows).
 *
 * Limitations the user should know about:
 *  - No queue / Up Next from cloud. One-track-at-a-time playback
 *    until the local-DB-aware integration lands.
 *  - No download for offline. The stream URL is consumed live.
 *  - Skip / next won't auto-advance to the next album song, also
 *    pending the queue integration.
 *
 * Connection-policy gated: the screen disables drill-downs when
 * [SettingsStore.getConnectivityPolicy] denies metadata fetches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubsonicBrowseScreen(
    onBack: () -> Unit,
    settings: SettingsStore = koinInject(),
    client: SubsonicClient = koinInject(),
    audioEngine: AudioEngine = koinInject(),
    networkPolicy: NetworkPolicyGate = koinInject(),
) {
    // Re-derive the server tuple if the user updates credentials in
    // Settings during a multi-pane session — the navigation model
    // tears the screen down between visits today, but the server
    // captured on first composition would otherwise become permanently
    // stale if this screen ever lives in a long-lived pane.
    val urlPref = settings.getSubsonicUrl()
    val userPref = settings.getSubsonicUsername()
    val passPref = settings.getSubsonicPassword()
    val server =
        remember(urlPref, userPref, passPref) {
            SubsonicServer(
                baseUrl = urlPref,
                username = userPref,
                password = passPref,
            )
        }

    var stack by remember { mutableStateOf<List<Level>>(listOf(Level.Artists)) }
    val current = stack.last()

    var artists by remember { mutableStateOf<List<SubsonicArtist>>(emptyList()) }
    var albums by remember { mutableStateOf<List<SubsonicAlbum>>(emptyList()) }
    var songs by remember { mutableStateOf<List<SubsonicSong>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    // Cover-art URLs memoized per [albums] / [songs] list so the
    // per-call random salt in [SubsonicClient.coverArtUrl] doesn't
    // make every recomposition produce a fresh URL — Coil keys its
    // cache by URL string, so without memoization a scroll would
    // re-fetch every cover that left and re-entered the viewport.
    val albumCoverUrls =
        remember(albums, server) {
            albums.associate { album ->
                album.id to album.coverArtId?.let { client.coverArtUrl(server, it, size = 256) }
            }
        }
    val songCoverUrls =
        remember(songs, server) {
            songs.associate { song ->
                song.id to song.coverArtId?.let { client.coverArtUrl(server, it, size = 128) }
            }
        }

    LaunchedEffect(current) {
        // Honour the user's connectivity preference. Subsonic browse
        // is a metadata fetch (not an "automatic" background sync —
        // the user explicitly opened this screen) so the
        // user-initiated gate is the right one. Manual mode still
        // allows the request because the user just initiated it.
        if (!networkPolicy.allowUserInitiatedFetch()) {
            error = "Network policy blocks this fetch — change your " +
                "Connectivity preference in Settings to use this screen."
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        when (val lvl = current) {
            is Level.Artists -> {
                artists = client.getArtists(server)
                if (artists.isEmpty()) {
                    error = "No artists returned. Check server URL + credentials."
                }
            }
            is Level.Albums -> {
                albums = client.getAlbumsForArtist(server, lvl.artistId)
            }
            is Level.Songs -> {
                songs = client.getSongs(server, lvl.albumId)
            }
        }
        loading = false
    }

    com.visorcraft.radioshed.nav.PlatformBackHandler {
        if (stack.size > 1) {
            stack = stack.dropLast(1)
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (val lvl = current) {
                            is Level.Artists -> "Subsonic"
                            is Level.Albums -> lvl.artistName
                            is Level.Songs -> lvl.albumName
                        },
                    )
                },
                navigationIcon = {
                    TooltipIconButton(
                        tooltip = "Back",
                        onClick = {
                            if (stack.size > 1) stack = stack.dropLast(1) else onBack()
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (loading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                }
                return@Box
            }
            error?.let {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                return@Box
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // Filter field — applies to whichever level is
                // currently visible. Server-side search is a
                // separate Subsonic endpoint; v1 only filters the
                // already-loaded list to avoid the per-keystroke
                // round-trip that would hammer the server.
                if (current !is Level.Songs) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Filter…") },
                        singleLine = true,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    when (current) {
                        is Level.Artists -> {
                            val visible =
                                if (query.isBlank()) {
                                    artists
                                } else {
                                    artists.filter {
                                        it.name.contains(query, ignoreCase = true)
                                    }
                                }
                            items(visible, key = { it.id }) { artist ->
                                ArtistRow(
                                    artist = artist,
                                    onClick = {
                                        query = ""
                                        stack = stack +
                                            Level.Albums(
                                                artistId = artist.id,
                                                artistName = artist.name,
                                            )
                                    },
                                )
                            }
                        }
                        is Level.Albums -> {
                            val visible =
                                if (query.isBlank()) {
                                    albums
                                } else {
                                    albums.filter {
                                        it.name.contains(query, ignoreCase = true)
                                    }
                                }
                            items(visible, key = { it.id }) { album ->
                                AlbumRow(
                                    album = album,
                                    coverUrl = albumCoverUrls[album.id],
                                    onClick = {
                                        query = ""
                                        stack = stack +
                                            Level.Songs(
                                                albumId = album.id,
                                                albumName = album.name,
                                            )
                                    },
                                )
                            }
                        }
                        is Level.Songs -> {
                            items(songs, key = { it.id }) { song ->
                                SongRow(
                                    song = song,
                                    onClick = {
                                        // Stream! The audio engine
                                        // takes whatever URI we give
                                        // it; ExoPlayer's default
                                        // DataSource opens HTTPS
                                        // resources directly.
                                        audioEngine.play(
                                            Playable(
                                                trackId = -1L,
                                                uri = client.streamUrl(server, song.id),
                                                title = song.title,
                                                artist = song.artist,
                                                album = song.album,
                                                durationMs = song.durationSec * 1000L,
                                                artworkUri = songCoverUrls[song.id],
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistRow(
    artist: SubsonicArtist,
    onClick: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Column {
            Text(
                text = artist.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (artist.albumCount > 0) {
                Text(
                    text = "${artist.albumCount} album${if (artist.albumCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun AlbumRow(
    album: SubsonicAlbum,
    coverUrl: String?,
    onClick: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        color = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(4.dp)),
            ) {
                if (coverUrl != null) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {}
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub =
                    buildString {
                        album.year?.let {
                            append(it)
                            append(" · ")
                        }
                        if (album.songCount > 0) append("${album.songCount} tracks")
                    }
                if (sub.isNotEmpty()) {
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

@Composable
private fun SongRow(
    song: SubsonicSong,
    onClick: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        color = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub =
                    buildString {
                        song.track?.let {
                            append(it.toString().padStart(2, '0'))
                            append(" · ")
                        }
                        append(formatDurationSeconds(song.durationSec.toLong()))
                        song.bitRateKbps?.let {
                            append(" · ")
                            append("$it kbps")
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


private sealed interface Level {
    data object Artists : Level

    data class Albums(val artistId: String, val artistName: String) : Level

    data class Songs(val albumId: String, val albumName: String) : Level
}
