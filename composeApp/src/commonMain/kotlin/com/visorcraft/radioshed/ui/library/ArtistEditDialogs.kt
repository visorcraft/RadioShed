package com.visorcraft.radioshed.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.visorcraft.radioshed.domain.Artist
import com.visorcraft.radioshed.domain.ArtistAlias
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Edit-artist dialog. Two things in one screen:
 *  - Rename the artist (display name; sort-name is renormalized
 *    server-side so capitalization changes don't fragment lookups).
 *  - Add / remove alternate names. Each alias means "tracks tagged
 *    with this name link to THIS artist", so the user can collapse
 *    stage-name variants ("Slim Shady" → Eminem) without a merge.
 *
 * Aliases load on first open; the local edit set tracks pending
 * insertions / deletions so the user can chain edits before Save.
 * Save commits both the rename and the alias diff in sequence.
 */
@Composable
fun EditArtistDialog(
    artist: Artist,
    onDismiss: () -> Unit,
    libraryRepo: LibraryRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(artist.name) }
    var newAliasText by remember { mutableStateOf("") }
    // Aliases currently in the DB for this artist. Loaded on first
    // composition; the user-facing add / remove paths mutate
    // `aliases` directly, then commit on Save.
    val aliases = remember { mutableStateListOf<ArtistAlias>() }
    // Locally-added alias display names that aren't in the DB yet
    // — committed on Save. Kept as a separate list so deletes only
    // touch already-persisted rows (deleting a still-pending entry
    // is just a list removal here).
    val pendingNewAliases = remember { mutableStateListOf<String>() }
    // Already-persisted alias ids the user removed in this session.
    // Applied as deletes on Save so a cancel-out closes the dialog
    // without touching the DB.
    val pendingDeletedAliasIds = remember { mutableStateListOf<Long>() }
    var loaded by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(artist.id) {
        aliases.clear()
        aliases.addAll(libraryRepo.aliasesForArtist(artist.id))
        loaded = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit artist") },
        confirmButton = {
            Button(
                enabled = loaded && name.trim().isNotEmpty(),
                onClick = {
                    scope.launch {
                        val trimmed = name.trim()
                        // Rename first so a UNIQUE collision surfaces
                        // here and aborts the alias commit cleanly.
                        // Skip the call when nothing actually changed
                        // to avoid bumping updated_at for no reason.
                        val nameChanged = trimmed != artist.name
                        val renameOk =
                            if (!nameChanged) {
                                true
                            } else {
                                libraryRepo.renameArtist(artist.id, trimmed)
                            }
                        if (!renameOk) {
                            saveError = "Another artist already uses that name. " +
                                "Use Merge instead."
                            return@launch
                        }
                        // Commit alias deletes.
                        for (id in pendingDeletedAliasIds.toList()) {
                            libraryRepo.deleteArtistAlias(id)
                        }
                        // Commit alias inserts. Skip silently on
                        // collision — the user will see the alias
                        // missing from the next open and can
                        // investigate. We could surface per-alias
                        // errors but the noise/value tradeoff favours
                        // silent skip for the common typo case.
                        for (alias in pendingNewAliases.toList()) {
                            libraryRepo.addArtistAlias(artist.id, alias)
                        }
                        onDismiss()
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        saveError = null
                    },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                saveError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Alternate names",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Tracks tagged with any of these names will link to " +
                        "this artist on the next scan. Useful for stage-name " +
                        "variants or non-Latin script versions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(8.dp))

                // Persisted aliases (filtered to drop the ones marked
                // for deletion in this session — they stay in the DB
                // until Save but should disappear from the chip list
                // immediately so the UI reflects intent).
                aliases.forEach { alias ->
                    if (alias.id in pendingDeletedAliasIds) return@forEach
                    AliasChip(
                        text = alias.aliasName,
                        onRemove = { pendingDeletedAliasIds.add(alias.id) },
                    )
                }
                // Pending (not-yet-saved) aliases.
                pendingNewAliases.forEach { pending ->
                    AliasChip(
                        text = "$pending (new)",
                        onRemove = { pendingNewAliases.remove(pending) },
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newAliasText,
                        onValueChange = { newAliasText = it },
                        label = { Text("Add alternate name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    TextButton(
                        enabled = newAliasText.trim().isNotEmpty(),
                        onClick = {
                            val v = newAliasText.trim()
                            if (v.isNotEmpty() && v !in pendingNewAliases &&
                                aliases.none { it.aliasName.equals(v, ignoreCase = true) }
                            ) {
                                pendingNewAliases.add(v)
                            }
                            newAliasText = ""
                        },
                    ) { Text("Add") }
                }
            }
        },
    )
}

@Composable
private fun AliasChip(
    text: String,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remove alternate name",
                    modifier = Modifier.padding(2.dp),
                )
            }
        }
    }
}

/**
 * Merge-artist picker + confirmation. Two-step:
 *  1. Search-filterable list of every OTHER artist; tap one to
 *     select the merge target.
 *  2. Confirmation dialog: "Merge 'Source' into 'Target'? N tracks
 *     will be relinked. This can't be undone."
 *
 * The merge itself is a single transaction in
 * [LibraryRepository.mergeArtists]; this dialog just gates the
 * destructive action behind the explicit picker + confirm pair.
 */
@Composable
fun MergeArtistDialog(
    source: Artist,
    onDismiss: () -> Unit,
    onMerged: () -> Unit,
    libraryRepo: LibraryRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val allArtists = remember { mutableStateListOf<Artist>() }
    var loaded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Artist?>(null) }
    var merging by remember { mutableStateOf(false) }
    var mergeError by remember { mutableStateOf<String?>(null) }
    var sourceTrackCount by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(source.id) {
        // Pull the full artist list and the source's track count
        // up-front so the user can see "N tracks will move" in the
        // confirm dialog without a second round-trip. observeArtists
        // returns a Flow; .first() snapshots the current emission
        // and exits (we're not subscribing to updates here — if the
        // user merges concurrently from another surface they can
        // re-open this dialog).
        allArtists.clear()
        allArtists.addAll(
            libraryRepo.observeArtists().first().filter { it.id != source.id },
        )
        sourceTrackCount = libraryRepo.tracksForArtist(source.id).size
        loaded = true
    }

    when {
        picked == null ->
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Merge \"${source.name}\" into…") },
                confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
                text = {
                    Column {
                        Text(
                            "Pick the artist to keep. Tracks linked to " +
                                "\"${source.name}\" will be relinked to the artist " +
                                "you choose, and \"${source.name}\" will be removed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Search artists") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        val filtered =
                            remember(query, allArtists.size) {
                                val q = query.trim().lowercase()
                                if (q.isEmpty()) {
                                    allArtists.toList()
                                } else {
                                    allArtists.filter {
                                        it.sortName.contains(q) ||
                                            it.name.lowercase().contains(q)
                                    }
                                }
                            }
                        if (!loaded) {
                            Text(
                                "Loading…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        } else if (filtered.isEmpty()) {
                            Text(
                                if (query.isBlank()) {
                                    "No other artists to merge into."
                                } else {
                                    "No matches for \"$query\"."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        } else {
                            LazyColumn(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 320.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                items(filtered, key = { it.id }) { artist ->
                                    Surface(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable { picked = artist },
                                        color =
                                            MaterialTheme.colorScheme.surfaceVariant
                                                .copy(alpha = 0.3f),
                                        shape = MaterialTheme.shapes.small,
                                    ) {
                                        Text(
                                            artist.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier =
                                                Modifier
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
            )
        else ->
            AlertDialog(
                onDismissRequest = { if (!merging) picked = null },
                title = { Text("Merge artists?") },
                text = {
                    Column {
                        Text(
                            "\"${source.name}\" will be merged into " +
                                "\"${picked!!.name}\". " +
                                (
                                    sourceTrackCount?.let {
                                        "$it track${if (it == 1) "" else "s"} will be relinked. "
                                    } ?: ""
                                ) +
                                "\"${source.name}\" will be removed from your library. " +
                                "This can't be undone, but future scans of tracks tagged " +
                                "with \"${source.name}\" will resolve to " +
                                "\"${picked!!.name}\" automatically.",
                        )
                        mergeError?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = !merging,
                        onClick = {
                            merging = true
                            mergeError = null
                            scope.launch {
                                // mergeArtists runs an atomic 6-step
                                // transaction with FK-touching writes; a
                                // concurrent delete of the target (race
                                // against another surface that just removed
                                // it) would surface as a FK violation that
                                // throws out of the transaction. Wrap in
                                // runCatching so the dialog doesn't freeze
                                // with `merging = true` permanently — the
                                // user sees the error inline and can pick a
                                // different target.
                                runCatching {
                                    libraryRepo.mergeArtists(
                                        sourceArtistId = source.id,
                                        targetArtistId = picked!!.id,
                                    )
                                }.onSuccess { onMerged() }
                                    .onFailure {
                                        merging = false
                                        mergeError = "Merge failed: " +
                                            (it.message ?: it::class.simpleName ?: "unknown")
                                    }
                            }
                        },
                    ) { Text(if (merging) "Merging…" else "Merge") }
                },
                dismissButton = {
                    TextButton(enabled = !merging, onClick = {
                        picked = null
                        mergeError = null
                    }) { Text("Back") }
                },
            )
    }
}
