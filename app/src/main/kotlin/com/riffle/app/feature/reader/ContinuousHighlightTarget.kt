package com.riffle.app.feature.reader

/**
 * Narrow interface over [ContinuousReaderView]'s highlight-related methods.
 * Extracted so [ContinuousHighlightRenderer] can be tested on JVM without
 * instantiating the Android View.
 */
internal interface ContinuousHighlightTarget {
    /**
     * Highlight the sentence identified by [fragmentId] (an id in the chapter's DOM) if it is
     * non-null AND resolves to an element; otherwise fall back to a `window.find`-based text
     * search over [text]. Cadence always passes a `cd-N` id — its `.riffle-cd` spans are
     * chapter-unique — and Readaloud's sidecar-driven sentences pass their ABS-provided id.
     * The text fallback covers callers that don't yet plumb an id (older tests, migration paths).
     */
    fun highlightInChapter(href: String, fragmentId: String?, text: String, cssColor: String)
    fun clearHighlightInChapter(href: String)
    fun applyAnnotationHighlights(annotationsByHref: Map<String, List<AnnotationHighlight>>)
    /**
     * Apply search highlights across all currently-loaded chapters. Persists the state so
     * chapters entering the sliding window later (via onPageFinished) automatically receive
     * their marks. Pass null to clear all search highlights.
     */
    fun applySearchHighlights(state: SearchHighlightsState?)
}

/**
 * Full snapshot of the current search-highlight state.
 *
 * [resultsByHref] maps chapter hrefs to the unique highlighted-text snippets (up to 40 chars each)
 * for all search results in that chapter.  All results are displayed with [inactiveCssColor]; the
 * single active result ([activeHref] / [activeText] / [activeProgression]) is additionally
 * distinguished with [activeCssColor].
 */
internal data class SearchHighlightsState(
    val resultsByHref: Map<String, List<String>>,
    val activeHref: String?,
    val activeText: String?,
    val activeProgression: Float,
    val activeCssColor: String,
    val inactiveCssColor: String,
)
