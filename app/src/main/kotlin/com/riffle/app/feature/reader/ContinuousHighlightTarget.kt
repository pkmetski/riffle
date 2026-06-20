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
}
