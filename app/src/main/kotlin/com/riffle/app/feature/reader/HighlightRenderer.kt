package com.riffle.app.feature.reader

import androidx.annotation.ColorInt
import com.riffle.core.domain.ReadaloudHighlightColor
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.SentenceQuote
import org.readium.r2.shared.publication.Locator

/** ARGB color used for the active (current) search result highlight — warm orange. */
@ColorInt
internal val SEARCH_ACTIVE_ARGB: Int = 0xFFF5A623.toInt()

/** ARGB color used for inactive (non-current) search result highlights — pale yellow. */
@ColorInt
internal val SEARCH_INACTIVE_ARGB: Int = 0xFFFDE68A.toInt()

/**
 * Abstracts the two rendering pipelines for reader highlights:
 *  - [ReadiumHighlightRenderer]: paginated and scroll modes via DecorableNavigator
 *  - [ContinuousHighlightRenderer]: continuous mode via ChapterWebView JS injection
 *
 * All suspend methods are called from LaunchedEffect coroutines and must be idempotent —
 * they may be invoked with the same arguments on reflow/pageLoad events.
 */
internal interface HighlightRenderer {

    /**
     * Applies or clears the readaloud sentence highlight.
     * [fragmentRef] is "href#spanId" when active, null to clear.
     */
    suspend fun applyReadaloud(
        fragmentRef: String?,
        quotes: Map<String, SentenceQuote>,
        color: ReadaloudHighlightColor,
    )

    /**
     * Applies or clears persisted annotation highlight decorations.
     * Empty [renders] clears the group.
     */
    suspend fun applyAnnotations(
        renders: List<EpubReaderViewModel.HighlightRender>,
        theme: ReaderTheme,
    )

    /**
     * Applies or clears note glyph decorations for annotations that have a note.
     * Empty or all-without-notes [renders] clears the group.
     * Continuous implementation is a no-op — glyphs are emitted inside [applyAnnotations]
     * via [ContinuousStyleInjector.applyAnnotationHighlightsJs], so no separate pass is needed.
     */
    suspend fun applyNoteGlyphs(
        renders: List<EpubReaderViewModel.HighlightRender>,
    )

    /**
     * Applies or clears search result decorations for ALL results at once.
     * Readium implementation renders [Decoration] objects for each locator and
     * runs a post-navigation settle loop to reposition boxes after layout settles.
     * Continuous implementation is a no-op — search match highlighting is done
     * via [highlightSearchMatch] from the navigation event handler.
     * Empty [results] clears the group.
     */
    suspend fun applySearch(
        results: List<Locator>,
        activeIndex: Int,
    )

    /**
     * Highlights a single navigated-to search match.
     * Readium implementation is a no-op (DecorableNavigator handles all matches).
     * Continuous implementation calls [ContinuousHighlightTarget.highlightInChapter].
     */
    fun highlightSearchMatch(href: String, text: String, cssColor: String)
}
