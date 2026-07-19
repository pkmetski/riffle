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
            append(matchCountsFragment(summary))
        }
    }
}

/**
 * Compact string surfacing every non-zero count from a [ReadaloudMatchSummary]. Silent counts
 * (zero) are dropped so the subtitle stays short — "12 matched" reads cleaner than
 * "0 unmatched · 0 suggested · 0 partial · 12 matched" and matches the pre-collapse behaviour
 * where the "Review matches" row showed the four counts only when there were partial/unmatched
 * ones to act on.
 *
 * All four counts zero (fresh install, no readalouds discovered yet) collapses to a friendlier
 * "no readalouds yet".
 */
internal fun matchCountsFragment(summary: ReadaloudMatchSummary): String {
    val total = summary.unmatchedCount + summary.suggestedCount +
        summary.partiallyMatchedCount + summary.matchedCount
    if (total == 0) return "no readalouds yet"
    val parts = buildList {
        if (summary.unmatchedCount > 0) add("${summary.unmatchedCount} unmatched")
        if (summary.suggestedCount > 0) add("${summary.suggestedCount} suggested")
        if (summary.partiallyMatchedCount > 0) add("${summary.partiallyMatchedCount} partial")
        if (summary.matchedCount > 0) add("${summary.matchedCount} matched")
    }
    return parts.joinToString(" · ")
}

private fun shortHost(rawUrl: String): String =
    runCatching { java.net.URI(rawUrl).host ?: rawUrl }.getOrDefault(rawUrl)
