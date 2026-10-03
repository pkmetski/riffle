package com.riffle.feature.reader

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation
import com.riffle.core.models.EmbeddedFigure

/**
 * Which visual variant an annotations-panel row renders, derived from [Annotation.type].
 *
 * Platform-neutral so Android's Compose `AnnotationRow` and the iOS annotations panel route on
 * the same decision rather than each re-deriving it.
 */
enum class RowKind { Bookmark, Highlight, Image }

/**
 * Pure type→row-variant selector, extracted so the routing decision is unit-testable without
 * standing up Compose. Unknown/legacy types fall back to [RowKind.Highlight].
 */
fun rowKindFor(annotation: Annotation): RowKind = when (annotation.type) {
    AnnotationEntity.TYPE_BOOKMARK -> RowKind.Bookmark
    // A HIGHLIGHT whose selection enclosed a figure with captured bytes gets the Image row so
    // the panel shows the figure thumbnail — same visual weight as a standalone TYPE_IMAGE. The
    // text snippet still renders alongside via the row's title column. Highlights without a
    // captured figure (or figures without bytes) fall back to the plain color-dot row.
    AnnotationEntity.TYPE_HIGHLIGHT ->
        if (annotation.embeddedFigures.orEmpty().any { !it.imageBytes.isNullOrBlank() })
            RowKind.Image else RowKind.Highlight
    AnnotationEntity.TYPE_IMAGE -> RowKind.Image
    else -> RowKind.Highlight
}

const val BOOKMARK_TITLE_MAX_LINES = 2
const val HIGHLIGHT_SNIPPET_MAX_LINES = 6

fun maxLinesForAnnotationTitle(type: String): Int =
    if (type == AnnotationEntity.TYPE_BOOKMARK) BOOKMARK_TITLE_MAX_LINES else HIGHLIGHT_SNIPPET_MAX_LINES

/**
 * Platform-neutral figure descriptor used by the shared annotations panel row.
 * [bytesUri] is a `data:image/…;base64,…` URI; [caption] is the figure's caption text.
 */
data class InlineFigure(val bytesUri: String, val caption: String, val charOffset: Long?)

/** Collects the displayable inline figures for [annotation], same logic as the Android panel. */
fun collectInlineFigures(annotation: Annotation): List<InlineFigure> {
    val fromEmbedded = annotation.embeddedFigures
        ?.sortedBy(EmbeddedFigure::order)
        ?.mapNotNull { fig ->
            val bytes = fig.imageBytes
            if (!bytes.isNullOrBlank()) InlineFigure(bytes, fig.caption, fig.charOffset) else null
        }
        ?: emptyList()
    if (fromEmbedded.isNotEmpty()) return fromEmbedded
    val bytes = annotation.imageBytes
    return if (!bytes.isNullOrBlank()) listOf(InlineFigure(bytes, "", charOffset = null)) else emptyList()
}
