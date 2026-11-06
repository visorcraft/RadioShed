package com.visorcraft.radioshed.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.BuildInfo
import com.visorcraft.radioshed.data.FolderRepository
import com.visorcraft.radioshed.getPlatform
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import radioshed.composeapp.generated.resources.Res
import radioshed.composeapp.generated.resources.radioshed

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
    onSettings: () -> Unit,
    folderRepo: FolderRepository = koinInject(),
) {
    val folders by folderRepo.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val totalTracks = folders.sumOf { it.trackCount }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isCompact = maxWidth < 600.dp
            val isExpanded = maxWidth >= 840.dp
            val horizontalPadding =
                when {
                    isExpanded -> 96.dp
                    isCompact -> 24.dp
                    else -> 48.dp
                }

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = horizontalPadding, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Top breathing room — compressed from the pre-icon
                // layout because the icon now consumes vertical
                // budget that used to be empty space. Compact phones
                // get the minimum needed to clear the system status
                // bar; expanded windows get a generous gap so the
                // mark doesn't crowd the title bar.
                Spacer(Modifier.height(if (isCompact) 16.dp else 40.dp))

                // App icon. Size scales with form factor so the mark
                // stays proportional to the title text below it
                // (phone: 96dp, tablet: 112dp, desktop window: 128dp).
                // The PNG is 256x256 so even desktop HiDPI up-scales
                // cleanly. Painted via Compose Multiplatform resources
                // so the same .png at composeApp/src/commonMain/
                // composeResources/drawable/radioshed.png feeds every
                // target without a per-platform fork.
                val iconSize =
                    when {
                        isExpanded -> 128.dp
                        isCompact -> 96.dp
                        else -> 112.dp
                    }
                Image(
                    painter = painterResource(Res.drawable.radioshed),
                    contentDescription = "RadioShed",
                    modifier = Modifier.size(iconSize),
                )
                Spacer(Modifier.height(12.dp))

                Text(
                    text = "RadioShed",
                    // Stepped down one notch from the icon-less
                    // layout (displayMedium/displayLarge -> headlineLarge
                    // /displayMedium) so the title doesn't tower over
                    // the mark above it. Bold weight preserves the
                    // strong wordmark feel.
                    style =
                        if (isCompact) {
                            MaterialTheme.typography.headlineLarge
                        } else {
                            MaterialTheme.typography.displayMedium
                        },
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Your music. Your stations. No subscriptions.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 480.dp),
                )

                // Reduced from 40dp -> 28dp so the four nav cards
                // stay above the fold on a Galaxy Fold 7's outer
                // display (the most-cramped Android form factor we
                // support) even with the new icon on top.
                Spacer(Modifier.height(28.dp))

                Column(
                    // Spacing 12 -> 10 between cards, NavCard inner
                    // padding 20 -> 14 (see NavCard below). Tightening
                    // both is what claws back the room for the icon
                    // without compressing the title.
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                ) {
                    NavCard(
                        title = "Stations",
                        subtitle = "Built-in presets + your own — tap to play",
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
                    NavCard(
                        title = "Settings",
                        subtitle = "Theme, accessibility, about",
                        onClick = onSettings,
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
        colors =
            CardDefaults.cardColors(
                // `surface` is barely distinguishable from `background` on
                // the dark theme — non-primary cards rendered as nearly
                // invisible rectangles on the new Home layout. Switched to
                // `surfaceContainerHigh` (one tonal step up from surface)
                // so the tap targets read clearly without competing with
                // the primary Stations card's container colour.
                containerColor =
                    if (primary) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
            ),
        shape = RoundedCornerShape(16.dp),
    ) {
        // Inner padding reduced from 20dp -> 14dp/16dp pair to
        // shrink each card's vertical footprint by ~12dp without
        // making the tap target feel cramped (Material's 48dp
        // touch-target minimum is still cleared with room to spare
        // because titleMedium + bodyMedium together are ~44dp).
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                title,
                // titleLarge (22sp) -> titleMedium (16sp). One step
                // down lets the four cards stack inside ~60dp each
                // rather than ~76dp, claiming back the room the
                // new icon needs above.
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color =
                    if (primary) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (primary) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    },
            )
            badge?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (primary) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        },
                )
            }
        }
    }
}
