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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.data.FolderRepository
import com.visorcraft.radioshed.permissions.StoragePermissions
import com.visorcraft.radioshed.scanner.ScanProgress
import com.visorcraft.radioshed.scanner.Scanner
import com.visorcraft.radioshed.ui.util.OnResume
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private enum class OnboardingStep {
    Welcome, Permissions, Folder, Scan, Done
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
    var folderPath by remember { mutableStateOf("/storage/emulated/0/Music") }
    var folderName by remember { mutableStateOf("Music") }
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
            val horizontalPadding = when {
                isWide -> 64.dp
                isCompact -> 24.dp
                else -> 40.dp
            }
            val verticalPadding = if (isCompact) 24.dp else 40.dp

            Column(
                modifier = Modifier
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

                        OnboardingStep.Permissions -> PermissionsStep(
                            isWide = isWide,
                            granted = permissionGranted,
                            onRequest = { permissions.requestAllFilesAccess() },
                            onRefresh = { permissionGranted = permissions.hasAllFilesAccess() },
                        )

                        OnboardingStep.Folder -> FolderStep(
                            isWide = isWide,
                            folderPath = folderPath,
                            folderName = folderName,
                            onFolderPathChange = { folderPath = it },
                            onFolderNameChange = { folderName = it },
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
                                scanJob = scope.launch {
                                    runCatching {
                                        val id = folderRepo.insert(
                                            displayName = folderName.ifBlank {
                                                folderPath.substringAfterLast('/').ifBlank { folderPath }
                                            },
                                            uri = folderPath,
                                            recursive = true,
                                        )
                                        scanFolderId = id
                                        scanner.scanFolder(id).collect { ev ->
                                            scanStatus = ev
                                            if (ev is ScanProgress.Completed) {
                                                runCatching { presetSeeder.seedIfEmpty() }
                                                step = OnboardingStep.Done
                                            } else if (ev is ScanProgress.Failed) {
                                                step = OnboardingStep.Done
                                            }
                                        }
                                    }.onFailure {
                                        addError = it.message ?: "Failed to add folder"
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
                    onSkip = when (step) {
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
                        else -> null
                    },
                )
            }
        }
    }
}

@Composable
private fun ProgressDots(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(total) { i ->
            val active = i <= current
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .widthIn(min = 24.dp)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f),
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
            colors = CardDefaults.cardColors(
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
private fun WelcomeStep(isCompact: Boolean, isWide: Boolean) {
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
                    FeatureRow(glyph = "♫", title = "Stations from your library",
                        body = "Smart shuffle, weighted picks, custom rules — your music, programmed how you like it.")
                    FeatureRow(glyph = "✈", title = "Works fully offline",
                        body = "No accounts, no telemetry. Internet is optional, never required.")
                    FeatureRow(glyph = "▣", title = "Built for the car",
                        body = "Big-glyph transport, fluid layout, station-centric UI tuned for head-units.")
                }
            },
        )
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "RadioShed",
            style = if (isCompact) MaterialTheme.typography.displaySmall
                    else MaterialTheme.typography.displayMedium,
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
private fun FeatureRow(glyph: String, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(48.dp),
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
                    Text(
                        "Granted ✓",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
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
            Text(
                "Granted ✓",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
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
    onFolderPathChange: (String) -> Unit,
    onFolderNameChange: (String) -> Unit,
    error: String?,
) {
    if (isWide) {
        TwoPaneStep(
            title = "Add a music folder",
            subtitle = null,
            body = "Type a path to a folder containing your music. /storage/emulated/0/Music is a common default. (A native folder picker lands in a follow-up.)",
            titleStyle = MaterialTheme.typography.displaySmall,
            titleColor = MaterialTheme.colorScheme.onBackground,
            rightContent = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
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
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Add a music folder",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Type a path to a folder containing your music. /storage/emulated/0/Music is a common default. (Folder picker UI lands in a follow-up.)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 480.dp),
        )
        Spacer(Modifier.height(24.dp))
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

@Composable
private fun ScanStep(isWide: Boolean, progress: ScanProgress?) {
    val statusText = when (val p = progress) {
        null -> "Starting…"
        is ScanProgress.Started -> "Walking the folder…"
        is ScanProgress.Progress -> "${p.tracksProcessed} tracks · ${p.currentFile}"
        is ScanProgress.Warning -> "Skipped: ${p.message}"
        is ScanProgress.Completed -> "Done — ${p.totalTracks} tracks"
        is ScanProgress.Failed -> "Failed: ${p.message}"
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
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 6.dp),
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
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
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 6.dp)
                .widthIn(max = 480.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 480.dp),
        )
    }
}

@Composable
private fun DoneStep(isWide: Boolean, scanStatus: ScanProgress?) {
    val isFailed = scanStatus is ScanProgress.Failed
    val title = if (isFailed) "Setup incomplete" else "You're set up"
    val titleColor = if (isFailed) MaterialTheme.colorScheme.error
                     else MaterialTheme.colorScheme.primary
    val body = when (scanStatus) {
        is ScanProgress.Failed -> scanStatus.message
        is ScanProgress.Completed ->
            "Indexed ${scanStatus.totalTracks} tracks. You can add more folders, build stations, and start listening."
        else -> "Setup complete."
    }

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
                    Text(
                        text = if (isFailed) "✕" else "✓",
                        style = MaterialTheme.typography.displayLarge,
                        color = titleColor,
                    )
                    Text(
                        text = if (isFailed) "Tap Back to retry, or finish anyway and reconfigure later in Library."
                               else "Tap Open RadioShed to head to the Stations home — built-in presets are seeded and ready to play.",
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
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = titleColor,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 480.dp),
        )
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
            onSkip?.let { TextButton(onClick = it) { Text("Skip") } }
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
): Boolean = when (step) {
    OnboardingStep.Welcome -> true
    OnboardingStep.Permissions -> true
    OnboardingStep.Folder -> folderPath.isNotBlank()
    OnboardingStep.Scan -> false
    OnboardingStep.Done -> true
}

private fun nextStep(current: OnboardingStep, skipPermissions: Boolean): OnboardingStep {
    val candidate = OnboardingStep.entries.getOrNull(current.ordinal + 1) ?: current
    return if (candidate == OnboardingStep.Permissions && skipPermissions) {
        OnboardingStep.entries.getOrNull(candidate.ordinal + 1) ?: candidate
    } else candidate
}

private fun previousStep(current: OnboardingStep, skipPermissions: Boolean): OnboardingStep {
    val candidate = OnboardingStep.entries.getOrNull(current.ordinal - 1) ?: current
    return if (candidate == OnboardingStep.Permissions && skipPermissions) {
        OnboardingStep.entries.getOrNull(candidate.ordinal - 1) ?: candidate
    } else candidate
}
