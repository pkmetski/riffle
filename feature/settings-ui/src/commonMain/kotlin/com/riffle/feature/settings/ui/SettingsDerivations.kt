package com.riffle.feature.settings.ui

import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.comic.ComicBackgroundThemeOptions
import com.riffle.core.models.HighlightColor
import com.riffle.core.models.Source
import com.riffle.feature.settings.ReadaloudMatchSummary

/**
 * The English string for the cadence-not-supported note. Matches [Res.string.ui_cadence_webview_unavailable].
 * Exposed here so tests can assert the note is displayed without importing string resources.
 */
internal const val CADENCE_UNSUPPORTED_NOTE =
    "Cadence isn't available on this device's WebView. Update the Android System WebView from the Play Store to enable it."

/** Ordered list of highlight colour options for the Cadence chip picker. */
internal val cadenceHighlightChipOptions: List<HighlightColor> = HighlightColor.entries

/** Display label for a highlight colour — title-cased from the enum name. */
internal fun highlightColorLabel(color: HighlightColor): String =
    color.name.lowercase().replaceFirstChar { it.uppercase() }

/**
 * Formats the audiobook speed/skip/rewind into the one-liner shown in the Listening sub-screen
 * row. Whole-number speeds trim the ".0" (1× not 1.0×).
 *
 * Regression note: the private `formatSpeed` this replaced truncated to one decimal so 0.75×
 * read "0.7×". Reverting this function makes [SettingsDerivationsTest.listeningSummaryPrintsTheSpeedAtTheDomainsStepGranularity] red.
 */
internal fun listeningSummary(speed: Float, skipSec: Int, rewindSec: Int): String {
    val speedStr = if (speed == speed.toLong().toFloat()) "${speed.toLong()}×" else "${speed}×"
    return "Speed $speedStr · Skip ${skipSec}s · Rewind ${rewindSec}s"
}

/**
 * Subtitle line for a Storyteller source row in the Readaloud settings screen. Suppresses zero
 * counts so "0 unmatched" never appears.
 *
 * Regression note: the private iOS copy printed all counts unconditionally and dropped host/version
 * once any summary existed. Reverting makes [SettingsDerivationsTest.readaloudSubtitleSuppressesZeroCountsAndKeepsHostAndVersion] red.
 */
internal fun readaloudSubtitle(
    source: Source,
    serverVersions: Map<String, String>,
    readaloudSummaries: Map<String, ReadaloudMatchSummary>,
): String {
    val prefix = "${source.username}@${source.url.authority()}"
    val version = serverVersions[source.id]
    val summary = readaloudSummaries[source.id]

    val versionStr = version?.let { " · v$it" } ?: ""

    val summaryStr = if (summary == null) "" else {
        val parts = buildList {
            if (summary.matchedCount > 0) add("${summary.matchedCount} matched")
            if (summary.unmatchedCount > 0) add("${summary.unmatchedCount} unmatched")
            if (summary.suggestedCount > 0) add("${summary.suggestedCount} suggested")
            if (summary.partiallyMatchedCount > 0) add("${summary.partiallyMatchedCount} partially matched")
        }
        if (parts.isEmpty()) " · no readalouds yet" else " · ${parts.joinToString(" · ")}"
    }

    return "$prefix$versionStr$summaryStr"
}

/**
 * Maps a stored [ReaderTheme] to the chip that should appear selected in the comic background
 * picker. [ReaderTheme.DarkDim] has no separate chip — it renders the same paper as Dark.
 *
 * Regression note: the iOS private copy only offered the three concrete backdrops, so a stored
 * Auto theme highlighted nothing. Reverting makes [SettingsDerivationsTest.comicBackgroundOptionsIncludeAuto] red.
 */
internal fun comicBackgroundChipSelection(theme: ReaderTheme): ReaderTheme =
    if (theme == ReaderTheme.DarkDim) ReaderTheme.Dark else theme
