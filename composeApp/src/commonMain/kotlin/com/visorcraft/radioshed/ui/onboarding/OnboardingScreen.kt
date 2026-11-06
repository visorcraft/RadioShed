package com.visorcraft.radioshed.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.data.FolderRepository
import com.visorcraft.radioshed.permissions.StoragePermissions
import com.visorcraft.radioshed.scanner.ScanProgress
import com.visorcraft.radioshed.scanner.Scanner
import com.visorcraft.radioshed.ui.library.FolderBrowserDialog
import com.visorcraft.radioshed.ui.util.OnResume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private enum class OnboardingStep {
    Welcome,
    Permissions,
    Folder,
    Scan,
    Done,
}

/**
 * First-launch flow. Lands the user from cold-start to having music
 * indexed: welcome → permissions → folder → initial scan → done.
 *
 * Steps that don't apply on a given platform (Permissions on iOS) are
 * skipped automatically. The flow is linear; the user can always go
 * back to the previous step except after Done.
 *
 * Layout breakpoints:
 *   - `< 600dp` (compact phones)  → single-column, hero centered
 *   - `600–840dp` (medium tablets) → single-column with bigger type
 *   - `≥ 840dp` (large tablets, car consoles) → two-pane: hero left,
 *     interactive content in a Card on the right. The first thing the
 *     user sees on a 1290dp head-unit screen looks intentional rather
 *     than a stretched phone layout.
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    folderRepo: FolderRepository = koinInject(),
    scanner: Scanner = koinInject(),
    permissions: StoragePermissions = koinInject(),
    presetSeeder: com.visorcraft.radioshed.stations.PresetSeeder = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val initiallyGranted = remember { permissions.hasAllFilesAccess() }
    var step by remember { mutableStateOf(OnboardingStep.Welcome) }
    var folderPath by remember {
        mutableStateOf(com.visorcraft.radioshed.source.defaultMusicFolderPath())
    }
    var folderName by remember { mutableStateOf("Music") }
    // Tracks whether the user has manually edited the display-name
    // field. Default name is "Music" (a real folder name on most
    // devices), so a naive `folderName.isBlank()` check would never
    // trigger and the picker's auto-fill from the chosen folder would
    // never run. With this flag, picking a folder updates the name
    // until the user types — after that we leave it alone.
    var folderNameEdited by remember { mutableStateOf(false) }
    var scanFolderId by remember { mutableStateOf<Long?>(null) }
    var scanStatus by remember { mutableStateOf<ScanProgress?>(null) }
    var permissionGranted by remember { mutableStateOf(initiallyGranted) }

    // Auto-recheck the permission whenever we come back from system Settings.
    // Without this the user has to manually tap "I granted it" — fine when the
    // permission system fires a result callback, but `MANAGE_EXTERNAL_STORAGE`
    // doesn't, so the only signal is the activity resuming.
    OnResume {
        if (!permissionGranted) {
            permissionGranted = permissions.hasAllFilesAccess()
        }
    }
    var addError by remember { mutableStateOf<String?>(null) }
    var scanJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isCompact = maxWidth < 600.dp
            val isWide = maxWidth >= 840.dp
            val horizontalPadding =
                when {
                    isWide -> 64.dp
                    isCompact -> 24.dp
                    else -> 40.dp
                }
            val verticalPadding = if (isCompact) 24.dp else 40.dp

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ProgressDots(
                    current = step.ordinal,
                    total = OnboardingStep.entries.size,
                )
                Spacer(Modifier.height(if (isCompact) 24.dp else 40.dp))

                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    when (step) {
                        OnboardingStep.Welcome -> WelcomeStep(isCompact = isCompact, isWide = isWide)

                        OnboardingStep.Permissions ->
                            PermissionsStep(
                                isWide = isWide,
                                granted = permissionGranted,
                                onRequest = { permissions.requestAllFilesAccess() },
                                onRefresh = { permissionGranted = permissions.hasAllFilesAccess() },
                            )

                        OnboardingStep.Folder ->
                            FolderStep(
                                isWide = isWide,
                                folderPath = folderPath,
                                folderName = folderName,
                                folderNameEdited = folderNameEdited,
                                onFolderPathChange = { folderPath = it },
                                onFolderNameChange = {
                                    folderName = it
                                    folderNameEdited = true
                                },
                                onAutoFillFolderName = { folderName = it },
                                error = addError,
                            )

                        OnboardingStep.Scan -> ScanStep(isWide = isWide, progress = scanStatus)

                        OnboardingStep.Done -> DoneStep(isWide = isWide, scanStatus = scanStatus)
                    }
                }

                Spacer(Modifier.height(16.dp))

                NavButtons(
                    step = step,
                    canProceed = canProceed(step, permissionGranted, folderPath, scanStatus),
                    onBack = {
                        if (step == OnboardingStep.Scan) {
                            scanJob?.cancel()
                            scanJob = null
                            scanStatus = null
                        }
                        step = previousStep(step, initiallyGranted)
                    },
                    onNext = {
                        when (step) {
                            OnboardingStep.Welcome -> {
                                step = nextStep(step, initiallyGranted)
                            }
                            OnboardingStep.Permissions -> {
                                step = nextStep(step, initiallyGranted)
                            }
                            OnboardingStep.Folder -> {
                                if (scanJob?.isActive == true) return@NavButtons
                                addError = null
                                step = OnboardingStep.Scan
                                scanJob =
                                    scope.launch {
                                        try {
                                            // Re-use the existing folder row when the
                                            // user comes back to this step after a
                                            // failed scan (or just bounces Back +
                                            // Scan-now). The folder.uri column has a
                                            // UNIQUE index, so a naked .insert() would
                                            // throw a SQLite constraint exception on
                                            // the second attempt and trap the user in
                                            // an unrecoverable error loop.
                                            val existing = folderRepo.getByUri(folderPath)
                                            val id =
                                                existing?.id ?: folderRepo.insert(
                                                    displayName =
                                                        folderName.ifBlank {
                                                            folderPath.substringAfterLast('/').ifBlank { folderPath }
                                                        },
                                                    uri = folderPath,
                                                    recursive = true,
                                                )
                                            scanFolderId = id
                                            scanner.scanFolder(id).collect { ev ->
                                                // Back-press during scan calls
                                                // scanJob.cancel() AND mutates `step`
                                                // directly; without this guard a
                                                // late-arriving Completed/Failed
                                                // would override the user's Back
                                                // and pop UI to Done.
                                                if (!isActive) return@collect
                                                scanStatus = ev
                                                if (ev is ScanProgress.Completed) {
                                                    runCatching { presetSeeder.seedIfEmpty() }
                                                    step = OnboardingStep.Done
                                                } else if (ev is ScanProgress.Failed) {
                                                    step = OnboardingStep.Done
                                                }
                                            }
                                        } catch (ce: CancellationException) {
                                            // User pressed Back / Skip — the
                                            // handlers already moved `step`. Don't
                                            // surface this as an error.
                                            throw ce
                                        } catch (t: Throwable) {
                                            addError = t.message ?: "Failed to add folder"
                                            step = OnboardingStep.Folder
                                        }
                                    }.also { it.invokeOnCompletion { scanJob = null } }
                            }
                            OnboardingStep.Scan -> {
                                // No-op — auto-advances on completion.
                            }
                            OnboardingStep.Done -> onComplete()
                        }
                    },
                    onSkip =
                        when (step) {
                            // Skipping permissions is fine — user can grant later from Library Settings.
                            OnboardingStep.Permissions -> {
                                { step = nextStep(step, initiallyGranted) }
                            }
                            // Skipping folder = no folders to scan, so jump straight
                            // to Done. Going to Scan would strand the user on
                            // "Starting…" forever because there's no scan to run.
                            OnboardingStep.Folder -> {
                                { step = OnboardingStep.Done }
                            }
                            // Welcome: returning users (re-installs, fresh
                            // device) usually don't want a re-tour — Skip
                            // tour drops them straight into the app. Library
                            // Manage covers the folder add later.
                            OnboardingStep.Welcome -> {
                                { onComplete() }
                            }
                            // Scan: for users who tapped Scan now and then
                            // realised they don't want to wait. Cancels the
                            // in-flight scan and exits onboarding —
                            // partial progress is harmless because the
                            // folder row is already inserted; a future
                            // Library Manage rescan picks up where this
                            // one left off.
                            OnboardingStep.Scan -> {
                                {
                                    scanJob?.cancel()
                                    scanJob = null
                                    onComplete()
                                }
                            }
                            else -> null
                        },
                )
            }
        }
    }
}

@Composable
private fun ProgressDots(
    current: Int,
    total: Int,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(total) { i ->
            val active = i <= current
            Box(
                modifier =
                    Modifier
                        .height(6.dp)
                        .widthIn(min = 24.dp)
                        .background(
                            if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f)
                            },
                            shape = RoundedCornerShape(3.dp),
                        ),
            )
        }
    }
}

/**
 * Two-pane scaffold used by every step in wide mode. Hero column on the
 * left holds title + supporting copy; the right pane is a Card the step
 * fills with its own interactive content.
 */
@Composable
private fun TwoPaneStep(
    title: String,
    subtitle: String?,
    body: String?,
    titleStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.displayMedium,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onBackground,
    rightContent: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).widthIn(max = 720.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = titleStyle,
                color = titleColor,
                fontWeight = FontWeight.Bold,
            )
            subtitle?.let {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            body?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                    modifier = Modifier.widthIn(max = 560.dp),
                )
            }
        }
        Spacer(Modifier.width(48.dp))
        Card(
            modifier = Modifier.weight(1f).widthIn(max = 520.dp).fillMaxHeight(0.7f),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                rightContent()
            }
        }
    }
}

@Composable
private fun WelcomeStep(
    isCompact: Boolean,
    isWide: Boolean,
) {
    if (isWide) {
        TwoPaneStep(
            title = "RadioShed",
            subtitle = "Your music turned into stations.",
            body = "No subscriptions. No internet required. We'll point at your music folders, scan them, and you build stations from there.",
            titleStyle = MaterialTheme.typography.displayLarge,
            titleColor = MaterialTheme.colorScheme.primary,
            rightContent = {
                Column(
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    FeatureRow(
                        icon = Icons.Filled.LibraryMusic,
                        title = "Stations from your library",
                        body = "Smart shuffle, weighted picks, custom rules — your music, programmed how you like it.",
                    )
                    FeatureRow(
                        icon = Icons.Filled.CloudOff,
                        title = "Works fully offline",
                        body = "No accounts, no telemetry. Internet is optional, never required.",
                    )
                    FeatureRow(
                        icon = Icons.Filled.DirectionsCar,
                        title = "Built for the car",
                        body = "Big-glyph transport, fluid layout, station-centric UI tuned for head-units.",
                    )
                }
            },
        )
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "RadioShed",
            style =
                if (isCompact) {
                    MaterialTheme.typography.displaySmall
                } else {
                    MaterialTheme.typography.displayMedium
                },
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Your music turned into stations.",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "No subscriptions. No internet required. We'll point at your music folders, scan them, and you build stations from there.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 480.dp),
        )
    }
}

@Composable
private fun FeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(36.dp).padding(end = 8.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun PermissionsStep(
    isWide: Boolean,
    granted: Boolean,
    onRequest: () -> Unit,
    onRefresh: () -> Unit,
) {
    if (isWide) {
        TwoPaneStep(
            title = "All Files Access",
            subtitle = null,
            body = "RadioShed needs broad file-read access to scan arbitrary folders fast — including your microSD card. We never modify or upload your files.",
            titleStyle = MaterialTheme.typography.displaySmall,
            titleColor = MaterialTheme.colorScheme.onBackground,
            rightContent = {
                if (granted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Granted",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Tap to open Android's file-access screen, then return here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                            Text("Open Settings")
                        }
                        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                            Text("I granted it")
                        }
                    }
                }
            },
        )
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "All Files Access",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "RadioShed needs broad file-read access to scan arbitrary folders fast — including your microSD card. We never modify or upload your files.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 480.dp),
        )
        Spacer(Modifier.height(24.dp))
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Granted",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRequest) { Text("Open Settings") }
                OutlinedButton(onClick = onRefresh) { Text("I granted it") }
            }
        }
    }
}

@Composable
private fun FolderStep(
    isWide: Boolean,
    folderPath: String,
    folderName: String,
    folderNameEdited: Boolean,
    onFolderPathChange: (String) -> Unit,
    onFolderNameChange: (String) -> Unit,
    onAutoFillFolderName: (String) -> Unit,
    error: String?,
) {
    var browserOpen by remember { mutableStateOf(false) }
    val bodyCopy =
        "Tap Browse to pick a folder, or type a path manually if you know it. " +
            "Most music libraries live in your Music folder."

    if (isWide) {
        TwoPaneStep(
            title = "Add a music folder",
            subtitle = null,
            body = bodyCopy,
            titleStyle = MaterialTheme.typography.displaySmall,
            titleColor = MaterialTheme.colorScheme.onBackground,
            rightContent = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = { browserOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Browse for folder…") }
                    OutlinedTextField(
                        value = folderPath,
                        onValueChange = onFolderPathChange,
                        label = { Text("Folder path") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = onFolderNameChange,
                        label = { Text("Display name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
        )
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Add a music folder",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                bodyCopy,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 480.dp),
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { browserOpen = true },
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
            ) { Text("Browse for folder…") }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = folderPath,
                onValueChange = onFolderPathChange,
                label = { Text("Folder path") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = folderName,
                onValueChange = onFolderNameChange,
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
            )
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (browserOpen) {
        // Start the browser at the parent of whatever's currently typed in,
        // so the user sees neighbors of the pre-fill (e.g. opens at
        // /storage/emulated/0 with /Music, /Pictures, /Download visible
        // as options).
        // parentPath is separator-agnostic — handles `/` AND `\` so a
        // Windows path like `C:\Music\Foo` opens the browser at
        // `C:\Music`. Falls back to the platform-aware default music
        // folder when the user hasn't typed anything resolvable yet.
        val startPath =
            com.visorcraft.radioshed.source.parentPath(folderPath)
                .ifBlank { com.visorcraft.radioshed.source.defaultMusicFolderPath() }
        FolderBrowserDialog(
            initialPath = startPath,
            onSelect = { picked ->
                onFolderPathChange(picked)
                // Auto-fill display name from picked dir, but only if
                // the user hasn't already customized it. Default name
                // is "Music" (a real folder name), so the prior
                // `isBlank` check never fired — `folderNameEdited`
                // tracks actual user typing instead. Skips the
                // edited-flag mutation by routing through
                // onAutoFillFolderName so picking a folder doesn't
                // mark the field as user-edited.
                if (!folderNameEdited || folderName.isBlank()) {
                    onAutoFillFolderName(picked.substringAfterLast('/').ifBlank { picked })
                }
                browserOpen = false
            },
            onDismiss = { browserOpen = false },
        )
    }
}

@Composable
private fun ScanStep(
    isWide: Boolean,
    progress: ScanProgress?,
) {
    // Two-line status: a "headline" with the count + state, and a
    // "subline" with the currently-scanning filename. The previous
    // single-line form ran a long full path off the right edge on
    // narrow screens AND hid the track count behind the basename.
    val (headline, subline) =
        when (val p = progress) {
            null -> "Starting…" to null
            is ScanProgress.Started -> "Walking the folder…" to null
            is ScanProgress.Progress -> {
                val count = "${p.tracksProcessed} track${if (p.tracksProcessed == 1) "" else "s"} scanned"
                count to baseFileName(p.currentFile)
            }
            is ScanProgress.Warning -> "Skipped" to p.message
            is ScanProgress.Completed -> "Done — ${p.totalTracks} tracks" to null
            is ScanProgress.Failed -> "Failed" to p.message
        }

    if (isWide) {
        TwoPaneStep(
            title = "Scanning…",
            subtitle = null,
            body = "Reading tags, building the library index. Big libraries can take a minute on the first pass; subsequent rescans only touch changed files.",
            titleStyle = MaterialTheme.typography.displaySmall,
            titleColor = MaterialTheme.colorScheme.onBackground,
            rightContent = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    LinearProgressIndicator(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 6.dp),
                    )
                    ScanStatusLines(
                        headline = headline,
                        subline = subline,
                    )
                }
            },
        )
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Scanning…",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 6.dp)
                    .widthIn(max = 480.dp),
        )
        Spacer(Modifier.height(12.dp))
        ScanStatusLines(
            headline = headline,
            subline = subline,
            modifier = Modifier.widthIn(max = 480.dp),
        )
    }
}

@Composable
private fun ScanStatusLines(
    headline: String,
    subline: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = headline,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subline != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Basename — last `/` or `\` segment. Falls back to the full string. */
private fun baseFileName(path: String): String {
    val sep = path.lastIndexOfAny(charArrayOf('/', '\\'))
    return if (sep >= 0 && sep < path.length - 1) path.substring(sep + 1) else path
}

@Composable
private fun DoneStep(
    isWide: Boolean,
    scanStatus: ScanProgress?,
) {
    val isFailed = scanStatus is ScanProgress.Failed
    val title = if (isFailed) "Setup incomplete" else "You're set up"
    val titleColor =
        if (isFailed) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        }
    val body =
        when (scanStatus) {
            is ScanProgress.Failed -> scanStatus.message
            is ScanProgress.Completed ->
                "Indexed ${scanStatus.totalTracks} tracks. Six built-in stations are seeded and ready to play; you can edit any of them or build your own."
            else -> "Setup complete."
        }

    if (isFailed) {
        // Failed setups skip the tour — keep the screen focused on the
        // recovery action.
        if (isWide) {
            TwoPaneStep(
                title = title,
                subtitle = null,
                body = body,
                titleStyle = MaterialTheme.typography.displayMedium,
                titleColor = titleColor,
                rightContent = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                            tint = titleColor,
                        )
                        Text(
                            text = "Tap Back to retry, or finish anyway and reconfigure later in Library.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                        )
                    }
                },
            )
            return
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = titleColor)
            Spacer(Modifier.height(16.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 480.dp),
            )
        }
        return
    }

    // Successful setup: render a 3-card mini-tour so the user knows what
    // to expect when they land on Stations Home. Replaces the previous
    // single-line "tap Open RadioShed" message with feature-discovery
    // copy that surfaces the three things newcomers ask about most:
    // built-in stations, Drive Mode, and the AI DJ teaser.
    val tourTitle =
        if (isWide) {
            MaterialTheme.typography.displayMedium
        } else {
            MaterialTheme.typography.headlineSmall
        }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = tourTitle, color = titleColor)
        Spacer(Modifier.height(12.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 560.dp),
        )
        Spacer(Modifier.height(20.dp))
        TourCard(
            icon = Icons.Filled.LibraryMusic,
            title = "Stations, not playlists",
            body =
                "Each tile on the Home grid is a generative station — Latest Hits, " +
                    "Top Rated, Forgotten Gems, or one you build yourself. Tap to play, " +
                    "long-press to edit.",
        )
        Spacer(Modifier.height(10.dp))
        TourCard(
            icon = Icons.Filled.DirectionsCar,
            title = "Drive Mode",
            body =
                "Settings → Accessibility → Drive Mode bumps every transport button " +
                    "by 25% so they're glance-safe in the car. Try it before your next drive.",
        )
        Spacer(Modifier.height(10.dp))
        TourCard(
            icon = Icons.Filled.CloudOff,
            title = "AI DJ — coming later",
            body =
                "Optional radio-style voice between tracks. Not in this build; once " +
                    "it ships you'll find it in Settings under each station's voice config.",
        )
    }
}

@Composable
private fun TourCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun NavButtons(
    step: OnboardingStep,
    canProceed: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (step != OnboardingStep.Welcome && step != OnboardingStep.Done) {
            TextButton(onClick = onBack) { Text("Back") }
        } else {
            Spacer(Modifier.widthIn(min = 1.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            onSkip?.let {
                val label =
                    when (step) {
                        OnboardingStep.Welcome, OnboardingStep.Scan -> "Skip tour"
                        else -> "Skip"
                    }
                TextButton(onClick = it) { Text(label) }
            }
            Button(onClick = onNext, enabled = canProceed) {
                Text(
                    when (step) {
                        OnboardingStep.Welcome -> "Get started"
                        OnboardingStep.Done -> "Open RadioShed"
                        OnboardingStep.Folder -> "Scan now"
                        OnboardingStep.Scan -> "Working…"
                        else -> "Next"
                    },
                )
            }
        }
    }
}

private fun canProceed(
    step: OnboardingStep,
    permissionGranted: Boolean,
    folderPath: String,
    scanStatus: ScanProgress?,
): Boolean =
    when (step) {
        OnboardingStep.Welcome -> true
        // Permissions step: the primary "Next" path is gated on the
        // user actually granting permission. The Skip button (separate)
        // is the explicit escape hatch that proceeds without it. Before
        // this gate, Next and Skip were functionally identical, which
        // was confusing and let users blow past the permission ask
        // without realising it was meaningful.
        OnboardingStep.Permissions -> permissionGranted
        OnboardingStep.Folder -> folderPath.isNotBlank()
        OnboardingStep.Scan -> false
        OnboardingStep.Done -> true
    }

private fun nextStep(
    current: OnboardingStep,
    skipPermissions: Boolean,
): OnboardingStep {
    val candidate = OnboardingStep.entries.getOrNull(current.ordinal + 1) ?: current
    return if (candidate == OnboardingStep.Permissions && skipPermissions) {
        OnboardingStep.entries.getOrNull(candidate.ordinal + 1) ?: candidate
    } else {
        candidate
    }
}

private fun previousStep(
    current: OnboardingStep,
    skipPermissions: Boolean,
): OnboardingStep {
    val candidate = OnboardingStep.entries.getOrNull(current.ordinal - 1) ?: current
    return if (candidate == OnboardingStep.Permissions && skipPermissions) {
        OnboardingStep.entries.getOrNull(candidate.ordinal - 1) ?: candidate
    } else {
        candidate
    }
}
