package com.riffle.app.feature.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.riffle.app.feature.settings.DrillInChevron
import com.riffle.app.feature.settings.ReadaloudMatchSummary
import com.riffle.app.feature.settings.SettingsSectionHeader
import com.riffle.app.feature.settings.StorytellerBadge
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.Source

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
    SettingsSectionHeader("Readaloud")
    val storyteller = servers.firstOrNull { it.serverType == ServerType.STORYTELLER_SERVICE }
    val configured = storyteller != null
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = { StorytellerBadge(configured = configured) },
        headlineContent = { Text(if (configured) "Readaloud" else "Configure Readaloud") },
        supportingContent = {
            Text(readaloudRowSummary(storyteller, serverVersions, readaloudSummaries))
        },
        trailingContent = { DrillInChevron() },
    )
}

/** Subtitle text for the collapsed Readaloud row — mirrors the pre-collapse per-row details. */
internal fun readaloudRowSummary(
    storyteller: Source?,
    serverVersions: Map<String, String>,
    readaloudSummaries: Map<String, ReadaloudMatchSummary>,
): String {
    if (storyteller == null) return "Storyteller not configured · tap to set up"
    val username = storyteller.username.takeIf { it.isNotEmpty() }
    val version = serverVersions[storyteller.id]
    val summary = readaloudSummaries[storyteller.id]
    return buildString {
        if (username != null) {
            append(username)
            append('@')
        }
        append(shortHost(storyteller.url.value))
        if (version != null) {
            append(" · v")
            append(version)
        }
        if (summary != null) {
            append(" · ")
            val hasAny = summary.unmatchedCount + summary.suggestedCount +
                summary.partiallyMatchedCount + summary.matchedCount > 0
            if (!hasAny) {
                append("no readalouds yet")
            } else if (summary.unmatchedCount == 0 && summary.partiallyMatchedCount == 0) {
                append("${summary.matchedCount} matched")
            } else {
                append("${summary.unmatchedCount} unmatched · ${summary.matchedCount} matched")
            }
        }
    }
}

private fun shortHost(rawUrl: String): String =
    runCatching { java.net.URI(rawUrl).host ?: rawUrl }.getOrDefault(rawUrl)
