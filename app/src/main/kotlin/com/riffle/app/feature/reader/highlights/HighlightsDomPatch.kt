package com.riffle.app.feature.reader.highlights

typealias HighlightsDomPatch = com.riffle.feature.reader.highlights.HighlightsDomPatch

internal fun buildEmphasisInlineCss(styles: Set<com.riffle.core.models.EmphasisStyle>): String =
    com.riffle.feature.reader.highlights.buildEmphasisInlineCss(styles)
