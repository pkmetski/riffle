package com.riffle.app.feature.settings.readaloud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.riffle.app.feature.server.AddSourceBackend
import com.riffle.app.feature.settings.DrillInChevron
import com.riffle.app.feature.settings.SettingsSectionHeader
import com.riffle.app.feature.settings.SettingsViewModel
import com.riffle.app.feature.settings.StorytellerBadge
import com.riffle.app.feature.settings.disabledListItemColors
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.ServerType

/**
 * Full-screen drill-in that hosts every Readaloud-related setting. Reached from the collapsed
 * "Readaloud" row on the main Settings screen. Groups:
 *  - **Server** — Storyteller service configuration row (tap to edit / create).
 *  - **Matches** — Review readaloud matches row (tap to open [ReadaloudMatchesScreen]).
 *  - **Appearance** — sentence-highlight colour picker (persists to [ReadaloudPreferences]).
 *
 * When no Storyteller Service is configured, the Matches row and colour picker read as disabled
 * — they still render so the user can see what will unlock once Storyteller is set up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadaloudSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAddSource: (AddSourceBackend, String?) -> Unit,
    onNavigateToReadaloudMatches: (String) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val servers by viewModel.servers.collectAsState()
    val serverVersions by viewModel.serverVersions.collectAsState()
    val readaloudSummaries by viewModel.readaloudSummaries.collectAsState()
    val readaloudPreferences by viewModel.readaloudPreferences.collectAsState()

    val storyteller = servers.firstOrNull { it.serverType == ServerType.STORYTELLER_SERVICE }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Readaloud") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            HorizontalDivider()
            SettingsSectionHeader("Server")
            if (storyteller == null) {
                ListItem(
                    modifier = Modifier.clickable {
                        onNavigateToAddSource(AddSourceBackend.Storyteller, null)
                    },
                    leadingContent = { StorytellerBadge(configured = false) },
                    headlineContent = { Text("Configure Storyteller") },
                    supportingContent = { Text("Not configured") },
                    trailingContent = { DrillInChevron() },
                )
            } else {
                val username = storyteller.username.takeIf { it.isNotEmpty() }
                val version = serverVersions[storyteller.id]
                val subtitle = buildString {
                    if (username != null) {
                        append(username)
                        append(" · ")
                    }
                    append(storyteller.url.value)
                    if (version != null) {
                        append(" · v")
                        append(version)
                    }
                }
                ListItem(
                    modifier = Modifier.clickable {
                        onNavigateToAddSource(AddSourceBackend.Storyteller, storyteller.id)
                    },
                    leadingContent = { StorytellerBadge(configured = true) },
                    headlineContent = { Text("Storyteller") },
                    supportingContent = { Text(subtitle) },
                    trailingContent = { DrillInChevron() },
                )
            }
            HorizontalDivider()

            SettingsSectionHeader("Matches")
            val summary = storyteller?.let { readaloudSummaries[it.id] }
            ListItem(
                modifier = if (storyteller != null) {
                    Modifier.clickable { onNavigateToReadaloudMatches(storyteller.id) }
                } else Modifier,
                headlineContent = { Text("Review readaloud matches") },
                supportingContent = {
                    if (summary != null) {
                        Text(
                            "${summary.unmatchedCount} unmatched · " +
                                "${summary.suggestedCount} suggested · " +
                                "${summary.partiallyMatchedCount} partially matched · " +
                                "${summary.matchedCount} matched",
                        )
                    } else {
                        Text("Configure Storyteller first to review matches")
                    }
                },
                colors = if (storyteller != null) ListItemDefaults.colors() else disabledListItemColors(),
                // Only render the drill-in affordance when the row is actually tappable — a
                // disabled row with a chevron reads as a broken link.
                trailingContent = if (storyteller != null) {
                    { DrillInChevron() }
                } else null,
            )
            HorizontalDivider()

            SettingsSectionHeader("Appearance")
            val highlightEnabled = storyteller != null
            ListItem(
                headlineContent = { Text("Sentence highlight") },
                colors = if (highlightEnabled) ListItemDefaults.colors() else disabledListItemColors(),
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HighlightColor.entries.forEach { color ->
                            val isSelected = readaloudPreferences.highlightColor == color
                            // Swatch renders the exact `argb` from [HighlightColor] — same pixel
                            // value that lands in the reader. Enabled: don't compose additional
                            // alpha (the palette bakes in 0x80). Disabled: drop to Material's
                            // 0.38 (absolute, not multiplicative against the palette).
                            val base = Color(color.argb.toLong() and 0xFFFFFFFFL)
                            val swatchColor = if (highlightEnabled) base else base.copy(alpha = 0.38f)
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .then(
                                        if (highlightEnabled) Modifier.clickable {
                                            viewModel.updateHighlightColor(color)
                                        } else Modifier,
                                    )
                                    .then(
                                        if (isSelected)
                                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        else Modifier,
                                    )
                                    .padding(4.dp)
                                    .clip(CircleShape)
                                    .background(swatchColor)
                                    .semantics {
                                        contentDescription = color.name.lowercase()
                                            .replaceFirstChar { it.uppercase() } + " highlight" +
                                            if (isSelected) ", selected" else ""
                                    },
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color(0xDD000000),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                },
            )
            HorizontalDivider()
        }
    }
}
