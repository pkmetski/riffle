package com.riffle.feature.reader

import com.riffle.core.models.EmphasisStyle

/**
 * A single annotation mark to be injected into a continuous-mode chapter WebView via
 * [ContinuousAnnotationJs.applyAnnotationHighlightsJs].
 */
data class AnnotationHighlight(
    val id: String,
    val text: String,
    val cssColor: String,
    val hasNote: Boolean = false,
    val before: String = "",
    val after: String = "",
    val suppressMarkClick: Boolean = false,
    val emphasisStyles: Set<EmphasisStyle> = emptySet(),
)
