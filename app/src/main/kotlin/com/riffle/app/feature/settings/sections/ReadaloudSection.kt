package com.riffle.app.feature.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.riffle.app.R
import com.riffle.app.feature.settings.DrillInChevron
import com.riffle.feature.settings.ReadaloudMatchSummary
import com.riffle.feature.settings.displayHost
import com.riffle.app.feature.settings.SettingsSectionHeader
import com.riffle.app.feature.settings.StorytellerBadge
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source

/**
 * "Readaloud" section — collapsed to a single drill-in row that leads to the dedicated Readaloud
 * settings screen. The row keeps the at-a-glance affordances the pre-collapse layout had:
 *  - [StorytellerBadge] tinted by whether a Storyteller Service is configured
 *  - subtitle summarising server host + version + review-match counts (or the "not configured"
 *    hint when there's no Storyteller yet).
 *
 * Tapping opens `ReadaloudSettingsScreen` where the Storyteller-config row, match review, and
 * highlight-color picker live.
 */
@Composable
internal fun ReadaloudSection(
    servers: List<Source>,
    serverVersions: Map<String, String>,
    readaloudSummaries: Map<String, ReadaloudMatchSummary>,
    onOpen: () -> Unit,
) {
    SettingsSectionHeader(stringResource(R.string.ui_readaloud))
    val storyteller = servers.firstOrNull { it.serverType == ServerType.STORYTELLER_SERVICE }
    val configured = storyteller != null
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = { StorytellerBadge(configured = configured) },
        headlineContent = {
            Text(
                if (configured) {
                    stringResource(R.string.ui_readaloud)
                } else {
                    stringResource(R.string.ui_configure_readaloud)
                },
            )
        },
        supportingContent = {
            Text(localizedReadaloudRowSummary(storyteller, serverVersions, readaloudSummaries))
        },
        trailingContent = { DrillInChevron() },
    )
}

@Composable
internal fun localizedReadaloudRowSummary(
    storyteller: Source?,
    serverVersions: Map<String, String>,
    readaloudSummaries: Map<String, ReadaloudMatchSummary>,
): String {
    if (storyteller == null) return stringResource(R.string.ui_storyteller_not_configured_tap_to_set_up)
    val username = storyteller.username.takeIf { it.isNotEmpty() }
    val version = serverVersions[storyteller.id]
    val summary = readaloudSummaries[storyteller.id]
    val head = if (username != null) {
        "$username@${storyteller.url.displayHost()}"
    } else {
        storyteller.url.displayHost()
    }
    val parts = mutableListOf(head)
    if (version != null) parts += "v$version"
    if (summary != null) parts += localizedMatchCountsFragment(summary)
    return parts.joinToString(" · ")
}

@Composable
internal fun localizedMatchCountsFragment(summary: ReadaloudMatchSummary): String {
    val total = summary.unmatchedCount + summary.suggestedCount +
        summary.partiallyMatchedCount + summary.matchedCount
    if (total == 0) return stringResource(R.string.ui_no_readalouds_yet)
    val parts = mutableListOf<String>()
    if (summary.unmatchedCount > 0) parts += stringResource(R.string.ui_unmatched_count, summary.unmatchedCount)
    if (summary.suggestedCount > 0) parts += stringResource(R.string.ui_suggested_count, summary.suggestedCount)
    if (summary.partiallyMatchedCount > 0) parts += stringResource(R.string.ui_partial_count, summary.partiallyMatchedCount)
    if (summary.matchedCount > 0) parts += stringResource(R.string.ui_matched_count, summary.matchedCount)
    return parts.joinToString(" · ")
}
