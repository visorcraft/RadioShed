package com.visorcraft.radioshed.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    folderRepo: FolderRepository = koinInject(),
    scanner: Scanner = koinInject(),
    permissions: StoragePermissions = koinInject(),
) {
    val scope = rememberCoroutineScope()
    // On iOS the StoragePermissions stub returns true at startup, so we
    // skip the Android-specific Permissions step entirely. This is the
    // "skipped automatically" behavior the doc-comment promises.
    val initiallyGranted = remember { permissions.hasAllFilesAccess() }
    // Always start on Welcome; the Permissions step is skipped during nav
    // when [initiallyGranted] is true (see nextStep / previousStep).
    var step by remember { mutableStateOf(OnboardingStep.Welcome) }
    var folderPath by remember { mutableStateOf("/storage/emulated/0/Music") }
    var folderName by remember { mutableStateOf("Music") }
    var scanFolderId by remember { mutableStateOf<Long?>(null) }
    var scanStatus by remember { mutableStateOf<ScanProgress?>(null) }
    var permissionGranted by remember { mutableStateOf(initiallyGranted) }
    var addError by remember { mutableStateOf<String?>(null) }
    // Track the in-flight scan so back navigation can cancel it cleanly,
    // and so a double-tap on "Scan now" can't fire two parallel inserts.
    var scanJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isCompact = maxWidth < 600.dp
            val horizontalPadding = if (isCompact) 24.dp else 64.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding, vertical = 32.dp),
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
                        OnboardingStep.Welcome -> WelcomeStep(isCompact = isCompact)

                        OnboardingStep.Permissions -> PermissionsStep(
                            granted = permissionGranted,
                            onRequest = { permissions.requestAllFilesAccess() },
                            onRefresh = { permissionGranted = permissions.hasAllFilesAccess() },
                        )

                        OnboardingStep.Folder -> FolderStep(
                            folderPath = folderPath,
                            folderName = folderName,
                            onFolderPathChange = { folderPath = it },
                            onFolderNameChange = { folderName = it },
                            error = addError,
                        )

                        OnboardingStep.Scan -> ScanStep(progress = scanStatus)

                        OnboardingStep.Done -> DoneStep(scanStatus = scanStatus)
                    }
                }

                Spacer(Modifier.height(16.dp))

                NavButtons(
                    step = step,
                    canProceed = canProceed(step, permissionGranted, folderPath, scanStatus),
                    onBack = {
                        // Cancel any in-flight scan when navigating away from
                        // the Scan step — otherwise a second scan started
                        // after going Back races with the first.
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
                                // Advance the step *first* so the button naturally disables
                                // (canProceed(Scan) returns false). Without this, a double-tap
                                // before the launch's first suspension fires two folder inserts
                                // and the second throws on the UNIQUE(uri) constraint.
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
                                            if (ev is ScanProgress.Completed || ev is ScanProgress.Failed) {
                                                step = OnboardingStep.Done
                                            }
                                        }
                                    }.onFailure {
                                        addError = it.message ?: "Failed to add folder"
                                        // Revert to Folder step so the user can fix the path
                                        // and retry rather than being stuck on Scan with no
                                        // progress events incoming.
                                        step = OnboardingStep.Folder
                                    }
                                }.also { it.invokeOnCompletion { scanJob = null } }
                            }
                            OnboardingStep.Scan -> {
                                // No-op — scan is in progress; auto-advances on completion.
                            }
                            OnboardingStep.Done -> onComplete()
                        }
                    },
                    onSkip = if (step == OnboardingStep.Folder || step == OnboardingStep.Permissions) {
                        { step = nextStep(step, initiallyGranted) }
                    } else null,
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

@Composable
private fun WelcomeStep(isCompact: Boolean) {
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
private fun PermissionsStep(granted: Boolean, onRequest: () -> Unit, onRefresh: () -> Unit) {
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
    folderPath: String,
    folderName: String,
    onFolderPathChange: (String) -> Unit,
    onFolderNameChange: (String) -> Unit,
    error: String?,
) {
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
private fun ScanStep(progress: ScanProgress?) {
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
            text = when (val p = progress) {
                null -> "Starting…"
                is ScanProgress.Started -> "Walking the folder…"
                is ScanProgress.Progress -> "${p.tracksProcessed} tracks · ${p.currentFile}"
                is ScanProgress.Warning -> "Skipped: ${p.message}"
                is ScanProgress.Completed -> "Done — ${p.totalTracks} tracks"
                is ScanProgress.Failed -> "Failed: ${p.message}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 480.dp),
        )
    }
}

@Composable
private fun DoneStep(scanStatus: ScanProgress?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (scanStatus is ScanProgress.Failed) "Setup incomplete" else "You're set up",
            style = MaterialTheme.typography.headlineSmall,
            color = if (scanStatus is ScanProgress.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = when (scanStatus) {
                is ScanProgress.Failed -> scanStatus.message
                is ScanProgress.Completed ->
                    "Indexed ${scanStatus.totalTracks} tracks. You can add more folders, build stations, and start listening."
                else -> "Setup complete."
            },
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
    // Allow proceeding even without permission — user can grant later from
    // Library Settings, scans will just return empty until they do.
    OnboardingStep.Permissions -> true
    OnboardingStep.Folder -> folderPath.isNotBlank()
    OnboardingStep.Scan -> false   // auto-advances when scan completes
    OnboardingStep.Done -> true
}

/**
 * Returns the next step, skipping [OnboardingStep.Permissions] when the
 * platform doesn't need it (i.e. iOS where access is per-folder via the
 * document picker, or Android where the user already granted before launch).
 */
private fun nextStep(current: OnboardingStep, skipPermissions: Boolean): OnboardingStep {
    val candidate = OnboardingStep.entries.getOrNull(current.ordinal + 1) ?: current
    return if (candidate == OnboardingStep.Permissions && skipPermissions) {
        OnboardingStep.entries.getOrNull(candidate.ordinal + 1) ?: candidate
    } else candidate
}

/** Mirror of [nextStep] for back navigation. */
private fun previousStep(current: OnboardingStep, skipPermissions: Boolean): OnboardingStep {
    val candidate = OnboardingStep.entries.getOrNull(current.ordinal - 1) ?: current
    return if (candidate == OnboardingStep.Permissions && skipPermissions) {
        OnboardingStep.entries.getOrNull(candidate.ordinal - 1) ?: candidate
    } else candidate
}
