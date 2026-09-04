package com.riffle.shared.reader

import com.riffle.core.data.annotationLocatorJson
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation
import com.riffle.feature.reader.NavigatorDecoration

/** Maps a stored [Annotation] to a [NavigatorDecoration.Highlight], or null if not a highlight type. */
internal fun annotationToHighlightDecoration(a: Annotation): NavigatorDecoration.Highlight? {
    if (a.type != AnnotationEntity.TYPE_HIGHLIGHT) return null
    return NavigatorDecoration.Highlight(
        id = a.id,
        locatorJson = annotationLocatorJson(a.chapterHref, a.cfi, a.progression),
        color = a.color.ifBlank { "#FFFF00" },
        alpha = 0.4f,
    )
}

/** Maps a stored [Annotation] bookmark to a [NavigatorDecoration.Bookmark]. */
internal fun annotationToBookmarkDecoration(a: Annotation): NavigatorDecoration.Bookmark =
    NavigatorDecoration.Bookmark(
        id = a.id,
        locatorJson = annotationLocatorJson(a.chapterHref, a.cfi, a.progression),
    )
