package com.visorcraft.radioshed.ui.stations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.data.FolderRepository
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.domain.PlayOrder
import com.visorcraft.radioshed.domain.PlaybackRule
import com.visorcraft.radioshed.domain.SourceRule
import com.visorcraft.radioshed.domain.Station
import com.visorcraft.radioshed.domain.Weighting
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Station Editor — create a new station or edit an existing one.
 *
 * MVP scope (Phase 5 part 2 — first cut):
 *  - Identity: name (required), optional subtitle, color picked from a small
 *    swatch row
 *  - Source: multi-select of registered folders → builds a
 *    [SourceRule.FolderSelector]. Other rule kinds (TagFilter, SmartQuery,
 *    Union/Intersection/Difference/TopN) deferred — they need their own
 *    editor surfaces and aren't part of the "I want to make a station from
 *    a folder" 80% case.
 *  - Playback: [PlayOrder] + [Weighting] selectors (everything else on
 *    [PlaybackRule] keeps the default — crossfade=Smart, gapless=true,
 *    skipOnDislike=Off, etc.)
 *
 * Built-in stations open a constrained variant: cosmetic fields (name,
 * subtitle, color) are editable; the source and playback fields are
 * read-only and the Delete button is hidden. This keeps the seeded preset
 * library intact while letting users personalize them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationEditorScreen(
    stationId: Long?,    // null = creating a new station
    onClose: () -> Unit,
    folderRepo: FolderRepository = koinInject(),
    stationRepo: StationRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val folders by folderRepo.observeAll().collectAsState(initial = emptyList())

    var existing by remember { mutableStateOf<Station?>(null) }
    var name by remember { mutableStateOf("") }
    var subtitle by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(BRAND_AMBER) }
    val selectedFolderIds = remember { mutableStateListOf<Long>() }
    var playOrder by remember { mutableStateOf(PlayOrder.SmartShuffle) }
    var weighting by remember { mutableStateOf(Weighting.Uniform) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    // Load existing station (one-shot when stationId resolves).
    LaunchedEffect(stationId) {
        if (stationId == null) return@LaunchedEffect
        val station = stationRepo.getById(stationId) ?: run {
            saveError = "Station $stationId not found"
            return@LaunchedEffect
        }
        existing = station
        name = station.name
        subtitle = station.customSubtitle.orEmpty()
        color = if (station.color == 0) BRAND_AMBER else station.color
        playOrder = station.playback.order
        weighting = station.playback.weighting
        when (val src = station.source) {
            is SourceRule.FolderSelector -> selectedFolderIds.addAll(src.folderIds)
            else -> {
                /* MVP can't represent non-FolderSelector sources in this
                   editor; we leave selectedFolderIds empty and the user
                   can still edit cosmetic fields. The source rule round-
                   trips unchanged on save (see saveStation) so we don't
                   accidentally clobber a Union/Intersection/etc. */
            }
        }
    }

    val isBuiltIn = existing?.isBuiltIn == true
    val isEditing = stationId != null
    val sourceEditable = !isBuiltIn
    val canSave = name.isNotBlank() && (
        // Creating: must pick at least one folder. Editing built-ins: cosmetic
        // fields only, source is locked. Editing custom: must keep at least
        // one folder selected if originally a FolderSelector.
        isBuiltIn || existing?.source !is SourceRule.FolderSelector ||
            selectedFolderIds.isNotEmpty()
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (isEditing) "Edit station" else "New station")
                },
                navigationIcon = { TextButton(onClick = onClose) { Text("Cancel") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SectionHeader("Identity")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = subtitle,
                onValueChange = { subtitle = it },
                label = { Text("Subtitle (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            SectionHeader("Color")
            ColorSwatchRow(selected = color, onPick = { color = it })

            Spacer(Modifier.height(16.dp))
            SectionHeader(if (sourceEditable) "Folders" else "Folders (built-in: read-only)")
            if (folders.isEmpty()) {
                EmptyHint("No folders registered yet. Add one in Library Settings first.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    folders.forEach { folder ->
                        FolderRow(
                            folderName = folder.displayName,
                            folderPath = folder.uri,
                            checked = folder.id in selectedFolderIds,
                            enabled = sourceEditable,
                            onToggle = {
                                if (!sourceEditable) return@FolderRow
                                if (folder.id in selectedFolderIds) selectedFolderIds.remove(folder.id)
                                else selectedFolderIds += folder.id
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader("Playback")
            EnumPicker(
                label = "Play order",
                values = PlayOrder.entries.toList(),
                selected = playOrder,
                onPick = { playOrder = it },
                labelOf = { it.uiLabel() },
                enabled = sourceEditable,
            )
            Spacer(Modifier.height(8.dp))
            EnumPicker(
                label = "Weighting",
                values = Weighting.entries.toList(),
                selected = weighting,
                onPick = { weighting = it },
                labelOf = { it.uiLabel() },
                enabled = sourceEditable,
            )

            Spacer(Modifier.height(20.dp))
            saveError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isEditing && !isBuiltIn && stationId != null) {
                    OutlinedButton(
                        onClick = {
                            if (saving) return@OutlinedButton
                            scope.launch {
                                runCatching { stationRepo.delete(stationId) }
                                    .onSuccess { onClose() }
                                    .onFailure { saveError = "Delete failed: ${it.message}" }
                            }
                        },
                    ) { Text("Delete") }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                Button(
                    onClick = {
                        if (saving) return@Button
                        saving = true
                        saveError = null
                        scope.launch {
                            runCatching {
                                saveStation(
                                    repo = stationRepo,
                                    existing = existing,
                                    name = name.trim(),
                                    subtitle = subtitle.trim().ifBlank { null },
                                    color = color,
                                    selectedFolderIds = selectedFolderIds.toList(),
                                    playOrder = playOrder,
                                    weighting = weighting,
                                )
                            }.onSuccess { onClose() }
                                .onFailure { saveError = "Save failed: ${it.message}" }
                            saving = false
                        }
                    },
                    enabled = canSave && !saving,
                ) { Text(if (isEditing) "Save" else "Create") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Persist a new or edited station. Built-in stations preserve their original
 * source rule (we don't expose source-editing for them in MVP); user-edited
 * stations whose original source wasn't a FolderSelector also preserve the
 * original source unchanged, so the editor doesn't accidentally rewrite a
 * Union/Intersection/etc. that the editor can't represent yet.
 */
private suspend fun saveStation(
    repo: StationRepository,
    existing: Station?,
    name: String,
    subtitle: String?,
    color: Int,
    selectedFolderIds: List<Long>,
    playOrder: PlayOrder,
    weighting: Weighting,
) {
    val source: SourceRule = when {
        existing == null ->
            // New station: always FolderSelector for MVP.
            SourceRule.FolderSelector(selectedFolderIds)
        existing.source is SourceRule.FolderSelector ->
            // Editing a folder-based station: update the folder list.
            SourceRule.FolderSelector(selectedFolderIds)
        else ->
            // Editing something else (built-in compound rule) — preserve
            // whatever the original was. The editor's folder selection is
            // ignored in this branch.
            existing.source
    }
    val playback = (existing?.playback ?: PlaybackRule()).copy(
        order = playOrder,
        weighting = weighting,
    )
    if (existing == null) {
        repo.insert(
            name = name,
            source = source,
            playback = playback,
            color = color,
            customSubtitle = subtitle,
            isBuiltIn = false,
        )
    } else {
        repo.update(
            id = existing.id,
            name = name,
            source = source,
            playback = playback,
            color = color,
            icon = existing.icon,
            artworkUri = existing.artworkUri,
            customSubtitle = subtitle,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
    )
}

@Composable
private fun ColorSwatchRow(selected: Int, onPick: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SWATCHES.forEach { swatch ->
            val isSelected = swatch == selected
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(swatch.toLong() and 0xFFFFFFFFL))
                    .border(
                        width = if (isSelected) 3.dp else 0.dp,
                        color = MaterialTheme.colorScheme.onBackground,
                        shape = CircleShape,
                    )
                    .clickable { onPick(swatch) },
            )
        }
    }
}

@Composable
private fun FolderRow(
    folderName: String,
    folderPath: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onToggle),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.3f else 0.15f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    folderName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    folderPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun <T> EnumPicker(
    label: String,
    values: List<T>,
    selected: T,
    onPick: (T) -> Unit,
    labelOf: (T) -> String,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = { if (enabled) open = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(labelOf(selected))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            values.forEach { value ->
                DropdownMenuItem(
                    text = { Text(labelOf(value)) },
                    onClick = {
                        onPick(value)
                        open = false
                    },
                )
            }
        }
    }
}

private fun PlayOrder.uiLabel(): String = when (this) {
    PlayOrder.Shuffle -> "Shuffle"
    PlayOrder.Sequential -> "Sequential"
    PlayOrder.SmartShuffle -> "Smart shuffle (no-repeat memory)"
    PlayOrder.WeightedRandom -> "Weighted random"
}

private fun Weighting.uiLabel(): String = when (this) {
    Weighting.Uniform -> "Uniform"
    Weighting.FavorRecent -> "Favor recently added"
    Weighting.FavorUnderPlayed -> "Favor under-played"
    Weighting.FavorHigherRated -> "Favor higher-rated"
}

// 8 swatches in muted, in-car-readable hues. Brand amber leads.
private const val BRAND_AMBER = 0xFFFF7F1A.toInt()
private val SWATCHES = listOf(
    BRAND_AMBER,
    0xFFE53935.toInt(), // red
    0xFFFB8C00.toInt(), // orange
    0xFFFDD835.toInt(), // yellow
    0xFF43A047.toInt(), // green
    0xFF00ACC1.toInt(), // teal
    0xFF1E88E5.toInt(), // blue
    0xFF8E24AA.toInt(), // purple
)
