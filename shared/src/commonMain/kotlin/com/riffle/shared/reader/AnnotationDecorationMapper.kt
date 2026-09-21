package com.riffle.shared.reader

import com.riffle.core.data.annotationDecorationLocatorJson
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation
import com.riffle.core.models.EmphasisStyle
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

/**
 * The locator every decoration for [a] is anchored with.
 *
 * Text-quote anchored, because that is the only thing Readium's `rangeFromLocator` can resolve
 * for a text range — see [annotationDecorationLocatorJson] for why the previous cfi-only locator
 * placed nothing at all.
 */
internal fun annotationDecorationLocator(a: Annotation): String =
    annotationDecorationLocatorJson(
        chapterHref = a.chapterHref,
        cfi = a.cfi,
        progression = a.progression,
        textSnippet = a.textSnippet,
        textBefore = a.textBefore,
        textAfter = a.textAfter,
        fragmentAnchor = a.fragmentAnchor,
    )

/** Maps a stored [Annotation] to a [NavigatorDecoration.Highlight], or null if not a highlight type. */
internal fun annotationToHighlightDecoration(a: Annotation): NavigatorDecoration.Highlight? {
    if (a.type != AnnotationEntity.TYPE_HIGHLIGHT) return null
    // ADR 0056 §4: a highlight whose colour was cleared with the `∅` swatch persists with an
    // EMPTY token so its note and its sibling emphasis rows survive. Android emits a
    // fully-transparent decoration for it (`ReadiumHighlightRenderer.applyAnnotations`: tint
    // `0x00000000`) — nothing paints, but the range stays tappable so the user can get the
    // actions sheet back. Resolving the empty token through `HighlightColor.fromToken` instead
    // would repaint the wash the user just removed, in YELLOW, and read as correct in review.
    val transparent = a.color.isEmpty()
    return NavigatorDecoration.Highlight(
        id = a.id,
        locatorJson = annotationDecorationLocator(a),
        color = if (transparent) TRANSPARENT_HEX else highlightColorHex(a.color),
        alpha = if (transparent) 0f else highlightColorAlpha(a.color),
    )
}

/** The rgb half of Android's `0x00000000` empty-colour tint; the alpha is what makes it invisible. */
private const val TRANSPARENT_HEX = "#000000"

/**
 * The margin glyph that tells the reader a highlight carries a note.
 *
 * Android paints one per noted highlight in its own decoration group (`"annotation-notes"`,
 * `NOTE_GLYPH_DECORATION_GROUP`) so tapping the glyph opens the read-only note view while
 * tapping the wash opens the full actions sheet. iOS had no producer at all, which is why a
 * highlight-with-note was indistinguishable from a plain one.
 */
internal fun annotationToNoteGlyphDecoration(a: Annotation): NavigatorDecoration.NoteGlyph? {
    if (a.note.isNullOrBlank()) return null
    return NavigatorDecoration.NoteGlyph(id = a.id, locatorJson = annotationDecorationLocator(a))
}

/** Maps a stored [Annotation] bookmark to a [NavigatorDecoration.Bookmark]. */
internal fun annotationToBookmarkDecoration(a: Annotation): NavigatorDecoration.Bookmark =
    NavigatorDecoration.Bookmark(
        id = a.id,
        locatorJson = annotationDecorationLocator(a),
    )

/**
 * Maps a `TYPE_EMPHASIS` row to the decoration half of ADR 0056.
 *
 * Returns null when the row carries only [com.riffle.core.models.EmphasisStyle.BOLD] /
 * `ITALIC` — those reflow text and are applied by DOM injection instead, so emitting a
 * decoration for them would add an invisible, un-tappable overlay.
 */
internal fun annotationToEmphasisDecoration(a: Annotation): NavigatorDecoration.Emphasis? {
    if (a.type != AnnotationEntity.TYPE_EMPHASIS) return null
    val styles = a.emphasisStyles?.filter { it in DECORATION_RENDERED_EMPHASIS }?.toSet().orEmpty()
    if (styles.isEmpty()) return null
    return NavigatorDecoration.Emphasis(
        id = a.id,
        locatorJson = annotationDecorationLocator(a),
        styles = styles,
    )
}

/** The two emphasis styles that can be drawn over text without changing its metrics. */
private val DECORATION_RENDERED_EMPHASIS = setOf(EmphasisStyle.UNDERLINE, EmphasisStyle.STRIKE)
