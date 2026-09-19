package com.riffle.feature.settings

import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.PanelOverflowBehavior
import com.riffle.core.domain.comic.asComicBackgroundTheme

/**
 * Subtitle for the "Comics" drill-in row: background theme, Panel View state, and the optional
 * reading-progress / page-number affordances.
 *
 * Untranslated — Android renders a localized variant (`localizedComicDisplaySummary`) inside a
 * Composable scope; this is the shared, resource-free string both platforms fall back to and the
 * one `ComicDisplaySummaryTest` pins on JVM and iOS.
 */
fun comicDisplaySummary(prefs: ComicFormattingPreferences): String = buildString {
    val backgroundTheme = prefs.backgroundTheme.asComicBackgroundTheme()
    append(
        if (backgroundTheme == ReaderTheme.Auto) {
            "Auto background"
        } else {
            "${backgroundTheme.label()} background"
        },
    )
    append(" · ")
    append(
        if (prefs.panelViewOn) {
            when (prefs.panelOverflow) {
                PanelOverflowBehavior.SPLIT -> "Panel View · Split"
                PanelOverflowBehavior.SMART_SPLIT -> "Panel View · Smart split"
                PanelOverflowBehavior.OFF -> "Panel View · No split"
            }
        } else {
            "Panel View off"
        },
    )
    if (prefs.showChapterMap) {
        append(" · Reading progress")
        if (prefs.showPageProgress) append(" · Page numbers")
    }
}
