package com.visorcraft.radioshed.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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
 * Post-onboarding home. Wordmark, tagline, and nav cards for Stations,
 * Library, Search, and Settings.
 *
 * Width breakpoints alone are not enough. A 1080p projector at xhdpi is
 * about 960×540 dp: wide enough to take the desktop header, too short to
 * show every card. The column scrolls, and a short viewport uses a
 * compact header so a D-pad can move through every card without the last
 * ones being clipped out of reach.
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
    val folderWord = if (folders.size == 1) "folder" else "folders"
    val trackWord = if (totalTracks == 1L) "track" else "tracks"

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isCompact = maxWidth < 600.dp
            val isExpanded = maxWidth >= 840.dp
            // 640 dp is below a normal phone in portrait and above a
            // 1080p xhdpi projector (540 dp). Those landscape panels
            // used to inherit the desktop header from width alone.
            val viewportHeight = maxHeight
            val isShort = viewportHeight < 640.dp
            val horizontalPadding =
                when {
                    isExpanded -> 96.dp
                    isCompact -> 24.dp
                    else -> 48.dp
                }
            val verticalPadding = if (isShort) 12.dp else 24.dp
            val cardSpacing = if (isShort) 8.dp else 10.dp

            // verticalScroll is required even when the compact header
            // fits: a large font scale or the MiniPlayer can still push
            // the last card past the clip. Focus on a card asks the
            // scrollable to bring that card into view, which is what
            // lets a remote move past the last fully visible row.
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = viewportHeight)
                            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Top breathing room. Compact phones clear the status
                    // bar; expanded windows keep the mark off the title
                    // bar. Short panels skip that gap — the icon plus the
                    // desktop title is what used to shove Settings off
                    // the bottom.
                    Spacer(
                        Modifier.height(
                            when {
                                isShort -> 4.dp
                                isCompact -> 16.dp
                                else -> 40.dp
                            },
                        ),
                    )

                    // App icon. Phone 96 dp, tablet 112 dp, desktop
                    // 128 dp. Short panels use 72 dp so the four cards
                    // stay on screen. The PNG is 256×256. One resource
                    // at composeResources/drawable/radioshed.png feeds
                    // every target.
                    val iconSize =
                        when {
                            isShort -> 72.dp
                            isExpanded -> 128.dp
                            isCompact -> 96.dp
                            else -> 112.dp
                        }
                    Image(
                        painter = painterResource(Res.drawable.radioshed),
                        contentDescription = "RadioShed",
                        modifier = Modifier.size(iconSize),
                    )
                    Spacer(Modifier.height(if (isShort) 8.dp else 12.dp))

                    Text(
                        text = "RadioShed",
                        // headlineLarge / displayMedium keep the wordmark
                        // strong without the title outgrowing the icon.
                        // Short panels step down again: displayMedium
                        // alone is ~52 dp and does not fit four cards
                        // under a 540 dp viewport.
                        style =
                            when {
                                isShort -> MaterialTheme.typography.headlineMedium
                                isCompact -> MaterialTheme.typography.headlineLarge
                                else -> MaterialTheme.typography.displayMedium
                            },
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(if (isShort) 2.dp else 6.dp))
                    Text(
                        text = "Your music. Your stations. No subscriptions.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 480.dp),
                    )

                    Spacer(Modifier.height(if (isShort) 12.dp else 28.dp))

                    Column(
                        verticalArrangement = Arrangement.spacedBy(cardSpacing),
                        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                    ) {
                        NavCard(
                            title = "Stations",
                            subtitle = "Built-in presets + your own — tap to play",
                            onClick = onStations,
                            primary = true,
                            compact = isShort,
                        )
                        NavCard(
                            title = "Library",
                            subtitle = "${folders.size} $folderWord · $totalTracks $trackWord indexed",
                            onClick = onLibrary,
                            compact = isShort,
                        )
                        NavCard(
                            title = "Search",
                            subtitle = "Find tracks, artists, and albums",
                            onClick = onSearch,
                            compact = isShort,
                        )
                        NavCard(
                            title = "Settings",
                            subtitle = "Theme, accessibility, about",
                            onClick = onSettings,
                            compact = isShort,
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
}

@Composable
private fun NavCard(
    title: String,
    subtitle: String,
    badge: String? = null,
    primary: Boolean = false,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    // Remote / D-pad focus has no cursor. The resting ripple is easy to
    // miss across a room, so a focused card takes the stronger fill and
    // a high-contrast ring. Touch does not leave the ring up: leaving
    // this screen disposes it, and coming back starts unfocused.
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val emphasized = focused || primary
    val strong = focused && primary
    val container =
        when {
            strong -> MaterialTheme.colorScheme.primary
            emphasized -> MaterialTheme.colorScheme.primaryContainer
            // `surface` is barely distinguishable from `background` on
            // the dark theme. `surfaceContainerHigh` keeps the card
            // visible without competing with the Stations card.
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        }
    val content =
        when {
            strong -> MaterialTheme.colorScheme.onPrimary
            emphasized -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        }
    val ring = if (focused) MaterialTheme.colorScheme.onBackground else Color.Transparent
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .border(width = 4.dp, color = ring, shape = shape)
                .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = container),
        shape = shape,
    ) {
        // 14 dp vertical padding still clears a 48 dp touch target
        // (titleMedium + bodySmall are ~40 dp). Short panels use 10 dp
        // so four cards fit a 540 dp-tall viewport.
        Column(
            modifier =
                Modifier.padding(
                    horizontal = 16.dp,
                    vertical = if (compact) 10.dp else 14.dp,
                ),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = content,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = content.copy(alpha = if (emphasized) 0.8f else 0.6f),
            )
            badge?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = if (emphasized) 0.6f else 0.5f),
                )
            }
        }
    }
}
