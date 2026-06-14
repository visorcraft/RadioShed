package com.visorcraft.radioshed.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.crash.CrashLogEntry
import com.visorcraft.radioshed.crash.CrashLogReader
import com.visorcraft.radioshed.crash.CrashLogSharer
import com.visorcraft.radioshed.data.nowMillis
import com.visorcraft.radioshed.ui.util.TooltipIconButton
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Local-only crash & ANR triage. Sourced from `ApplicationExitInfo`
 * — the OS keeps roughly the last dozen abnormal exits, we filter to
 * crash / ANR / native crash / OOM and render newest-first. Tapping a
 * row expands the description + stack trace. The toolbar's Share
 * action hands the full dump to the platform share sheet so the user
 * can paste it into a bug report. Nothing is uploaded automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogScreen(
    onBack: () -> Unit,
    reader: CrashLogReader = koinInject(),
    sharer: CrashLogSharer = koinInject(),
) {
    var entries by remember { mutableStateOf<List<CrashLogEntry>>(emptyList()) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { entries = reader.recentEntries() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crash log") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    val copyAll = com.visorcraft.radioshed.ui.util.rememberCopyToClipboard()
                    TooltipIconButton(
                        tooltip = "Copy all",
                        enabled = entries.isNotEmpty(),
                        onClick = {
                            copyAll(entries.joinToString("\n\n---\n\n") { it.toShareText() })
                        },
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy all")
                    }
                    TooltipIconButton(
                        tooltip = "Share",
                        enabled = entries.isNotEmpty(),
                        onClick = {
                            scope.launch {
                                sharer.share(
                                    subject = "RadioShed crash log",
                                    body = entries.joinToString("\n\n---\n\n") { it.toShareText() },
                                )
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text =
                        "No crashes recorded. The OS keeps roughly the last " +
                            "dozen abnormal app exits — anything older has aged out.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        } else {
            val rowCopy = com.visorcraft.radioshed.ui.util.rememberCopyToClipboard()
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.timestamp }) { entry ->
                    CrashLogRow(
                        entry = entry,
                        expanded = expanded == entry.timestamp,
                        onToggle = {
                            expanded =
                                if (expanded == entry.timestamp) {
                                    null
                                } else {
                                    entry.timestamp
                                }
                        },
                        onShare = {
                            scope.launch {
                                sharer.share(
                                    subject = "RadioShed ${entry.reason}",
                                    body = entry.toShareText(),
                                )
                            }
                        },
                        onCopy = { rowCopy(entry.toShareText()) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CrashLogRow(
    entry: CrashLogEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        onClick = onToggle,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.reason,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${formatRelativeTime(entry.timestamp)} · ${entry.importance}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                TooltipIconButton(tooltip = "Copy entry", onClick = onCopy) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy entry")
                }
                TooltipIconButton(tooltip = "Share entry", onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = "Share entry")
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                if (entry.description.isNotBlank()) {
                    Text(
                        text = entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                val trace = entry.trace
                if (!trace.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    // Inner verticalScroll inside a LazyColumn item
                                    // requires a finite height — without heightIn,
                                    // expanding any crash row with a stack trace
                                    // throws "Vertical scroll not permitted when
                                    // the parent was measured with infinite
                                    // height." 240dp matches a typical 8-10 frames
                                    // of a stack trace; longer traces scroll.
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(8.dp),
                        ) {
                            Text(
                                text = trace,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            )
                        }
                    }
                } else {
                    Text(
                        text =
                            "No stack trace recorded — the OS only attaches one " +
                                "for ANRs and some native crashes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

private fun CrashLogEntry.toShareText(): String =
    buildString {
        append("Reason: ").append(reason).append('\n')
        append("Time: ").append(formatRelativeTime(timestamp)).append('\n')
        append("Importance: ").append(importance).append('\n')
        if (processName.isNotBlank()) append("Process: ").append(processName).append('\n')
        if (description.isNotBlank()) append("Description: ").append(description).append('\n')
        val t = trace
        if (!t.isNullOrBlank()) {
            append("Trace:\n").append(t)
        }
    }

private fun formatRelativeTime(ms: Long): String {
    val deltaMs = (nowMillis() - ms).coerceAtLeast(0L)
    val sec = deltaMs / 1_000L
    val min = sec / 60L
    val hr = min / 60L
    val day = hr / 24L
    return when {
        sec < 45L -> "Just now"
        min < 60L -> "${min}m ago"
        hr < 24L -> "${hr}h ago"
        day < 7L -> "${day}d ago"
        day < 30L -> "${day / 7L}w ago"
        else -> "${day / 30L}mo ago"
    }
}
