package com.riffle.shared.reader

import com.riffle.core.data.annotationLocatorJson
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation
import com.riffle.core.models.HighlightColor
import com.riffle.feature.reader.NavigatorDecoration

/**
 * `AnnotationEntity.color` persists a [HighlightColor] *token* — `"yellow"`, `"green"`, `"blue"`,
 * `"red"` — never a hex string. The Swift bridge parses the decoration's `color` with
 * `Scanner.scanHexInt64`, which cannot read a token and silently yields `rgb = 0`, so every
 * highlight rendered black. Resolve the token the way Android does
 * (`ReadiumHighlightRenderer` → `HighlightColor.fromToken(...).argb`) before it crosses the bridge.
 *
 * [HighlightColor.argb] is the FINAL rendered value with alpha baked in (0x80, ~50%), so the
 * alpha is carried across rather than left at the old hardcoded 0.4f — that is what makes the iOS
 * highlight the same pixel value as the Android one and as the settings swatch.
 */
internal fun highlightColorHex(token: String): String {
    val argb = HighlightColor.fromToken(token).argb
    return "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
}

/** Baked-in alpha of [HighlightColor.argb], as the 0..1 fraction the navigator bridge expects. */
internal fun highlightColorAlpha(token: String): Float =
    ((HighlightColor.fromToken(token).argb ushr 24) and 0xFF) / 255f

/** Maps a stored [Annotation] to a [NavigatorDecoration.Highlight], or null if not a highlight type. */
internal fun annotationToHighlightDecoration(a: Annotation): NavigatorDecoration.Highlight? {
    if (a.type != AnnotationEntity.TYPE_HIGHLIGHT) return null
    return NavigatorDecoration.Highlight(
        id = a.id,
        locatorJson = annotationLocatorJson(a.chapterHref, a.cfi, a.progression),
        color = highlightColorHex(a.color),
        alpha = highlightColorAlpha(a.color),
    )
}

/** Maps a stored [Annotation] bookmark to a [NavigatorDecoration.Bookmark]. */
internal fun annotationToBookmarkDecoration(a: Annotation): NavigatorDecoration.Bookmark =
    NavigatorDecoration.Bookmark(
        id = a.id,
        locatorJson = annotationLocatorJson(a.chapterHref, a.cfi, a.progression),
    )
