package com.riffle.feature.reader

import com.riffle.core.models.Annotation

/**
 * The one-line label an annotations list shows for [annotation].
 *
 * Keyed on [rowKindFor] rather than on `annotation.type` directly so a row that the panel
 * classifies as an image (a highlight whose only content is an embedded figure) reads as one
 * here too — the classification and the label cannot drift apart.
 *
 * A note is marked with a leading glyph because the list is the only place a note is visible
 * without opening the annotation, and a highlight-with-note was otherwise indistinguishable
 * from a plain one.
 */
fun annotationListLabel(annotation: Annotation, maxSnippet: Int = 80): String {
    val body = when (rowKindFor(annotation)) {
        RowKind.Bookmark -> annotation.bookmarkTitle.ifBlank { annotation.textSnippet }
        RowKind.Image -> annotation.textSnippet.ifBlank { "Figure" }
        RowKind.Highlight -> annotation.textSnippet
    }.trim().replace(WHITESPACE_RUN, " ")
    val clipped = if (body.length > maxSnippet) body.take(maxSnippet).trimEnd() + "…" else body
    val prefix = when (rowKindFor(annotation)) {
        RowKind.Bookmark -> "🔖 "
        RowKind.Image -> "🖼 "
        RowKind.Highlight -> ""
    }
    val noteMark = if (!annotation.note.isNullOrBlank()) " 📝" else ""
    return prefix + clipped + noteMark
}

private val WHITESPACE_RUN = Regex("\\s+")
