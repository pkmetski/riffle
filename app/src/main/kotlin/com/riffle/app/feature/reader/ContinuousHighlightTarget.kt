package com.riffle.app.feature.reader

/**
 * Narrow interface over [ContinuousReaderView]'s highlight-related methods.
 * Extracted so [ContinuousHighlightRenderer] can be tested on JVM without
 * instantiating the Android View.
 */
internal interface ContinuousHighlightTarget {
    fun highlightInChapter(href: String, text: String, cssColor: String)
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
