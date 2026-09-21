package com.riffle.shared.reader

import com.riffle.feature.reader.NOTE_GLYPH_DECORATION_GROUP

/**
 * The decoration group names the reader applies decorations under, in one place both languages
 * can read.
 *
 * Readium keys everything on the group string: `apply(decorations:in:)` replaces a group
 * atomically, `observeDecorationInteractions(inGroup:)` decides which groups are tappable, and
 * `didActivateDecoration` reports the group back so the host knows whether a tap was on a
 * highlight or on a note glyph. A mistyped literal on either side of the bridge does not fail —
 * the decorations simply land in a group nobody observes and the tap goes nowhere, which is the
 * "typo reads as correct in review" shape AGENTS.md calls out.
 *
 * An `object` with plain `val`s rather than top-level `const val`s so the Swift side can read
 * them as `ReaderDecorationGroups.shared.noteGlyphs` — the same pattern
 * [ReaderHighlightDefaults] uses for the fallback tint.
 */
object ReaderDecorationGroups {
    /** User highlights (and the transparent tap target of a `∅`-coloured one). */
    val highlights: String = "highlights"

    /** Bookmarked paragraphs, drawn as a gutter bar. */
    val bookmarks: String = "bookmarks"

    /** Margin glyph for a highlight carrying a note. Same value Android uses. */
    val noteGlyphs: String = NOTE_GLYPH_DECORATION_GROUP

    /** ADR 0056 underline / strike layers. */
    val emphasis: String = "emphasis"

    /** In-page search-result marks. */
    val search: String = "search"

    /** Cadence's "now reading" sentence. */
    val cadence: String = DECORATION_GROUP_CADENCE

    /** Every group whose decorations must respond to a tap. */
    val activable: List<String> = listOf(highlights, bookmarks, noteGlyphs)
}
