package com.visorcraft.radioshed.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.BuildInfo
import com.visorcraft.radioshed.data.FolderRepository
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.getPlatform
import kotlinx.coroutines.flow.combine
import org.koin.compose.koinInject

/**
 * Post-onboarding home. Wordmark + tagline + nav cards (Library, Search,
 * Stations placeholder). Replaced by Stations Home when Phase 6 lands —
 * this exists to give the app something coherent to land on while we
 * build out the rest.
 */
@Composable
fun HomeScreen(
    onLibrary: () -> Unit,
    onSearch: () -> Unit,
    onStations: () -> Unit,
    folderRepo: FolderRepository = koinInject(),
    libraryRepo: LibraryRepository = koinInject(),
) {
    val folders by folderRepo.observeAll().collectAsState(initial = emptyList())
    val totalTracks = folders.sumOf { it.trackCount }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isCompact = maxWidth < 600.dp
            val isExpanded = maxWidth >= 840.dp
            val horizontalPadding = when {
                isExpanded -> 96.dp
                isCompact -> 24.dp
                else -> 48.dp
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(if (isCompact) 32.dp else 64.dp))

                Text(
                    text = "RadioShed",
                    style = if (isCompact) MaterialTheme.typography.displayMedium
                    else MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Your music. Your stations. No subscriptions.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 480.dp),
                )

                Spacer(Modifier.height(40.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                ) {
                    NavCard(
                        title = "Stations",
                        subtitle = "Build and play stations from your library",
                        badge = "Coming in Phase 4",
                        onClick = onStations,
                        primary = true,
                    )
                    NavCard(
                        title = "Library",
                        subtitle = "${folders.size} folder${if (folders.size == 1) "" else "s"} · $totalTracks track${if (totalTracks == 1L) "" else "s"} indexed",
                        onClick = onLibrary,
                    )
                    NavCard(
                        title = "Search",
                        subtitle = "Find tracks, artists, and albums",
                        onClick = onSearch,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "v${BuildInfo.VERSION_NAME} · ${getPlatform().name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                )
            }
        }
    }
}

@Composable
private fun NavCard(
    title: String,
    subtitle: String,
    badge: String? = null,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (primary) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (primary) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (primary) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            badge?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (primary) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
    }
}
