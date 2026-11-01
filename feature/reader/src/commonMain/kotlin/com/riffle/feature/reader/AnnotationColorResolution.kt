package com.riffle.feature.reader

import com.riffle.core.models.HighlightColor

fun resolveAnnotationSheetColor(
    persistedColor: String?,
    lastUsedHighlightColor: HighlightColor,
    lastUsedColorIsNone: Boolean,
): HighlightColor? = if (persistedColor == null) {
    if (lastUsedColorIsNone) null else lastUsedHighlightColor
} else {
    if (persistedColor.isEmpty()) null else HighlightColor.fromToken(persistedColor)
}
