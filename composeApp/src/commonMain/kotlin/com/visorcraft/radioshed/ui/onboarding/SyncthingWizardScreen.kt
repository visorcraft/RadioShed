package com.visorcraft.radioshed.ui.onboarding

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.data.FolderRepository
import com.visorcraft.radioshed.scanner.ScanProgress
import com.visorcraft.radioshed.scanner.Scanner
import com.visorcraft.radioshed.source.SYNCTHING_DESKTOP_URL
import com.visorcraft.radioshed.source.SYNCTHING_FORK_FDROID_URL
import com.visorcraft.radioshed.source.SYNCTHING_FORK_PLAY_URL
import com.visorcraft.radioshed.source.SYNCTHING_LOCAL_WEB_UI_URL
import com.visorcraft.radioshed.source.SyncthingDetector
import com.visorcraft.radioshed.source.openWebUrl
import com.visorcraft.radioshed.ui.library.FolderBrowserDialog
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Five-step wizard that walks the user from "I have RadioShed and a
 * music folder on my PC" to "new files appear on my phone
 * automatically." Syncthing itself is the engine; this wizard just
 * deep-links into the Syncthing app at the right moments and
 * registers the resulting folder with RadioShed's library scanner
 * at the end so the user doesn't have to bounce out to Library
 * Settings to add it manually.
 *
 * Steps:
 *   1. Welcome — what we're going to do, expected time (~10 min).
 *   2. Phone-side install — detect Syncthing, route to install if
 *      missing, "Open it" if present.
 *   3. PC-side install — direct to syncthing.net.
 *   4. Pair the two devices — open Syncthing on the phone for the
 *      device-ID QR code, paste / scan into the PC web UI.
 *   5. Add a folder on PC + accept on phone — Syncthing handles the
 *      file transfer; this wizard just orients the user.
 *   6. Pick the now-syncing folder in RadioShed — runs the existing
 *      `FolderBrowserDialog`, registers the result, kicks a scan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncthingWizardScreen(
    onBack: () -> Unit,
    onComplete: () -> Unit,
    detector: SyncthingDetector = koinInject(),
    folderRepo: FolderRepository = koinInject(),
    scanner: Scanner = koinInject(),
) {
    var step by remember { mutableStateOf(WizardStep.Welcome) }
    // Re-probe on each step entry — by the time the user is
    // mid-wizard they might've come back from the Play Store with
    // Syncthing freshly installed, and we want the install step's
    // CTA to read "Continue" rather than "Install" without a manual
    // page refresh.
    var installed by remember { mutableStateOf(detector.isInstalled()) }
    LaunchedEffect(step) {
        installed = detector.isInstalled()
    }

    val scope = rememberCoroutineScope()
    var folderPickerOpen by remember { mutableStateOf(false) }
    var pickedFolder by remember { mutableStateOf<String?>(null) }
    var pickedFolderName by remember { mutableStateOf<String?>(null) }
    var scanStatus by remember { mutableStateOf<ScanProgress?>(null) }
    var addError by remember { mutableStateOf<String?>(null) }
    // Guards the "Add and scan now" button against concurrent
    // launches. Without this, a rapid double-tap fires two
    // scope.launch blocks each running scanner.scanFolder.collect
    // on the same folder id, producing duplicate writes and a
    // double onComplete() at the end.
    var scanning by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(step.title) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Close") }
                },
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
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            ProgressBar(currentStep = step)
            Spacer(Modifier.height(20.dp))

            when (step) {
                WizardStep.Welcome -> WelcomeBody()
                WizardStep.InstallPhone -> InstallPhoneBody(
                    installed = installed,
                    onRecheck = { installed = detector.isInstalled() },
                    onLaunch = { detector.launch() },
                )
                WizardStep.InstallPc -> InstallPcBody()
                WizardStep.PairDevices -> PairDevicesBody(
                    installed = installed,
                    onLaunchSyncthing = { detector.launch() },
                )
                WizardStep.AddFolder -> AddFolderBody(
                    installed = installed,
                    onLaunchSyncthing = { detector.launch() },
                )
                WizardStep.PickInRadioShed -> PickInRadioShedBody(
                    pickedFolder = pickedFolder,
                    pickedFolderName = pickedFolderName,
                    scanStatus = scanStatus,
                    addError = addError,
                    scanning = scanning,
                    onPickFolder = { folderPickerOpen = true },
                    onConfirm = {
                        val path = pickedFolder ?: return@PickInRadioShedBody
                        if (scanning) return@PickInRadioShedBody
                        scanning = true
                        scope.launch {
                            runCatching {
                                // Re-use the existing folder row when the
                                // user re-runs the wizard against the same
                                // path. The folder.uri column is UNIQUE,
                                // so a naked .insert() on the second run
                                // throws SQLite — same defensive pattern
                                // as Onboarding.Folder step.
                                val existing = folderRepo.getByUri(path)
                                val id = existing?.id ?: folderRepo.insert(
                                    displayName = pickedFolderName?.ifBlank { null }
                                        ?: path.substringAfterLast('/').ifBlank { path },
                                    uri = path,
                                    recursive = true,
                                )
                                scanner.scanFolder(id).collect { ev ->
                                    scanStatus = ev
                                    if (ev is ScanProgress.Completed
                                        || ev is ScanProgress.Failed
                                    ) {
                                        // The wizard's last visible state
                                        // before bouncing back to settings.
                                        onComplete()
                                    }
                                }
                            }.onFailure {
                                addError = it.message ?: "Failed to add folder"
                            }
                            scanning = false
                        }
                    },
                )
            }

            Spacer(Modifier.height(24.dp))
            NavRow(
                step = step,
                installed = installed,
                pickedFolder = pickedFolder,
                onBack = {
                    val prev = step.previous()
                    if (prev != null) step = prev else onBack()
                },
                onNext = {
                    val next = step.next()
                    if (next != null) step = next
                },
                onFinish = onComplete,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (folderPickerOpen) {
        FolderBrowserDialog(
            initialPath = "/storage/emulated/0",
            onSelect = { picked ->
                pickedFolder = picked
                pickedFolderName = picked.substringAfterLast('/').ifBlank { picked }
                folderPickerOpen = false
            },
            onDismiss = { folderPickerOpen = false },
        )
    }
}

private enum class WizardStep(val title: String) {
    Welcome("Sync setup — overview"),
    InstallPhone("1. Install on phone"),
    InstallPc("2. Install on PC"),
    PairDevices("3. Pair your devices"),
    AddFolder("4. Share a folder"),
    PickInRadioShed("5. Add to RadioShed");

    fun next(): WizardStep? {
        val ord = ordinal
        val all = values()
        return if (ord + 1 < all.size) all[ord + 1] else null
    }

    fun previous(): WizardStep? {
        val ord = ordinal
        val all = values()
        return if (ord > 0) all[ord - 1] else null
    }
}

@Composable
private fun ProgressBar(currentStep: WizardStep) {
    val total = WizardStep.values().size
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(total) { idx ->
            val isFilled = idx <= currentStep.ordinal
            Surface(
                modifier = Modifier
                    .height(4.dp)
                    .weight(1f),
                color = if (isFilled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(2.dp),
            ) {}
        }
    }
}

@Composable
private fun WelcomeBody() {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.CloudSync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.size(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Keep your music in sync over Wi-Fi",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "via Syncthing — peer-to-peer, no cloud, no account",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        BodyParagraph(
            "This wizard walks you through five steps. Plan ~10 minutes the first " +
                "time. Once it's set up new files appear on your phone automatically " +
                "whenever it's on Wi-Fi at home — no cable, no remembering what " +
                "you've already copied.",
        )
        Spacer(Modifier.height(12.dp))
        BodyParagraph(
            "What you'll need:",
        )
        Bullet("A computer (Windows / macOS / Linux) on the same Wi-Fi as the phone")
        Bullet("About 10 minutes for the one-time setup")
        Bullet("A music folder on the PC you'd like to keep mirrored")
        Spacer(Modifier.height(12.dp))
        BodyParagraph(
            "Syncthing is free and open-source. It runs locally on each device — your " +
                "files never pass through anyone else's server.",
        )
    }
}

@Composable
private fun InstallPhoneBody(
    installed: Boolean,
    onRecheck: () -> Unit,
    onLaunch: () -> Boolean,
) {
    Column {
        if (installed) {
            StatusBanner(
                ok = true,
                title = "Syncthing is installed",
                subtitle = "Tap below to open it and confirm it's running, then continue.",
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onLaunch() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open Syncthing") }
        } else {
            StatusBanner(
                ok = false,
                title = "Syncthing isn't installed yet",
                subtitle = "Install it from one of the stores below, then come back here.",
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { openWebUrl(SYNCTHING_FORK_PLAY_URL) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Install via Play Store") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { openWebUrl(SYNCTHING_FORK_FDROID_URL) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Install via F-Droid") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRecheck,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("I've installed it — re-check") }
        }
        Spacer(Modifier.height(20.dp))
        BodyParagraph(
            "We recommend Syncthing-Fork (by Catfriend1). It's the actively-maintained " +
                "build for Android — the original Nutomic version is also accepted but " +
                "no longer updated.",
        )
        Spacer(Modifier.height(12.dp))
        BodyParagraph(
            "When you first open Syncthing, it'll ask for storage and battery-optimization " +
                "permissions. Allow both — file sync needs storage; running in the background " +
                "needs the battery exemption.",
        )
    }
}

@Composable
private fun InstallPcBody() {
    Column {
        StatusBanner(
            ok = false,
            tone = StatusTone.Info,
            title = "Install Syncthing on your PC",
            subtitle = "We can't detect this from the phone — just confirm once it's done.",
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { openWebUrl(SYNCTHING_DESKTOP_URL) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open syncthing.net/downloads") }
        Spacer(Modifier.height(20.dp))
        BodyParagraph("Pick the build for your OS:")
        Bullet("Windows — SyncTrayzor (system-tray wrapper, easiest)")
        Bullet("macOS — Syncthing-macOS or `brew install syncthing`")
        Bullet("Linux — your distro's package manager (apt / dnf / pacman)")
        Spacer(Modifier.height(12.dp))
        BodyParagraph(
            "Once installed, Syncthing runs in the background and exposes a web admin UI " +
                "at http://localhost:8384. Both devices need to be running for syncing " +
                "to happen — that's normal for peer-to-peer.",
        )
    }
}

@Composable
private fun PairDevicesBody(
    installed: Boolean,
    onLaunchSyncthing: () -> Boolean,
) {
    Column {
        BodyParagraph(
            "Now we'll introduce the phone and PC to each other. Syncthing identifies " +
                "every device by a long device ID — pairing is just exchanging those IDs.",
        )
        Spacer(Modifier.height(16.dp))
        NumberedStep(
            n = 1,
            title = "Open Syncthing on your phone",
            body = "Tap the button below. If a permissions screen appears first, grant " +
                "everything it asks for.",
        )
        if (installed) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onLaunchSyncthing() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open Syncthing on phone") }
        }
        Spacer(Modifier.height(16.dp))
        NumberedStep(
            n = 2,
            title = "Show this phone's device ID as a QR code",
            body = "In Syncthing on the phone: top menu → \"Show device ID\" or " +
                "\"Web GUI\" → \"Actions\" → \"Show ID\". A QR code will appear.",
        )
        Spacer(Modifier.height(16.dp))
        NumberedStep(
            n = 3,
            title = "On your PC, scan the QR code",
            body = "Open the Syncthing web UI on your PC (the SyncTrayzor icon, or " +
                "http://localhost:8384). Click \"Add Remote Device\" → camera icon next " +
                "to the Device ID field → point your webcam at the phone's QR. " +
                "Or copy the device ID off the phone manually if scanning fails.",
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { openWebUrl(SYNCTHING_LOCAL_WEB_UI_URL) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open localhost:8384 (PC web UI)") }
        Spacer(Modifier.height(16.dp))
        NumberedStep(
            n = 4,
            title = "Approve the pairing on your phone",
            body = "After your PC sends the request, your phone shows a notification " +
                "asking to approve the new device. Tap it and choose \"Add\".",
        )
    }
}

@Composable
private fun AddFolderBody(
    installed: Boolean,
    onLaunchSyncthing: () -> Boolean,
) {
    Column {
        BodyParagraph(
            "Now you'll tell Syncthing which folder to mirror. Decide on the PC side first " +
                "— pick whatever folder holds your music — then accept the share on the phone.",
        )
        Spacer(Modifier.height(16.dp))
        NumberedStep(
            n = 1,
            title = "On your PC, add a folder",
            body = "In the web UI: \"Add Folder\" → pick a Folder Label and the path on " +
                "your PC. In the \"Sharing\" tab, tick the box for your phone.",
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { openWebUrl(SYNCTHING_LOCAL_WEB_UI_URL) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open localhost:8384 (PC web UI)") }
        Spacer(Modifier.height(16.dp))
        NumberedStep(
            n = 2,
            title = "On your phone, accept the share",
            body = "Syncthing will post a notification: \"PC wants to share folder X.\" " +
                "Tap it → \"Add\". You'll be asked where on the phone to put the synced " +
                "files. Pick somewhere RadioShed can read — `/storage/emulated/0/Music/" +
                "RadioShed-Sync` is a common choice.",
        )
        if (installed) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onLaunchSyncthing() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open Syncthing on phone") }
        }
        Spacer(Modifier.height(16.dp))
        NumberedStep(
            n = 3,
            title = "Wait for the first sync",
            body = "Syncthing will copy everything from the PC to the phone. Big libraries " +
                "can take a while — go grab coffee. You can keep going in this wizard " +
                "while it runs in the background.",
        )
    }
}

@Composable
private fun PickInRadioShedBody(
    pickedFolder: String?,
    pickedFolderName: String?,
    scanStatus: ScanProgress?,
    addError: String?,
    scanning: Boolean,
    onPickFolder: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column {
        BodyParagraph(
            "Last step: tell RadioShed where Syncthing put the folder so it can scan it " +
                "for tracks. After this, every new file Syncthing pulls down will be " +
                "auto-rescanned next time you open RadioShed.",
        )
        Spacer(Modifier.height(16.dp))
        if (pickedFolder == null) {
            Button(
                onClick = onPickFolder,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Pick the synced folder") }
        } else {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Picked folder",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                    Text(
                        pickedFolder,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onPickFolder,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Pick a different folder") }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onConfirm,
                enabled = !scanning,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (scanning) "Scanning…" else "Add and scan now") }
        }
        addError?.let { msg ->
            Spacer(Modifier.height(12.dp))
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        scanStatus?.let { status ->
            Spacer(Modifier.height(12.dp))
            val (head, sub) = when (status) {
                is ScanProgress.Started -> "Walking the folder…" to null
                is ScanProgress.Progress -> "${status.tracksProcessed} tracks scanned" to
                    status.currentFile.substringAfterLast('/')
                is ScanProgress.Warning -> "Skipped" to status.message
                is ScanProgress.Completed -> "Done — ${status.totalTracks} tracks" to null
                is ScanProgress.Failed -> "Failed" to status.message
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(head, style = MaterialTheme.typography.titleSmall)
                    sub?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavRow(
    step: WizardStep,
    installed: Boolean,
    pickedFolder: String?,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        OutlinedButton(onClick = onBack) {
            Text(if (step == WizardStep.Welcome) "Cancel" else "Back")
        }
        when (step) {
            WizardStep.Welcome -> Button(onClick = onNext) { Text("Get started") }
            WizardStep.InstallPhone -> Button(
                onClick = onNext,
                enabled = installed,
            ) { Text("Continue") }
            WizardStep.InstallPc -> Button(onClick = onNext) { Text("PC's ready — continue") }
            WizardStep.PairDevices -> Button(onClick = onNext) { Text("Devices paired — continue") }
            WizardStep.AddFolder -> Button(onClick = onNext) { Text("Folder shared — continue") }
            WizardStep.PickInRadioShed -> Button(
                // Always allow finishing the wizard from the last
                // step. The body's "Add and scan now" is the
                // recommended path when a folder has been picked,
                // but the user must always have a way out: when
                // they haven't picked a folder yet they can just
                // close out, and when they have, they may want to
                // skip the scan and let the auto-rescan-on-launch
                // pick it up later.
                onClick = onFinish,
            ) {
                Text(if (pickedFolder == null) "Done" else "Skip scan and finish")
            }
        }
    }
}

@Composable
private fun BodyParagraph(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun Bullet(text: String) {
    Row(modifier = Modifier.padding(start = 8.dp, top = 4.dp)) {
        Text(
            "• ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun NumberedStep(n: Int, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    n.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            )
        }
    }
}

private enum class StatusTone { Success, Warning, Info }

@Composable
private fun StatusBanner(
    ok: Boolean,
    title: String,
    subtitle: String,
    tone: StatusTone = if (ok) StatusTone.Success else StatusTone.Warning,
) {
    val container = when (tone) {
        StatusTone.Success -> MaterialTheme.colorScheme.primaryContainer
        StatusTone.Warning -> MaterialTheme.colorScheme.errorContainer
        StatusTone.Info -> MaterialTheme.colorScheme.secondaryContainer
    }
    val onContainer = when (tone) {
        StatusTone.Success -> MaterialTheme.colorScheme.onPrimaryContainer
        StatusTone.Warning -> MaterialTheme.colorScheme.onErrorContainer
        StatusTone.Info -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = container,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ok) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = onContainer,
                )
                Spacer(Modifier.size(12.dp))
            }
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer.copy(alpha = 0.85f),
                )
            }
        }
    }
}
