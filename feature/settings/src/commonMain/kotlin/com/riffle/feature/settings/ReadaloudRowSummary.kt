package com.riffle.feature.settings

import com.riffle.core.models.Source
import com.riffle.core.models.SourceUrl

/**
 * Subtitle text for the collapsed Readaloud row — mirrors the pre-collapse per-row details.
 *
 * The pre-collapse layout showed the Storyteller host, its version and the match counts inline on
 * separate rows; folding them behind a single drill-in must keep that at-a-glance information,
 * otherwise the collapse loses information that used to be visible without a tap.
 *
 * Untranslated — Android renders a localized variant (`localizedReadaloudRowSummary`) inside a
 * Composable scope; this is the shared, resource-free string both platforms fall back to and the
 * one `ReadaloudRowSummaryTest` pins on JVM and iOS.
 */
fun readaloudRowSummary(
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
        append(storyteller.url.displayHost())
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
fun matchCountsFragment(summary: ReadaloudMatchSummary): String {
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

/**
 * Bare host of a source URL — scheme, user-info, port, path, query and fragment stripped.
 *
 * Reproduces `java.net.URI(url).host`, which is what the Android Readaloud row used before this
 * summary moved to `commonMain`. The annotation-sync row deliberately keeps the port, so it has
 * its own helper; do not merge the two without deciding which display form wins.
 */
fun SourceUrl.displayHost(): String {
    val authority = authority()
    // IPv6 literals keep their brackets, exactly as java.net.URI.host reports them.
    if (authority.startsWith("[")) return authority.substringBefore(']') + "]"
    return authority.substringBefore(':')
}
