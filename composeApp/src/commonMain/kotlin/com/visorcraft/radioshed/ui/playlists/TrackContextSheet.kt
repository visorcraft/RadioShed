package com.visorcraft.radioshed.ui.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.PlaylistRepository
import com.visorcraft.radioshed.data.RatingRepository
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.stations.StationPlayback
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Long-press track menu. Three core actions for v1:
 *  - **Play next** — inject after the current track
 *  - **Add to queue** — append to the priority buffer's tail
 *  - **Add to playlist** — opens [AddToPlaylistSheet] on top
 *
 * Why bottom sheet not dropdown: the actions affect the playback
 * queue, which feels like a "modal commit" rather than an inline
 * tweak. The sheet's height + dismiss gesture is also more thumb-
 * friendly while driving than a tiny dropdown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackContextSheet(
    trackId: Long,
    onDismiss: () -> Unit,
    onSelect: (() -> Unit)? = null,
    libraryRepo: LibraryRepository = koinInject(),
    stationPlayback: StationPlayback = koinInject(),
    ratingRepo: RatingRepository = koinInject(),
) {
    val sheetState = rememberModalBottomSheetState()
    var track by remember { mutableStateOf<Track?>(null) }
    var liked by remember { mutableStateOf(false) }
    var primaryArtistId by remember { mutableStateOf<Long?>(null) }
    var primaryArtistName by remember { mutableStateOf<String?>(null) }
    var globallyDisliked by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val navInstalled = com.visorcraft.radioshed.nav.LocalNavInstalled.current
    val navToAlbum = com.visorcraft.radioshed.nav.LocalNavigateToAlbum.current
    val navToArtist = com.visorcraft.radioshed.nav.LocalNavigateToArtist.current
    val navToLyrics = com.visorcraft.radioshed.nav.LocalNavigateToLyrics.current
    // Internal step machine — when the user picks "Add to playlist",
    // we replace the visible content of the SAME sheet rather than
    // opening a second ModalBottomSheet on top (which produces
    // z-order / dismiss-coordination artifacts on Material3).
    var step by remember { mutableStateOf(Step.Main) }

    LaunchedEffect(trackId) {
        track = libraryRepo.getTrackById(trackId)
        liked = ratingRepo.isLiked(trackId)
        globallyDisliked = ratingRepo.isGloballyDisliked(trackId)
        // Resolve the primary artist's id once so we can navigate
        // straight from this sheet without an extra DB hit at click
        // time. Tracks with multiple artist links pick the first
        // primary (matches what the rest of the app does — top
        // artist stats, etc.).
        val firstArtist = libraryRepo.artistsForTrack(trackId).firstOrNull()
        primaryArtistId = firstArtist?.id
        primaryArtistName = firstArtist?.name
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        when (step) {
            Step.Main ->
                MainStep(
                    track = track,
                    liked = liked,
                    navInstalled = navInstalled,
                    primaryArtistId = primaryArtistId,
                    primaryArtistName = primaryArtistName,
                    onDismiss = onDismiss,
                    globallyDisliked = globallyDisliked,
                    onPlayNext = {
                        track?.let { stationPlayback.playNext(it) }
                        onDismiss()
                    },
                    onAddToQueue = {
                        track?.let { stationPlayback.addToQueue(it) }
                        onDismiss()
                    },
                    onToggleLike = {
                        val target = !liked
                        liked = target
                        scope.launch {
                            if (target) ratingRepo.like(trackId) else ratingRepo.unlike(trackId)
                        }
                    },
                    onToggleGlobalDislike = {
                        val target = !globallyDisliked
                        globallyDisliked = target
                        scope.launch {
                            if (target) {
                                ratingRepo.globalDislike(trackId)
                            } else {
                                ratingRepo.undoGlobalDislike(trackId)
                            }
                        }
                    },
                    onAddToPlaylist = { step = Step.PlaylistPicker },
                    onGoToAlbum = { id ->
                        onDismiss()
                        navToAlbum(id)
                    },
                    onGoToArtist = { id ->
                        onDismiss()
                        navToArtist(id)
                    },
                    onViewLyrics = {
                        onDismiss()
                        navToLyrics(trackId)
                    },
                    onSelect = onSelect,
                )
            Step.PlaylistPicker ->
                AddToPlaylistInline(
                    trackId = trackId,
                    onDone = onDismiss,
                    onBack = { step = Step.Main },
                )
        }
    }
}

private enum class Step { Main, PlaylistPicker }

@Composable
private fun MainStep(
    track: Track?,
    onDismiss: () -> Unit = {},
    primaryArtistName: String? = null,
    liked: Boolean,
    navInstalled: Boolean,
    primaryArtistId: Long?,
    globallyDisliked: Boolean,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleGlobalDislike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onGoToAlbum: (Long) -> Unit,
    onGoToArtist: (Long) -> Unit,
    onViewLyrics: () -> Unit,
    onSelect: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
        // Track title header — gives the user a moment of confirmation
        // that they long-pressed the right row. While the title is
        // resolving from the DB we render whitespace of the same
        // height so the actions don't bounce when it lands.
        Text(
            text = track?.displayTitle() ?: " ",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        ContextActionRow(
            icon = if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            label = if (liked) "Remove from Liked songs" else "Add to Liked songs",
            enabled = track != null,
            onClick = onToggleLike,
            iconTint = if (liked) MaterialTheme.colorScheme.primary else null,
        )
        if (onSelect != null) {
            ContextActionRow(
                icon = Icons.Filled.Check,
                label = "Select",
                enabled = track != null,
                onClick = {
                    onSelect()
                    onDismiss()
                },
            )
        }
        ContextActionRow(
            icon = Icons.Filled.PlayArrow,
            label = "Play next",
            enabled = track != null,
            onClick = onPlayNext,
        )
        ContextActionRow(
            icon = Icons.AutoMirrored.Filled.QueueMusic,
            label = "Add to queue",
            enabled = track != null,
            onClick = onAddToQueue,
        )
        ContextActionRow(
            icon = Icons.AutoMirrored.Filled.PlaylistAdd,
            label = "Add to playlist…",
            enabled = track != null,
            onClick = onAddToPlaylist,
        )
        // Nav actions only when the host installed handlers (App.kt
        // does; tests / previews don't). Album row hidden for tracks
        // with no albumId; artist row hidden when we couldn't resolve
        // a primary artist link — silent rather than disabled, since
        // a greyed-out action that does nothing reads as broken.
        if (navInstalled) {
            ContextActionRow(
                icon = Icons.Filled.Lyrics,
                label = "View lyrics",
                enabled = track != null,
                onClick = onViewLyrics,
            )
            track?.albumId?.let { aid ->
                ContextActionRow(
                    icon = Icons.Filled.Album,
                    label = "Go to album",
                    enabled = true,
                    onClick = { onGoToAlbum(aid) },
                )
            }
            primaryArtistId?.let { aid ->
                ContextActionRow(
                    icon = Icons.Filled.Person,
                    label = "Go to artist",
                    enabled = true,
                    onClick = { onGoToArtist(aid) },
                )
            }
        }
        // Quick clipboard copy of "Artist – Title" so the user can
        // paste what they're listening to into a chat / search box
        // without opening the full Track Info dialog. Routes through
        // the shared rememberCopyToClipboard so all clipboard writes
        // hit the same hook.
        run {
            val copyToClipboard = com.visorcraft.radioshed.ui.util.rememberCopyToClipboard()
            val displayArtist = primaryArtistName?.takeIf { it.isNotBlank() }
            val displayTitle =
                track?.title?.takeIf { it.isNotBlank() }
                    ?: track?.fileName
            val combined =
                remember(displayArtist, displayTitle) {
                    when {
                        displayArtist != null && displayTitle != null ->
                            "$displayArtist – $displayTitle"
                        displayTitle != null -> displayTitle
                        else -> null
                    }
                }
            ContextActionRow(
                icon = Icons.Filled.ContentCopy,
                label = "Copy artist – title",
                enabled = combined != null,
                onClick = {
                    combined?.let { copyToClipboard(it) }
                    onDismiss()
                },
            )
            // "Set as ringtone" mirrors the action in TrackInfoDialog
            // — same RingtoneSetter expect/actual, same Result type.
            // Surfacing it here lets users set a ringtone without
            // navigating into Now Playing first. The status pill
            // lives only in the dialog flow; failures here are
            // silent (the OS ringtone picker does its own UI).
            // Locally inject the setter + scope so MainStep doesn't
            // need new params threaded through the parent.
            val ringtoneSetter: com.visorcraft.radioshed.source.RingtoneSetter = koinInject()
            val ringtoneScope = rememberCoroutineScope()
            ContextActionRow(
                icon = Icons.Filled.Notifications,
                label = "Set as ringtone",
                enabled = track != null,
                onClick = {
                    val t = track
                    if (t != null) {
                        ringtoneScope.launch {
                            ringtoneSetter.setAsRingtone(
                                absolutePath = t.path,
                                displayName = t.displayTitle(),
                            )
                        }
                    }
                    onDismiss()
                },
            )
            // Copy raw file path — handy for debugging, sharing the
            // path with another app (e.g. a tag editor on desktop via
            // Syncthing), or pasting into the file manager search.
            // Already exists on Track Info dialog but the long-press
            // sheet is faster for this use case.
            ContextActionRow(
                icon = Icons.Filled.ContentCopy,
                label = "Copy file path",
                enabled = track != null,
                onClick = {
                    track?.path?.let { copyToClipboard(it) }
                    onDismiss()
                },
            )
        }
        // Global dislike — "never play this anywhere" — sits at the
        // bottom because it's a destructive-ish action distinct from
        // the per-station "skip on this station". When already
        // banned, the label flips so the user can undo it.
        ContextActionRow(
            icon =
                if (globallyDisliked) {
                    Icons.Filled.Favorite
                } else {
                    Icons.Filled.Block
                },
            label =
                if (globallyDisliked) {
                    "Allow on stations again"
                } else {
                    "Don't play this anywhere"
                },
            enabled = track != null,
            onClick = onToggleGlobalDislike,
            iconTint =
                if (globallyDisliked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
        )
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Inline playlist-picker shown inside the same ModalBottomSheet as
 * MainStep. Mirrors AddToPlaylistSheet's content (existing playlists +
 * "Create new" + new-playlist dialog) without nesting a second sheet.
 */
@Composable
private fun AddToPlaylistInline(
    trackId: Long,
    onDone: () -> Unit,
    onBack: () -> Unit,
    repo: PlaylistRepository = koinInject(),
) {
    val playlists by repo.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var createOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(
                "Add to playlist",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
            item {
                ContextActionRow(
                    icon = Icons.Filled.Add,
                    label = "Create new playlist…",
                    enabled = true,
                    onClick = { createOpen = true },
                )
            }
            items(playlists, key = { it.id }) { playlist ->
                ContextActionRow(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    label = playlist.name,
                    enabled = true,
                    onClick = {
                        scope.launch {
                            // Atomic append — see PlaylistRepository.appendTrack.
                            // The previous count + addTrack split was racy and
                            // could leave duplicate position values when two
                            // adds landed close together.
                            repo.appendTrack(playlist.id, trackId)
                            onDone()
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (createOpen) {
        var newName by remember { mutableStateOf("") }
        val confirmCreate: () -> Unit = {
            val n = newName.trim()
            if (n.isNotEmpty()) {
                scope.launch {
                    val id = repo.insert(name = n)
                    repo.addTrack(id, trackId, position = 0)
                    createOpen = false
                    onDone()
                }
            }
        }
        AlertDialog(
            onDismissRequest = { createOpen = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        ),
                    keyboardActions =
                        androidx.compose.foundation.text.KeyboardActions(
                            onDone = { confirmCreate() },
                        ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = confirmCreate,
                    enabled = newName.isNotBlank(),
                ) { Text("Create + add") }
            },
            dismissButton = {
                TextButton(onClick = { createOpen = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ContextActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    iconTint: androidx.compose.ui.graphics.Color? = null,
) {
    val baseTint =
        if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint ?: baseTint,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = baseTint,
        )
    }
}
