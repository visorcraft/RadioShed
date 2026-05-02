package com.visorcraft.radioshed.ui.stations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.visorcraft.radioshed.domain.SmartQueryKind
import com.visorcraft.radioshed.domain.SourceRule
import com.visorcraft.radioshed.domain.Station
import com.visorcraft.radioshed.domain.Weighting
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Station Editor — create or edit a station. Now supports four source-rule
 * types via an in-form type picker:
 *
 *  - Folders: multi-select of registered library folders →
 *    [SourceRule.FolderSelector]. Most common case for users with file-
 *    system-organized libraries.
 *  - Smart Query: [SourceRule.SmartQuery] with a [SmartQueryKind] and
 *    a row limit. Newest, Most Played, Top Rated, Never Played, etc. —
 *    these power the seeded built-in presets.
 *  - Tag Filter: [SourceRule.TagFilter] with a tag key (e.g. GENRE,
 *    MOOD), one or more values, and an "all of" vs "any of" mode. The
 *    canonical "all my Rock tracks" station regardless of folder layout.
 *  - Path Pattern: [SourceRule.PathPattern] with include / exclude
 *    glob lists. Useful when files are organized but not at folder
 *    boundaries — e.g. matching FLAC files anywhere under a "Live"
 *    subdirectory regardless of which library folder it lives in.
 *
 * One source type per station for now. Compound rules
 * ([SourceRule.Union] / [SourceRule.Intersection] / [SourceRule.Difference]
 * / [SourceRule.TopN]) round-trip unchanged when editing — the editor
 * shows them as read-only with a hint that compound editing isn't yet
 * exposed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationEditorScreen(
    stationId: Long?,    // null = creating
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
    var playOrder by remember { mutableStateOf(PlayOrder.SmartShuffle) }
    var weighting by remember { mutableStateOf(Weighting.Uniform) }

    // Source-rule state. We hold per-type fields ALL the time; switching
    // types just changes which set is read on save. The other slots
    // preserve their values so toggling back doesn't lose them.
    var sourceType by remember { mutableStateOf(SourceType.Folders) }

    // Folders
    val selectedFolderIds = remember { mutableStateListOf<Long>() }
    // Smart Query
    var smartKind by remember { mutableStateOf(SmartQueryKind.NewestN) }
    var smartLimitText by remember { mutableStateOf("100") }
    // Tag Filter
    var tagKey by remember { mutableStateOf("GENRE") }
    var tagValuesText by remember { mutableStateOf("") }
    var tagMatchAll by remember { mutableStateOf(false) }
    // Path Pattern
    var pathIncludesText by remember { mutableStateOf("") }
    var pathExcludesText by remember { mutableStateOf("") }

    var saveError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    // True when the existing station's source rule isn't representable
    // in the editor (compound rules, unsupported selectors). We freeze
    // the source section in that case to avoid clobbering it.
    var sourceLockedAsCompound by remember { mutableStateOf(false) }

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
            is SourceRule.FolderSelector -> {
                sourceType = SourceType.Folders
                selectedFolderIds.addAll(src.folderIds)
            }
            is SourceRule.SmartQuery -> {
                sourceType = SourceType.SmartQuery
                smartKind = src.kind
                smartLimitText = src.limit.toString()
            }
            is SourceRule.TagFilter -> {
                sourceType = SourceType.TagFilter
                tagKey = src.key
                tagValuesText = src.values.joinToString(", ")
                tagMatchAll = src.matchAll
            }
            is SourceRule.PathPattern -> {
                sourceType = SourceType.PathPattern
                pathIncludesText = src.include.joinToString(", ")
                pathExcludesText = src.exclude.joinToString(", ")
            }
            else -> {
                // Compound rules + selectors we don't expose (Playlist,
                // Artist, Album, Duration, FileFormat) round-trip unchanged.
                sourceLockedAsCompound = true
            }
        }
    }

    val isBuiltIn = existing?.isBuiltIn == true
    val isEditing = stationId != null
    val sourceEditable = !isBuiltIn && !sourceLockedAsCompound
    val canSave = name.isNotBlank() && !saving && when {
        !sourceEditable -> true   // cosmetic edit; source preserved as-is
        sourceType == SourceType.Folders -> selectedFolderIds.isNotEmpty()
        sourceType == SourceType.SmartQuery -> smartLimitText.toIntOrNull()?.let { it > 0 } == true
        sourceType == SourceType.TagFilter -> tagKey.isNotBlank() && tagValuesText.isNotBlank()
        sourceType == SourceType.PathPattern -> pathIncludesText.isNotBlank()
        else -> true
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(if (isEditing) "Edit station" else "New station") },
                navigationIcon = { TextButton(onClick = onClose) { Text("Cancel") } },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
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
            // ---- Identity ----
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

            // ---- Color ----
            Spacer(Modifier.height(16.dp))
            SectionHeader("Color")
            ColorSwatchRow(selected = color, onPick = { color = it })

            // ---- Source ----
            Spacer(Modifier.height(16.dp))
            SectionHeader(
                when {
                    sourceLockedAsCompound -> "Source (compound rule — read-only)"
                    isBuiltIn -> "Source (built-in: read-only)"
                    else -> "Source"
                },
            )
            if (sourceLockedAsCompound) {
                EmptyHint(
                    "This station uses a compound rule (Union / Intersection / etc) that " +
                        "the basic editor can't represent yet. Cosmetic edits above will " +
                        "save without changing the rule.",
                )
            } else {
                SourceTypeTabs(
                    selected = sourceType,
                    onSelect = { sourceType = it },
                    enabled = sourceEditable,
                )
                Spacer(Modifier.height(8.dp))
                when (sourceType) {
                    SourceType.Folders -> FoldersConfig(
                        folders = folders,
                        selectedFolderIds = selectedFolderIds,
                        enabled = sourceEditable,
                    )
                    SourceType.SmartQuery -> SmartQueryConfig(
                        kind = smartKind,
                        onKind = { smartKind = it },
                        limitText = smartLimitText,
                        onLimitText = { smartLimitText = it },
                        enabled = sourceEditable,
                    )
                    SourceType.TagFilter -> TagFilterConfig(
                        key = tagKey,
                        onKey = { tagKey = it },
                        valuesText = tagValuesText,
                        onValuesText = { tagValuesText = it },
                        matchAll = tagMatchAll,
                        onMatchAll = { tagMatchAll = it },
                        enabled = sourceEditable,
                    )
                    SourceType.PathPattern -> PathPatternConfig(
                        includesText = pathIncludesText,
                        onIncludesText = { pathIncludesText = it },
                        excludesText = pathExcludesText,
                        onExcludesText = { pathExcludesText = it },
                        enabled = sourceEditable,
                    )
                }
            }

            // ---- Playback ----
            Spacer(Modifier.height(16.dp))
            SectionHeader("Playback")
            EnumPicker(
                label = "Play order",
                values = PlayOrder.entries.toList(),
                selected = playOrder,
                onPick = { playOrder = it },
                labelOf = { it.uiLabel() },
                enabled = !isBuiltIn,
            )
            Spacer(Modifier.height(8.dp))
            EnumPicker(
                label = "Weighting",
                values = Weighting.entries.toList(),
                selected = weighting,
                onPick = { weighting = it },
                labelOf = { it.uiLabel() },
                enabled = !isBuiltIn,
            )

            // ---- Save row ----
            Spacer(Modifier.height(20.dp))
            saveError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
                        if (!canSave) return@Button
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
                                    sourceLocked = !sourceEditable,
                                    sourceType = sourceType,
                                    selectedFolderIds = selectedFolderIds.toList(),
                                    smartKind = smartKind,
                                    smartLimit = smartLimitText.toIntOrNull() ?: 100,
                                    tagKey = tagKey.trim(),
                                    tagValues = tagValuesText.toCsvList(),
                                    tagMatchAll = tagMatchAll,
                                    pathIncludes = pathIncludesText.toCsvList(),
                                    pathExcludes = pathExcludesText.toCsvList(),
                                    playOrder = playOrder,
                                    weighting = weighting,
                                )
                            }.onSuccess { onClose() }
                                .onFailure { saveError = "Save failed: ${it.message}" }
                            saving = false
                        }
                    },
                    enabled = canSave,
                ) { Text(if (isEditing) "Save" else "Create") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private enum class SourceType(val label: String) {
    Folders("Folders"),
    SmartQuery("Smart Query"),
    TagFilter("Tag Filter"),
    PathPattern("Path Pattern"),
}

private suspend fun saveStation(
    repo: StationRepository,
    existing: Station?,
    name: String,
    subtitle: String?,
    color: Int,
    sourceLocked: Boolean,
    sourceType: SourceType,
    selectedFolderIds: List<Long>,
    smartKind: SmartQueryKind,
    smartLimit: Int,
    tagKey: String,
    tagValues: List<String>,
    tagMatchAll: Boolean,
    pathIncludes: List<String>,
    pathExcludes: List<String>,
    playOrder: PlayOrder,
    weighting: Weighting,
) {
    val source: SourceRule = when {
        sourceLocked && existing != null -> existing.source
        else -> when (sourceType) {
            SourceType.Folders -> SourceRule.FolderSelector(selectedFolderIds)
            SourceType.SmartQuery -> SourceRule.SmartQuery(smartKind, smartLimit.coerceAtLeast(1))
            SourceType.TagFilter -> SourceRule.TagFilter(tagKey, tagValues, tagMatchAll)
            SourceType.PathPattern -> SourceRule.PathPattern(pathIncludes, pathExcludes)
        }
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
private fun SourceTypeTabs(
    selected: SourceType,
    onSelect: (SourceType) -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SourceType.entries.forEach { type ->
            val isSelected = type == selected
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = enabled) { onSelect(type) },
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.3f else 0.15f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = type.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun FoldersConfig(
    folders: List<com.visorcraft.radioshed.domain.Folder>,
    selectedFolderIds: androidx.compose.runtime.snapshots.SnapshotStateList<Long>,
    enabled: Boolean,
) {
    if (folders.isEmpty()) {
        EmptyHint("No folders registered yet. Add one in Library Settings first.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        folders.forEach { folder ->
            FolderRow(
                folderName = folder.displayName,
                folderPath = folder.uri,
                checked = folder.id in selectedFolderIds,
                enabled = enabled,
                onToggle = {
                    if (!enabled) return@FolderRow
                    if (folder.id in selectedFolderIds) selectedFolderIds.remove(folder.id)
                    else selectedFolderIds += folder.id
                },
            )
        }
    }
}

@Composable
private fun SmartQueryConfig(
    kind: SmartQueryKind,
    onKind: (SmartQueryKind) -> Unit,
    limitText: String,
    onLimitText: (String) -> Unit,
    enabled: Boolean,
) {
    EnumPicker(
        label = "What to play",
        values = SmartQueryKind.entries.toList(),
        selected = kind,
        onPick = onKind,
        labelOf = { it.uiLabel() },
        enabled = enabled,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = limitText,
        onValueChange = { txt -> if (txt.all { it.isDigit() } && txt.length <= 6) onLimitText(txt) },
        label = { Text("Track limit") },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TagFilterConfig(
    key: String,
    onKey: (String) -> Unit,
    valuesText: String,
    onValuesText: (String) -> Unit,
    matchAll: Boolean,
    onMatchAll: (Boolean) -> Unit,
    enabled: Boolean,
) {
    Text(
        "Common keys: GENRE, ALBUM_ARTIST, MOOD, LANGUAGE, LABEL, GROUPING. Type any tag your files use.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = key,
        onValueChange = onKey,
        label = { Text("Tag key") },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = valuesText,
        onValueChange = onValuesText,
        label = { Text("Values (comma-separated)") },
        singleLine = false,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = matchAll,
            onCheckedChange = onMatchAll,
            enabled = enabled,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (matchAll) "Match ALL listed values" else "Match ANY listed value",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (matchAll) "Track must have every listed value for this tag"
                else "Track needs at least one of the listed values",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun PathPatternConfig(
    includesText: String,
    onIncludesText: (String) -> Unit,
    excludesText: String,
    onExcludesText: (String) -> Unit,
    enabled: Boolean,
) {
    Text(
        "Glob-style patterns. * matches any chars within a name; ** matches any directory depth. " +
            "Comma-separate multiple patterns.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = includesText,
        onValueChange = onIncludesText,
        label = { Text("Include patterns (e.g. /Music/Rock/**, *.flac)") },
        singleLine = false,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = excludesText,
        onValueChange = onExcludesText,
        label = { Text("Exclude patterns (optional)") },
        singleLine = false,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
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
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Text(folderName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
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

private fun SmartQueryKind.uiLabel(): String = when (this) {
    SmartQueryKind.NewestN -> "Newest tracks"
    SmartQueryKind.OldestN -> "Oldest tracks"
    SmartQueryKind.LeastPlayed -> "Least played"
    SmartQueryKind.MostPlayed -> "Most played"
    SmartQueryKind.HighestRated -> "Highest rated"
    SmartQueryKind.LongestUnheard -> "Longest unheard"
    SmartQueryKind.RecentlyAdded -> "Recently added"
    SmartQueryKind.RecentlyRated -> "Recently rated"
    SmartQueryKind.NeverPlayed -> "Never played"
    SmartQueryKind.AdaptiveMix -> "Adaptive mix (under-played + favorites)"
    SmartQueryKind.ForgottenGems -> "Forgotten gems (4★+ unheard 90 days)"
}

private fun String.toCsvList(): List<String> =
    split(',').map { it.trim() }.filter { it.isNotBlank() }

private const val BRAND_AMBER = 0xFFFF7F1A.toInt()
private val SWATCHES = listOf(
    BRAND_AMBER,
    0xFFE53935.toInt(),
    0xFFFB8C00.toInt(),
    0xFFFDD835.toInt(),
    0xFF43A047.toInt(),
    0xFF00ACC1.toInt(),
    0xFF1E88E5.toInt(),
    0xFF8E24AA.toInt(),
)
