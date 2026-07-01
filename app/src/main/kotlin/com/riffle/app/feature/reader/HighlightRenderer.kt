package com.riffle.app.feature.reader

import androidx.annotation.ColorInt
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.SentenceQuote
import org.readium.r2.shared.publication.Locator

/** ARGB color used for the active (current) search result highlight — warm orange. */
@ColorInt
internal val SEARCH_ACTIVE_ARGB: Int = 0xFFF5A623.toInt()

/** ARGB color used for inactive (non-current) search result highlights — pale yellow. */
@ColorInt
internal val SEARCH_INACTIVE_ARGB: Int = 0xFFFDE68A.toInt()

/**
 * Alpha applied by Readium's [Decoration.Style.Highlight] for search results.
 * The continuous renderer uses this constant so both pipelines render at the same opacity.
 */
internal const val SEARCH_DECORATION_ALPHA = 0.30

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
     * [fragmentRef] is "href#spanId" when active, null to clear. The fill uses [color]'s [argb]
     * verbatim — colour AND alpha are pre-baked; renderers must not compose additional alpha.
     */
    suspend fun applyReadaloud(
        fragmentRef: String?,
        quotes: Map<String, SentenceQuote>,
        color: HighlightColor,
    )

    /**
     * Applies or clears persisted annotation highlight decorations.
     * Empty [renders] clears the group. Same colour+alpha invariant as [applyReadaloud].
     */
    suspend fun applyAnnotations(
        renders: List<EpubReaderViewModel.HighlightRender>,
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
     * Continuous implementation groups all results by chapter href, builds a
     * [SearchHighlightsState], and delegates to [ContinuousHighlightTarget.applySearchHighlights].
     * Empty [results] or negative [activeIndex] clears all search highlights.
     */
    suspend fun applySearch(
        results: List<Locator>,
        activeIndex: Int,
    )

    /**
     * Highlights a single navigated-to search match using [SEARCH_ACTIVE_ARGB].
     * Readium implementation is a no-op (DecorableNavigator handles all matches via applySearch).
     * Continuous implementation injects a mark into the chapter WebView.
     */
    fun highlightSearchMatch(href: String, text: String)
}
