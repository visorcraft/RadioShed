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
import com.visorcraft.radioshed.domain.Crossfade
import com.visorcraft.radioshed.domain.CrossfadeCurve
import com.visorcraft.radioshed.domain.PlayOrder
import com.visorcraft.radioshed.domain.PlaybackRule
import com.visorcraft.radioshed.domain.SmartQueryKind
import com.visorcraft.radioshed.domain.SourceRule
import com.visorcraft.radioshed.domain.Station
import com.visorcraft.radioshed.domain.Weighting
import com.visorcraft.radioshed.scanner.ScanProgress
import com.visorcraft.radioshed.scanner.Scanner
import com.visorcraft.radioshed.source.ArtworkPersister
import com.visorcraft.radioshed.source.MediaSourceRegistry
import com.visorcraft.radioshed.ui.library.FolderBrowserDialog
import com.visorcraft.radioshed.ui.util.rememberImagePicker
import com.visorcraft.radioshed.ui.util.supportsImagePicker
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Station Editor — create or edit a station. Supports five source-rule
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
 *  - Media Source: [SourceRule.MediaSourceSelector] choosing a registered
 *    remote/cloud source (e.g. Subsonic). Hidden when no sources are
 *    configured.
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
    stationId: Long?, // null = creating
    onClose: () -> Unit,
    folderRepo: FolderRepository = koinInject(),
    stationRepo: StationRepository = koinInject(),
    artworkPersister: ArtworkPersister = koinInject(),
    scanner: Scanner = koinInject(),
    mediaSourceRegistry: MediaSourceRegistry = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val folders by folderRepo.observeAll().collectAsState(initial = emptyList())
    val mediaSources by mediaSourceRegistry.sources.collectAsState()

    var existing by remember { mutableStateOf<Station?>(null) }
    var name by remember { mutableStateOf("") }
    var subtitle by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(BRAND_AMBER) }
    var artworkUri by remember { mutableStateOf<String?>(null) }
    var playOrder by remember { mutableStateOf(PlayOrder.SmartShuffle) }
    var weighting by remember { mutableStateOf(Weighting.Uniform) }
    var noRepeatDepth by remember { mutableStateOf(25) }
    var artistSpacing by remember { mutableStateOf(5) }
    var albumSpacing by remember { mutableStateOf(3) }
    // Crossfade. Stored as the three-way mode sentinel + the timed-mode
    // sub-fields so switching modes doesn't lose previously-entered values.
    var crossfadeMode by remember { mutableStateOf(CrossfadeMode.Smart) }
    var crossfadeSeconds by remember { mutableStateOf(6) }
    var crossfadeCurve by remember { mutableStateOf(CrossfadeCurve.EqualPower) }

    // Source-rule state. We hold per-type fields ALL the time; switching
    // types just changes which set is read on save. The other slots
    // preserve their values so toggling back doesn't lose them.
    var sourceType by remember { mutableStateOf(SourceType.Folders) }

    // Media source
    var mediaSourceId by remember { mutableStateOf<String?>(null) }

    // Folders
    val selectedFolderIds = remember { mutableStateListOf<Long>() }
    // Folder paths the user picked via the in-line browser that don't
    // exist in the library yet. Inserted into FolderRepository on save
    // (before the station write), then scanned and added to the
    // station's FolderSelector. Held separately from selectedFolderIds
    // so the editor can render them differently ("will be added") and
    // the save path knows which folders need a kick-off scan.
    val pendingNewFolderPaths = remember { mutableStateListOf<String>() }
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
    // Inline scan status published while the post-save kick-off scans
    // run. Mirrors OnboardingScreen / LibrarySettingsScreen progress
    // strings; null = no scan in flight (or all scans finished).
    var scanProgressLine by remember { mutableStateOf<String?>(null) }
    // True when the existing station's source rule isn't representable
    // in the editor (compound rules, unsupported selectors). We freeze
    // the source section in that case to avoid clobbering it.
    var sourceLockedAsCompound by remember { mutableStateOf(false) }

    LaunchedEffect(stationId) {
        if (stationId == null) return@LaunchedEffect
        // Wrap in runCatching so a corrupted source-JSON row (which
        // would otherwise throw out of decodeSource inside getById)
        // surfaces as a friendly inline error instead of crashing the
        // editor when the user taps Edit on a broken station.
        val station =
            runCatching { stationRepo.getById(stationId) }
                .onFailure { saveError = "Couldn't load station: ${it.message ?: it::class.simpleName}" }
                .getOrNull()
                ?: run {
                    if (saveError == null) saveError = "Station $stationId not found"
                    return@LaunchedEffect
                }
        existing = station
        name = station.name
        subtitle = station.customSubtitle.orEmpty()
        color = if (station.color == 0) BRAND_AMBER else station.color
        artworkUri = station.artworkUri
        playOrder = station.playback.order
        weighting = station.playback.weighting
        noRepeatDepth = station.playback.noRepeatDepth
        artistSpacing = station.playback.artistSpacing
        albumSpacing = station.playback.albumSpacing
        when (val cf = station.playback.crossfade) {
            is Crossfade.Off -> crossfadeMode = CrossfadeMode.Off
            is Crossfade.Fixed -> {
                crossfadeMode = CrossfadeMode.Timed
                crossfadeSeconds = cf.seconds.coerceIn(1, 12)
                crossfadeCurve = cf.curve
            }
            is Crossfade.Smart -> crossfadeMode = CrossfadeMode.Smart
        }
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
            is SourceRule.MediaSourceSelector -> {
                sourceType = SourceType.MediaSource
                mediaSourceId = src.mediaSourceId
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
    val canSave =
        name.isNotBlank() && !saving &&
            when {
                !sourceEditable -> true // cosmetic edit; source preserved as-is
                sourceType == SourceType.Folders ->
                    selectedFolderIds.isNotEmpty() || pendingNewFolderPaths.isNotEmpty()
                sourceType == SourceType.SmartQuery -> smartLimitText.toIntOrNull()?.let { it > 0 } == true
                sourceType == SourceType.TagFilter -> tagKey.isNotBlank() && tagValuesText.isNotBlank()
                sourceType == SourceType.PathPattern -> pathIncludesText.isNotBlank()
                sourceType == SourceType.MediaSource -> !mediaSourceId.isNullOrBlank()
                else -> true
            }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(if (isEditing) "Edit station" else "New station") },
                navigationIcon = {
                    // Disabled during a save-in-flight so a mid-scan
                    // cancel can't tear down [scope], strand a freshly-
                    // inserted folder in the DB without tracks, and
                    // leave the station referencing it. Once the
                    // scan-loop drains and [saving] flips false, the
                    // button re-enables and the editor closes normally.
                    TextButton(onClick = onClose, enabled = !saving) { Text("Cancel") }
                },
                colors =
                    androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier =
                Modifier
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

            // ---- Artwork (edit-only; new stations skip this section
            //               since there's no station id to attach the
            //               persisted artwork to until first save).
            if (isEditing && supportsImagePicker()) {
                Spacer(Modifier.height(16.dp))
                SectionHeader("Artwork")
                val pickImage =
                    rememberImagePicker { bytes ->
                        scope.launch {
                            val uri = artworkPersister.persist(bytes)
                            if (uri != null) {
                                artworkUri = uri
                                existing?.id?.let { stationRepo.setArtwork(it, uri) }
                            }
                        }
                    }
                ArtworkRow(
                    artworkUri = artworkUri,
                    onUpload = pickImage,
                    onClear = {
                        artworkUri = null
                        scope.launch {
                            existing?.id?.let { stationRepo.setArtwork(it, null) }
                        }
                    },
                )
            }

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
                val availableTypes =
                    remember(mediaSources) {
                        SourceType.entries.filter {
                            it != SourceType.MediaSource || mediaSources.isNotEmpty()
                        }
                    }
                SourceTypeTabs(
                    types = availableTypes,
                    selected = sourceType,
                    onSelect = { sourceType = it },
                    enabled = sourceEditable,
                )
                Spacer(Modifier.height(8.dp))
                when (sourceType) {
                    SourceType.Folders ->
                        FoldersConfig(
                            folders = folders,
                            selectedFolderIds = selectedFolderIds,
                            pendingNewFolderPaths = pendingNewFolderPaths,
                            enabled = sourceEditable,
                        )
                    SourceType.SmartQuery ->
                        SmartQueryConfig(
                            kind = smartKind,
                            onKind = { smartKind = it },
                            limitText = smartLimitText,
                            onLimitText = { smartLimitText = it },
                            enabled = sourceEditable,
                        )
                    SourceType.TagFilter ->
                        TagFilterConfig(
                            key = tagKey,
                            onKey = { tagKey = it },
                            valuesText = tagValuesText,
                            onValuesText = { tagValuesText = it },
                            matchAll = tagMatchAll,
                            onMatchAll = { tagMatchAll = it },
                            enabled = sourceEditable,
                        )
                    SourceType.PathPattern ->
                        PathPatternConfig(
                            includesText = pathIncludesText,
                            onIncludesText = { pathIncludesText = it },
                            excludesText = pathExcludesText,
                            onExcludesText = { pathExcludesText = it },
                            enabled = sourceEditable,
                        )
                    SourceType.MediaSource ->
                        MediaSourceConfig(
                            sources = mediaSources,
                            selectedId = mediaSourceId,
                            onSelect = { mediaSourceId = it },
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
            // Smart-shuffle policy sliders. These only matter for
            // non-Sequential play orders; we keep them visible
            // regardless so the user always sees the configured
            // values (debuggable / discoverable).
            Spacer(Modifier.height(12.dp))
            LabelledIntSlider(
                label = "No-repeat memory",
                description =
                    "Don't replay the last N picked tracks. " +
                        "Higher = more variety but smaller libraries can " +
                        "exhaust the pool and the queue starts re-using.",
                value = noRepeatDepth,
                range = 0..50,
                onChange = { noRepeatDepth = it },
                enabled = !isBuiltIn,
            )
            Spacer(Modifier.height(8.dp))
            LabelledIntSlider(
                label = "Album spacing",
                description =
                    "Skip same-album picks for this many tracks. " +
                        "Larger spacing avoids album-stuffing on small libraries.",
                value = albumSpacing,
                range = 0..10,
                onChange = { albumSpacing = it },
                enabled = !isBuiltIn,
            )
            Spacer(Modifier.height(8.dp))
            LabelledIntSlider(
                label = "Artist spacing",
                description =
                    "Skip same-artist picks for this many tracks. " +
                        "Smaller window than album spacing because users " +
                        "notice repeated artists faster.",
                value = artistSpacing,
                range = 0..15,
                onChange = { artistSpacing = it },
                enabled = !isBuiltIn,
            )
            Spacer(Modifier.height(12.dp))
            CrossfadeControl(
                mode = crossfadeMode,
                onMode = { crossfadeMode = it },
                seconds = crossfadeSeconds,
                onSeconds = { crossfadeSeconds = it },
                curve = crossfadeCurve,
                onCurve = { crossfadeCurve = it },
                enabled = !isBuiltIn,
            )

            // ---- Save row ----
            Spacer(Modifier.height(20.dp))
            saveError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }

            // Confirmation gate before destructive delete. Without this,
            // a user editing a station who tapped "Delete" by mistake
            // (it sits adjacent to the Save button) lost the station
            // and all its custom configuration immediately.
            var deleteConfirmOpen by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // `isEditing` already implies `stationId != null`, so the
                // explicit second check was redundant (Kotlin warned
                // "Condition is always 'true'"). Guard only on built-in
                // status here.
                if (isEditing && !isBuiltIn) {
                    OutlinedButton(
                        onClick = {
                            if (saving) return@OutlinedButton
                            deleteConfirmOpen = true
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
                                // Step 1: Insert any newly-picked folders
                                // BEFORE the station write so the station's
                                // FolderSelector can reference real ids.
                                // The collected id list is the union of
                                // already-existing ids + freshly-inserted
                                // ids. Existing pendingNewFolderPaths
                                // entries that turn out to already be in
                                // the library (race with another panel) get
                                // de-duped via folderRepo.getByUri.
                                val newlyInsertedIds = mutableListOf<Long>()
                                if (sourceType == SourceType.Folders) {
                                    for (path in pendingNewFolderPaths.toList()) {
                                        val existingRow = folderRepo.getByUri(path)
                                        val id =
                                            existingRow?.id ?: folderRepo.insert(
                                                displayName =
                                                    path.substringAfterLast('/')
                                                        .ifBlank {
                                                            path.substringAfterLast('\\').ifBlank { path }
                                                        },
                                                uri = path,
                                                recursive = true,
                                            )
                                        newlyInsertedIds += id
                                    }
                                }
                                val finalFolderIds =
                                    (selectedFolderIds.toList() + newlyInsertedIds)
                                        .distinct()

                                // Step 2: Write the station.
                                saveStation(
                                    repo = stationRepo,
                                    existing = existing,
                                    name = name.trim(),
                                    subtitle = subtitle.trim().ifBlank { null },
                                    color = color,
                                    artworkUri = artworkUri,
                                    sourceLocked = !sourceEditable,
                                    sourceType = sourceType,
                                    selectedFolderIds = finalFolderIds,
                                    smartKind = smartKind,
                                    smartLimit = smartLimitText.toIntOrNull() ?: 100,
                                    tagKey = tagKey.trim(),
                                    tagValues = tagValuesText.toCsvList(),
                                    tagMatchAll = tagMatchAll,
                                    pathIncludes = pathIncludesText.toCsvList(),
                                    pathExcludes = pathExcludesText.toCsvList(),
                                    mediaSourceId = mediaSourceId,
                                    playOrder = playOrder,
                                    weighting = weighting,
                                    noRepeatDepth = noRepeatDepth,
                                    artistSpacing = artistSpacing,
                                    albumSpacing = albumSpacing,
                                    crossfadeMode = crossfadeMode,
                                    crossfadeSeconds = crossfadeSeconds,
                                    crossfadeCurve = crossfadeCurve,
                                )

                                // Step 3: Kick off scans for the newly-
                                // inserted folders. Run sequentially —
                                // the scanner serializes through shared
                                // DB transactions, concurrent scans would
                                // contend. Progress updates publish to
                                // the inline status line; nothing throws
                                // out of this block (per-scan errors are
                                // swallowed into the status line so a
                                // single bad folder doesn't abort the
                                // close). The pending list is cleared
                                // AFTER scans drain so a mid-scan
                                // cancellation leaves the chips visible
                                // — the user can retry without re-
                                // picking everything from the browser.
                                if (newlyInsertedIds.isNotEmpty()) {
                                    val totalNew = newlyInsertedIds.size
                                    newlyInsertedIds.forEachIndexed { idx, id ->
                                        val folder = folderRepo.getById(id)
                                        val label = folder?.displayName ?: "folder"
                                        scanProgressLine = "Scanning $label " +
                                            "(${idx + 1}/$totalNew)…"
                                        runCatching {
                                            scanner.scanFolder(id).collect { ev ->
                                                if (ev is ScanProgress.Progress) {
                                                    scanProgressLine = "Scanning $label " +
                                                        "(${idx + 1}/$totalNew): " +
                                                        "${ev.tracksProcessed} tracks"
                                                } else if (ev is ScanProgress.Failed) {
                                                    scanProgressLine = "Scan failed for " +
                                                        "$label: ${ev.message}"
                                                }
                                            }
                                        }.onFailure {
                                            scanProgressLine = "Scan crashed for $label: " +
                                                (it.message ?: it::class.simpleName ?: "unknown")
                                        }
                                    }
                                    scanProgressLine = "Scanned $totalNew new folder" +
                                        if (totalNew == 1) "" else "s"
                                }
                                // Drained without cancellation — safe to
                                // drop the pending-chips state now.
                                pendingNewFolderPaths.clear()
                            }.onSuccess { onClose() }
                                .onFailure { saveError = "Save failed: ${it.message}" }
                            saving = false
                        }
                    },
                    enabled = canSave,
                ) {
                    Text(
                        when {
                            saving && scanProgressLine != null -> "Scanning…"
                            saving -> "Saving…"
                            isEditing -> "Save"
                            else -> "Create"
                        },
                    )
                }
            }
            // Inline scan-progress strip. Mirrors the
            // OnboardingScreen / LibrarySettingsScreen pattern — single
            // line, monospace, primary tint so the user sees that the
            // editor is still working on a kick-off scan before the
            // window closes.
            scanProgressLine?.let { line ->
                Spacer(Modifier.height(8.dp))
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(16.dp))

            if (deleteConfirmOpen && stationId != null) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { deleteConfirmOpen = false },
                    title = { Text("Delete station?") },
                    text = {
                        Text(
                            "\"${name.trim().ifBlank { "this station" }}\" will be removed. " +
                                "Tracks themselves stay in your library and can still be " +
                                "added to other stations.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            deleteConfirmOpen = false
                            scope.launch {
                                runCatching { stationRepo.delete(stationId) }
                                    .onSuccess { onClose() }
                                    .onFailure { saveError = "Delete failed: ${it.message}" }
                            }
                        }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { deleteConfirmOpen = false }) { Text("Cancel") }
                    },
                )
            }
        }
    }
}

private enum class SourceType(val label: String) {
    Folders("Folders"),
    SmartQuery("Smart Query"),
    TagFilter("Tag Filter"),
    PathPattern("Path Pattern"),
    MediaSource("Media Source"),
}

/** Three-way UI sentinel for the crossfade picker. Maps to [Crossfade] sealed subtypes on save. */
private enum class CrossfadeMode(val label: String) {
    Off("Off"),
    Timed("Timed"),
    Smart("Smart"),
}

private suspend fun saveStation(
    repo: StationRepository,
    existing: Station?,
    name: String,
    subtitle: String?,
    color: Int,
    artworkUri: String?,
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
    mediaSourceId: String?,
    playOrder: PlayOrder,
    weighting: Weighting,
    noRepeatDepth: Int,
    artistSpacing: Int,
    albumSpacing: Int,
    crossfadeMode: CrossfadeMode,
    crossfadeSeconds: Int,
    crossfadeCurve: CrossfadeCurve,
) {
    val source: SourceRule =
        when {
            sourceLocked && existing != null -> existing.source
            else ->
                when (sourceType) {
                    SourceType.Folders -> SourceRule.FolderSelector(selectedFolderIds)
                    SourceType.SmartQuery -> SourceRule.SmartQuery(smartKind, smartLimit.coerceAtLeast(1))
                    SourceType.TagFilter -> SourceRule.TagFilter(tagKey, tagValues, tagMatchAll)
                    SourceType.PathPattern -> SourceRule.PathPattern(pathIncludes, pathExcludes)
                    SourceType.MediaSource ->
                        SourceRule.MediaSourceSelector(
                            mediaSourceId = mediaSourceId.orEmpty(),
                        )
                }
        }
    val crossfade: Crossfade =
        when (crossfadeMode) {
            CrossfadeMode.Off -> Crossfade.Off
            CrossfadeMode.Timed ->
                Crossfade.Fixed(
                    seconds = crossfadeSeconds.coerceIn(1, 12),
                    curve = crossfadeCurve,
                )
            CrossfadeMode.Smart -> Crossfade.Smart
        }
    val playback =
        (existing?.playback ?: PlaybackRule()).copy(
            order = playOrder,
            weighting = weighting,
            noRepeatDepth = noRepeatDepth.coerceIn(0, 50),
            artistSpacing = artistSpacing.coerceIn(0, 15),
            albumSpacing = albumSpacing.coerceIn(0, 10),
            crossfade = crossfade,
        )
    if (existing == null) {
        // Insert path doesn't currently expose artworkUri; the upload
        // button is hidden for new stations (see UI). If we ever want
        // to support upload-during-create we'd thread artworkUri here
        // and into the underlying insert query.
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
            artworkUri = artworkUri,
            customSubtitle = subtitle,
        )
    }
}

@Composable
private fun SourceTypeTabs(
    types: List<SourceType>,
    selected: SourceType,
    onSelect: (SourceType) -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        types.forEach { type ->
            val isSelected = type == selected
            Surface(
                modifier =
                    Modifier
                        .weight(1f)
                        .clickable(enabled = enabled) { onSelect(type) },
                color =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.3f else 0.15f)
                    },
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = type.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color =
                        if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
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
    pendingNewFolderPaths: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
    enabled: Boolean,
) {
    var browserOpen by remember { mutableStateOf(false) }
    val foldersByUri = remember(folders) { folders.associateBy { it.uri } }
    val foldersById = remember(folders) { folders.associateBy { it.id } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selectedFolderIds.isEmpty() && pendingNewFolderPaths.isEmpty()) {
            EmptyHint(
                "No folders selected. Tap \"Add folder\" to browse for one. " +
                    "Folders you pick that aren't in your Library yet will be " +
                    "added and scanned automatically when you save.",
            )
        } else {
            // Existing-library folder chips. Filter through foldersById so
            // a stale id (e.g. folder deleted in Library Settings while
            // the editor was open) doesn't render an empty chip.
            selectedFolderIds.forEach { id ->
                val folder = foldersById[id] ?: return@forEach
                SelectedFolderChip(
                    folderName = folder.displayName,
                    folderPath = folder.uri,
                    pending = false,
                    enabled = enabled,
                    onRemove = { if (enabled) selectedFolderIds.remove(id) },
                )
            }
            // Newly-picked folder paths that aren't in the library yet.
            // Rendered with a distinct "will be added" affordance so
            // the user knows the save is about to do extra work.
            pendingNewFolderPaths.forEach { path ->
                SelectedFolderChip(
                    folderName =
                        path.substringAfterLast('/').ifBlank { path }
                            .substringAfterLast('\\').ifBlank { path },
                    folderPath = path,
                    pending = true,
                    enabled = enabled,
                    onRemove = { if (enabled) pendingNewFolderPaths.remove(path) },
                )
            }
        }

        OutlinedButton(
            onClick = { if (enabled) browserOpen = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add folder…") }
    }

    if (browserOpen) {
        // Default landing path: the parent of the first already-selected
        // folder if any, else any registered library folder, else the
        // platform default. Lets the user keep browsing in the same
        // tree they last selected from without having to walk back up
        // from the platform root every time.
        val startPath =
            remember(folders, selectedFolderIds) {
                val firstSelected = selectedFolderIds.firstNotNullOfOrNull { foldersById[it]?.uri }
                val anyFolder = folders.firstOrNull()?.uri
                (firstSelected ?: anyFolder)?.let {
                    com.visorcraft.radioshed.source.parentPath(it).ifBlank { it }
                } ?: com.visorcraft.radioshed.source.defaultMusicFolderPath()
            }
        FolderBrowserDialog(
            initialPath = startPath,
            onSelect = { picked ->
                browserOpen = false
                if (picked.isBlank()) return@FolderBrowserDialog
                val existing = foldersByUri[picked]
                when {
                    // Already an existing library folder → just select.
                    existing != null -> {
                        if (existing.id !in selectedFolderIds) {
                            selectedFolderIds += existing.id
                        }
                    }
                    // Already queued as pending → no-op (avoid dupes).
                    picked in pendingNewFolderPaths -> Unit
                    else -> pendingNewFolderPaths += picked
                }
            },
            onDismiss = { browserOpen = false },
        )
    }
}

@Composable
private fun SelectedFolderChip(
    folderName: String,
    folderPath: String,
    pending: Boolean,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    // Surface tint differs between pending and existing folders so the
    // user can tell at a glance which selections will trigger a scan
    // on save. Pending uses the primary container; existing uses the
    // neutral surface variant.
    val container =
        if (pending) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        }
    val onContainer =
        if (pending) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = container,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    folderName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = onContainer,
                )
                Text(
                    folderPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                )
                if (pending) {
                    Text(
                        "Will be added to your Library and scanned on save",
                        style = MaterialTheme.typography.labelSmall,
                        color = onContainer.copy(alpha = 0.85f),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onRemove,
                enabled = enabled,
            ) { Text("Remove", color = onContainer) }
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
    // Stable preset list. ALBUM_ARTIST / ARTIST are first-class even
    // though they're stored in the artist table (not track_tag) —
    // SourceRuleEvaluator special-cases them so the user-facing rule
    // works the way users expect ("filter to tracks by these artists").
    val presets = remember { COMMON_TAG_KEYS }
    val isPreset = remember(key) { key in presets }
    // "Custom" mode is sticky: once the user enters a non-preset key
    // (either by selecting Custom from the dropdown OR by loading a
    // station whose existing key isn't a preset) we keep the text
    // field visible until they pick a preset again.
    var customMode by remember(key) { mutableStateOf(!isPreset && key.isNotBlank()) }
    val effectiveSelection =
        when {
            customMode -> CUSTOM_SENTINEL
            isPreset -> key
            else -> presets.first()
        }

    Text(
        "Filters tracks by a metadata tag. ARTIST / ALBUM_ARTIST match by " +
            "name; other keys match the value as stored in your files.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
    Spacer(Modifier.height(6.dp))
    TagKeyDropdown(
        selected = effectiveSelection,
        options = presets,
        enabled = enabled,
        onPick = { picked ->
            if (picked == CUSTOM_SENTINEL) {
                customMode = true
                // Don't blow away an existing custom key when the user
                // re-opens the dropdown and picks Custom again.
                if (key in presets) onKey("")
            } else {
                customMode = false
                onKey(picked)
            }
        },
    )
    if (customMode) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = key,
            onValueChange = onKey,
            label = { Text("Custom tag key") },
            placeholder = { Text("e.g. STYLE, REMIXER, CATALOGNUMBER") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
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
    // Two-button segmented selector replaces the previous single
    // Switch. The Switch was the trap behind the user-reported
    // "BLACKPINK, Jennie" station that returned zero tracks: with
    // a single labelled switch, the active mode is the one NOT in
    // the label, which inverts the obvious mental model. Filled vs
    // outlined chips here keep both labels visible and the active
    // state obviously primary-coloured.
    MatchModeSelector(
        matchAll = matchAll,
        onPick = onMatchAll,
        enabled = enabled,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        if (matchAll) {
            "Track must have every listed value for this tag."
        } else {
            "Track needs at least one of the listed values."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )
    // Surface a soft warning when the user picks ALL with multiple
    // values on an artist key. A track is linked to at most one
    // ALBUM_ARTIST and one primary ARTIST, so the intersection
    // of >1 distinct values is empty by construction — the same
    // trap the original station fell into. Quiet hint, not a block;
    // the user might genuinely want this with a single value.
    val isArtistKey =
        key.equals("ALBUM_ARTIST", ignoreCase = true) ||
            key.equals("ARTIST", ignoreCase = true)
    val multipleNonBlankValues =
        valuesText.split(',')
            .map { it.trim() }
            .count { it.isNotEmpty() } >= 2
    if (matchAll && isArtistKey && multipleNonBlankValues) {
        Spacer(Modifier.height(6.dp))
        Text(
            "Heads up: a track has only one ${key.lowercase()} at a time, " +
                "so \"All\" with multiple values almost always matches zero tracks. " +
                "Switch to \"Any\" unless you're sure.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * Match-mode segmented selector. Two equal-weight Surface buttons
 * — "Any" on the left, "All" on the right. The active option fills
 * with primary tint so the user can see which mode is committed at
 * a glance, no parsing of a separate label required.
 */
@Composable
private fun MatchModeSelector(
    matchAll: Boolean,
    onPick: (Boolean) -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MatchModeOption(
            label = "Match ANY",
            selected = !matchAll,
            enabled = enabled,
            onClick = { onPick(false) },
            modifier = Modifier.weight(1f),
        )
        MatchModeOption(
            label = "Match ALL",
            selected = matchAll,
            enabled = enabled,
            onClick = { onPick(true) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MatchModeOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        color =
            if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(
                    alpha = if (enabled) 0.4f else 0.2f,
                )
            },
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun TagKeyDropdown(
    selected: String,
    options: List<String>,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(
            "Tag key",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = { if (enabled) open = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (selected == CUSTOM_SENTINEL) "Custom…" else selected)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onPick(option)
                        open = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Custom…") },
                onClick = {
                    onPick(CUSTOM_SENTINEL)
                    open = false
                },
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
private fun MediaSourceConfig(
    sources: Map<String, com.visorcraft.radioshed.source.MediaSource>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    enabled: Boolean,
) {
    if (sources.isEmpty()) {
        EmptyHint(
            "No media sources are configured. Add a Subsonic server in Settings to enable cloud tracks.",
        )
        return
    }
    val selectedEntry = sources[selectedId]
    val displayLabel =
        when {
            selectedId == null -> "Choose a source…"
            selectedEntry != null -> selectedEntry.displayName
            else -> "$selectedId (unavailable)"
        }
    Text(
        "Play tracks from a registered cloud or remote source.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
    Spacer(Modifier.height(6.dp))
    var open by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { if (enabled) open = true },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(displayLabel)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        sources.values.forEach { source ->
            DropdownMenuItem(
                text = { Text(source.displayName) },
                onClick = {
                    onSelect(source.id)
                    open = false
                },
            )
        }
    }
}

/**
 * Slim labelled integer slider. Range supplied by the caller; the slider
 * snaps to integers via the `steps` parameter so values are always whole
 * numbers. Used for shuffle-spacing knobs and crossfade duration alike.
 */
@Composable
private fun LabelledIntSlider(
    label: String,
    description: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    enabled: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (value == 0) "Off" else "$value",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
        androidx.compose.material3.Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Three-way crossfade picker: Off / Timed / Smart. Timed expands to a
 * duration slider (1–12 s) and a curve selector. Smart shows an honest
 * caption noting that content-aware analysis is planned.
 */
@Composable
private fun CrossfadeControl(
    mode: CrossfadeMode,
    onMode: (CrossfadeMode) -> Unit,
    seconds: Int,
    onSeconds: (Int) -> Unit,
    curve: CrossfadeCurve,
    onCurve: (CrossfadeCurve) -> Unit,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Mode row — three equal-weight Surface buttons mirroring MatchModeSelector.
        Text(
            "Crossfade",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CrossfadeMode.entries.forEach { entry ->
                val selected = entry == mode
                Surface(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clickable(enabled = enabled) { onMode(entry) },
                    color =
                        if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(
                                alpha = if (enabled) 0.4f else 0.2f,
                            )
                        },
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color =
                            if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        when (mode) {
            CrossfadeMode.Off -> Unit
            CrossfadeMode.Timed -> {
                Spacer(Modifier.height(10.dp))
                LabelledIntSlider(
                    label = "Crossfade duration",
                    description = "Overlap the outgoing and incoming tracks for this many seconds.",
                    value = seconds,
                    range = 1..12,
                    onChange = onSeconds,
                    enabled = enabled,
                )
                Spacer(Modifier.height(8.dp))
                EnumPicker(
                    label = "Curve",
                    values = CrossfadeCurve.entries.toList(),
                    selected = curve,
                    onPick = onCurve,
                    labelOf = { it.uiLabel() },
                    enabled = enabled,
                )
            }
            CrossfadeMode.Smart -> {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Currently equals Timed with a 6-second fade; " +
                        "content-aware fades are planned.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        }
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

/**
 * Custom-artwork management row. Shows the currently-selected
 * artwork (or a "no artwork" placeholder) alongside Upload and
 * (when set) Clear actions. Persists to disk + the station row
 * synchronously on pick so the change shows up on Stations Home
 * even if the user backs out before tapping Save.
 */
@Composable
private fun ArtworkRow(
    artworkUri: String?,
    onUpload: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center,
        ) {
            if (artworkUri != null) {
                coil3.compose.AsyncImage(
                    model = artworkUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            } else {
                Text(
                    "♫",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            OutlinedButton(onClick = onUpload, modifier = Modifier.fillMaxWidth()) {
                Text(if (artworkUri == null) "Upload artwork" else "Change artwork")
            }
            if (artworkUri != null) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Remove artwork",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Falls back to a 4-cover collage of your most-played albums " +
                        "in this station, or the preset gradient if there's no history yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun ColorSwatchRow(
    selected: Int,
    onPick: (Int) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SWATCHES.forEach { swatch ->
            val isSelected = swatch == selected
            Box(
                modifier =
                    Modifier
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

private fun PlayOrder.uiLabel(): String =
    when (this) {
        PlayOrder.Shuffle -> "Shuffle"
        PlayOrder.Sequential -> "Sequential"
        PlayOrder.SmartShuffle -> "Smart shuffle (no-repeat memory)"
        PlayOrder.WeightedRandom -> "Weighted random"
    }

private fun Weighting.uiLabel(): String =
    when (this) {
        Weighting.Uniform -> "Uniform"
        Weighting.FavorRecent -> "Favor recently added"
        Weighting.FavorUnderPlayed -> "Favor under-played"
        Weighting.FavorHigherRated -> "Favor higher-rated"
    }

private fun CrossfadeCurve.uiLabel(): String =
    when (this) {
        CrossfadeCurve.EqualPower -> "Equal power"
        CrossfadeCurve.Linear -> "Linear"
        CrossfadeCurve.SCurve -> "S-curve"
    }

private fun SmartQueryKind.uiLabel(): String =
    when (this) {
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
        SmartQueryKind.ForgottenGems -> "Forgotten gems — longest unheard"
        SmartQueryKind.ForgottenGemsByRating -> "Forgotten gems — highest rated"
        SmartQueryKind.LikedSongs -> "Liked songs (hearted tracks)"
    }

private fun String.toCsvList(): List<String> = split(',').map { it.trim() }.filter { it.isNotBlank() }

/**
 * Tag keys offered as one-tap options in the Tag Filter editor.
 * Order = roughly "most useful first" for the typical user, not
 * alphabetical. Selecting "Custom…" instead exposes a free-text
 * field for any tag the user's files happen to carry (e.g. STYLE,
 * CATALOGNUMBER, SCRIPT, ORIGINAL_RELEASE_DATE).
 *
 * ARTIST and ALBUM_ARTIST are presented as tag keys even though
 * they aren't stored in track_tag — SourceRuleEvaluator special-
 * cases both and routes them through the artist table so the
 * user-facing semantics ("filter by these artists") work.
 */
private val COMMON_TAG_KEYS =
    listOf(
        "GENRE",
        "ALBUM_ARTIST",
        "ARTIST",
        "MOOD",
        "LANGUAGE",
        "LABEL",
        "GROUPING",
        "COMPOSER",
        "YEAR",
        "COMMENT",
    )

/**
 * Sentinel that means "the user picked Custom from the tag-key
 * dropdown". Not a real tag key — used only inside the editor UI
 * to flip Custom mode on and reveal the free-text field. The
 * actual saved key comes from the OutlinedTextField in custom
 * mode (never this sentinel).
 */
private const val CUSTOM_SENTINEL = "__custom__"

private const val BRAND_AMBER = 0xFFFF7F1A.toInt()
private val SWATCHES =
    listOf(
        BRAND_AMBER,
        0xFFE53935.toInt(),
        0xFFFB8C00.toInt(),
        0xFFFDD835.toInt(),
        0xFF43A047.toInt(),
        0xFF00ACC1.toInt(),
        0xFF1E88E5.toInt(),
        0xFF8E24AA.toInt(),
    )
