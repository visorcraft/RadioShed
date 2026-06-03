package com.visorcraft.radioshed.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.visorcraft.radioshed.BuildInfo
import com.visorcraft.radioshed.licenses.Platform
import com.visorcraft.radioshed.licenses.RADIOSHED_PROPRIETARY
import com.visorcraft.radioshed.licenses.THIRD_PARTY_COMPONENTS
import com.visorcraft.radioshed.licenses.ThirdPartyComponent
import com.visorcraft.radioshed.nav.PlatformBackHandler
import com.visorcraft.radioshed.source.openWebUrl
import com.visorcraft.radioshed.ui.util.TooltipIconButton
import radioshed.composeapp.generated.resources.Res

/**
 * About → Licenses. A self-contained list → detail screen sourced from the
 * hand-curated [THIRD_PARTY_COMPONENTS] catalog (the single source of truth
 * shared with the generated `THIRD_PARTY_LICENSES` file). The proprietary
 * RadioShed license is pinned as its own first section; OSS components follow,
 * grouped by platform (All / Android / Desktop / iOS). Tapping a row opens the
 * full license body, read lazily from the bundled resource.
 *
 * Back behavior: while a component is selected, the first back press returns to
 * the list; from the list, back pops the screen via [onBack].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    var selected by remember { mutableStateOf<ThirdPartyComponent?>(null) }

    PlatformBackHandler {
        if (selected != null) selected = null else onBack()
    }

    val proprietary = remember {
        RADIOSHED_PROPRIETARY.copy(version = BuildInfo.VERSION_NAME)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected?.name ?: "Licenses") },
                navigationIcon = {
                    TooltipIconButton(
                        tooltip = "Back",
                        onClick = { if (selected != null) selected = null else onBack() },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val current = selected
        if (current == null) {
            LicenseList(
                proprietary = proprietary,
                modifier = Modifier.fillMaxSize().padding(padding),
                onSelect = { selected = it },
            )
        } else {
            ComponentDetail(
                component = current,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

@Composable
private fun LicenseList(
    proprietary: ThirdPartyComponent,
    modifier: Modifier,
    onSelect: (ThirdPartyComponent) -> Unit,
) {
    // Fixed section order: RadioShed (proprietary), then All / Android /
    // Desktop / iOS. A component belongs to a platform section when that
    // Platform is in its platforms set.
    val sections: List<Pair<String, List<ThirdPartyComponent>>> = listOf(
        "RadioShed" to listOf(proprietary),
        "All platforms" to THIRD_PARTY_COMPONENTS.filter { Platform.ALL in it.platforms },
        "Android" to THIRD_PARTY_COMPONENTS.filter { Platform.ANDROID in it.platforms },
        "Desktop" to THIRD_PARTY_COMPONENTS.filter { Platform.DESKTOP in it.platforms },
        "iOS" to THIRD_PARTY_COMPONENTS.filter { Platform.IOS in it.platforms },
    ).filter { it.second.isNotEmpty() }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        sections.forEach { (label, components) ->
            item(key = "header-$label") {
                PlatformSectionHeader(label)
            }
            // A multi-platform component (e.g. jaudiotagger in Android+Desktop,
            // Skiko in Desktop+iOS) appears in more than one section. LazyColumn
            // keys must be unique across the whole list, so scope the row key to
            // its section label — the same scoping the header key already uses.
            items(components, key = { "$label-${it.coordinate}" }) { component ->
                LicenseRow(component = component, onClick = { onSelect(component) })
            }
        }
    }
}

@Composable
private fun PlatformSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun LicenseRow(component: ThirdPartyComponent, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (component.version.isBlank()) component.name
                       else "${component.name} · ${component.version}",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            LicenseBadge(component)
        }
    }
}

@Composable
private fun LicenseBadge(component: ThirdPartyComponent) {
    val container = if (component.license.copyleft) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.secondaryContainer
    val content = if (component.license.copyleft) MaterialTheme.colorScheme.onTertiaryContainer
                  else MaterialTheme.colorScheme.onSecondaryContainer
    Text(
        text = component.license.spdx,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = content,
        modifier = Modifier
            .background(container, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun ComponentDetail(component: ThirdPartyComponent, modifier: Modifier) {
    // Primary license body, then any secondary bodies a layered component must
    // reproduce (sqlite-jdbc -> BSD-2-Clause, Skiko -> BSD-3-Clause). Each pair
    // is the license's display name and its full text.
    val licenses = remember(component) {
        listOf(component.license) + component.additionalLicenses
    }
    val bodies by produceState(initialValue = emptyList<Pair<String, String>>(), component) {
        value = licenses.map { license ->
            license.displayName to Res.readBytes(license.textResource).decodeToString()
        }
    }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = component.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        DetailLine("Coordinate", component.coordinate)
        if (component.version.isNotBlank()) DetailLine("Version", component.version)
        DetailLine("License", "${component.license.displayName} (${component.license.spdx})")
        DetailLine("Copyright", component.copyright)
        DetailLine("Platforms", component.platforms.joinToString(", "))
        DetailLine("Homepage", component.homepage)
        component.notes?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.clickable { openWebUrl(component.homepage) },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text = "View online",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        bodies.forEachIndexed { index, (displayName, text) ->
            // Label each block only when a layered component reproduces more
            // than one license text, so single-license rows stay uncluttered.
            if (bodies.size > 1) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
            if (index < bodies.lastIndex) Spacer(Modifier.height(16.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
    }
}
