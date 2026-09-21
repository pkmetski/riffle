package com.riffle.shared.reader

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation
import com.riffle.core.models.EmphasisStyle
import com.riffle.core.models.HighlightColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnotationDecorationMapperTest {

    private fun annotation(
        id: String = "ann-1",
        type: String = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi: String = "epubcfi(/6/4!/4/2/16)",
        chapterHref: String = "ch1.xhtml",
        progression: Double = 0.5,
        color: String = HighlightColor.YELLOW.token,
        note: String? = null,
        snippet: String = "",
        before: String = "",
        after: String = "",
        fragmentAnchor: String? = null,
        styles: Set<EmphasisStyle>? = null,
    ) = Annotation(
        id = id,
        sourceId = "src-1",
        itemId = "item-1",
        type = type,
        cfi = cfi,
        color = color,
        note = note,
        textSnippet = snippet,
        textBefore = before,
        textAfter = after,
        chapterHref = chapterHref,
        spineIndex = 0,
        progression = progression,
        bookmarkTitle = "",
        createdAt = 0L,
        updatedAt = 0L,
        emphasisStyles = styles,
        fragmentAnchor = fragmentAnchor,
    )

    // These fixtures used to pass "#FFFF00"/"#FF0000". AnnotationEntity.color never holds a hex
    // string — it holds a HighlightColor token — so the suite was green while production shipped
    // the raw token across the bridge to a hex scanner, which parsed it as 0 and painted every
    // highlight black. The fixtures below are what the DB actually stores.

    @Test
    fun highlightAnnotationMapsToHighlightDecoration() {
        val decoration = annotationToHighlightDecoration(annotation(type = AnnotationEntity.TYPE_HIGHLIGHT))
        assertEquals("ann-1", decoration?.id)
        assertEquals("#FBBF24", decoration?.color, "yellow token must resolve to HighlightColor.YELLOW's rgb")
    }

    @Test
    fun everyPaletteTokenResolvesToSixDigitHexAndTheBakedAlpha() {
        for (color in HighlightColor.entries) {
            val decoration = annotationToHighlightDecoration(annotation(color = color.token))
            val expectedHex = "#" + (color.argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
            assertEquals(expectedHex, decoration?.color, "${color.token} must resolve to its palette rgb")
            assertEquals(
                ((color.argb ushr 24) and 0xFF) / 255f,
                decoration?.alpha,
                "${color.token} must carry the alpha baked into HighlightColor.argb",
            )
        }
    }

    @Test
    fun aTokenNeverReachesTheBridgeVerbatim() {
        // The Swift bridge parses this with Scanner.scanHexInt64; a token yields rgb = 0 (black).
        for (color in HighlightColor.entries) {
            val rendered = annotationToHighlightDecoration(annotation(color = color.token))?.color
            assertEquals(true, rendered?.startsWith("#") == true, "must be a hex string, was: $rendered")
            assertEquals(7, rendered?.length, "must be #RRGGBB, was: $rendered")
        }
    }

    @Test
    fun bookmarkAnnotationReturnedNullFromHighlightMapper() {
        // bookmark type must not appear in highlights group
        val result = annotationToHighlightDecoration(annotation(type = AnnotationEntity.TYPE_BOOKMARK))
        assertNull(result)
    }

    @Test
    fun emphasisAnnotationReturnedNullFromHighlightMapper() {
        val result = annotationToHighlightDecoration(annotation(type = AnnotationEntity.TYPE_EMPHASIS))
        assertNull(result)
    }

    /**
     * Replaces `blankColorFallsBackToYellow`, whose claim — an empty colour token renders as
     * YELLOW — was wrong and is retired here.
     *
     * The behaviour that test was really pinning ("a token the palette does not know must not
     * render black") is unchanged and still covered by
     * [anUnknownTokenFallsBackToTheDefaultRatherThanBlack]. An EMPTY token is not an unknown
     * token: ADR 0056 §4 uses it for a highlight whose colour the user cleared with the `∅`
     * swatch so its note and emphasis survive. Android emits a fully-transparent decoration for
     * that row (`ReadiumHighlightRenderer.applyAnnotations`, tint `0x00000000`) — nothing
     * paints, but the range stays tappable. Resolving it to YELLOW, as the old assertion
     * demanded, would repaint the wash the user had just removed.
     */
    @Test
    fun emptyColorPaintsNothingButStaysTappable() {
        val decoration = annotationToHighlightDecoration(annotation(color = ""))
        assertNotNull(decoration, "the range must still be decorated, or the user cannot tap it back open")
        assertEquals(0f, decoration.alpha, "a cleared highlight must paint nothing")
        assertEquals(
            "#000000",
            decoration.color,
            "the rgb half of Android's 0x00000000 empty-colour tint",
        )
    }

    @Test
    fun anUnknownTokenFallsBackToTheDefaultRatherThanBlack() {
        // Forward-compat: a colour added by a newer client must not render as black.
        val decoration = annotationToHighlightDecoration(annotation(color = "chartreuse"))
        assertEquals("#FBBF24", decoration?.color)
    }

    @Test
    fun bookmarkMapperProducesBookmarkDecoration() {
        val decoration = annotationToBookmarkDecoration(annotation(id = "bm-1"))
        assertEquals("bm-1", decoration.id)
    }

    @Test
    fun locatorJsonContainsHrefAndCfiFragment() {
        val decoration = annotationToHighlightDecoration(
            annotation(cfi = "epubcfi(/6/4!/4/2/16)", chapterHref = "ch1.xhtml")
        )
        val locator = decoration?.locatorJson ?: ""
        assertEquals(true, locator.contains("ch1.xhtml"), "locator must contain href: $locator")
        assertEquals(true, locator.contains("/4/2/16"), "locator must contain CFI fragment: $locator")
    }

    // ── The locator Readium can actually resolve ────────────────────────────────────────────
    //
    // Readium's `rangeFromLocator` (utils.js) reads text.highlight / cssSelector / fragments and
    // NEVER locations.cfi. A cfi-only locator makes `DecorationGroup.add` log "Can't locate DOM
    // range" and drop the decoration, so every one of these assertions is the difference between
    // an annotation appearing on the page and disappearing silently.

    @Test
    fun highlightLocatorCarriesTheTextQuoteAnchorReadiumResolvesOn() {
        val decoration = annotationToHighlightDecoration(
            annotation(snippet = "the selected words", before = "context before ", after = " context after"),
        )
        val locator = decoration?.locatorJson ?: ""
        assertTrue(locator.contains("\"text\":"), "no text anchor, so Readium cannot place it: $locator")
        assertTrue(locator.contains("\"highlight\":\"the selected words\""), locator)
        assertTrue(locator.contains("\"before\":\"context before \""), locator)
        assertTrue(locator.contains("\"after\":\" context after\""), locator)
    }

    @Test
    fun aSnippetSpanningAParagraphBreakStaysValidJson() {
        // A raw newline inside a JSON string is a parse error; Swift's JSONValue(jsonString:)
        // rejects the whole locator and the highlight vanishes — indistinguishable from "the
        // range could not be found".
        val locator = annotationToHighlightDecoration(
            annotation(snippet = "first line\nsecond line", before = "a\tb"),
        )?.locatorJson ?: ""
        assertFalse(locator.contains('\n'), "raw newline in locator JSON: $locator")
        assertFalse(locator.contains('\t'), "raw tab in locator JSON: $locator")
        assertTrue(locator.contains("""first line\nsecond line"""), locator)
    }

    @Test
    fun aBookmarkWithAFragmentAnchorLocatesOnItsElementNotOnItsText() {
        // PR #671: fragmentAnchor was persisted and never read on iOS. As `locations.fragments`
        // it is the only field of the three Readium consults that points at the exact paragraph.
        val locator = annotationToBookmarkDecoration(
            annotation(type = AnnotationEntity.TYPE_BOOKMARK, snippet = "page top text", fragmentAnchor = "p-42"),
        ).locatorJson
        assertTrue(locator.contains("""fragments":["p-42"]"""), locator)
        assertFalse(
            locator.contains("\"text\":"),
            "a bookmark's snippet is the page's first words, not a chosen range — a quote " +
                "search for it would drift to the first matching occurrence: $locator",
        )
    }

    @Test
    fun aLegacyBookmarkWithoutAnAnchorStillHasSomethingToLocateOn() {
        val locator = annotationToBookmarkDecoration(
            annotation(type = AnnotationEntity.TYPE_BOOKMARK, snippet = "page top text", progression = 0.42),
        ).locatorJson
        assertTrue(locator.contains("\"highlight\":\"page top text\""), locator)
        assertTrue(locator.contains("\"progression\":0.42"), locator)
    }

    // ── Note glyph ──────────────────────────────────────────────────────────────────────────

    @Test
    fun aHighlightWithANoteGetsAGlyphAndOneWithoutDoesNot() {
        assertNotNull(annotationToNoteGlyphDecoration(annotation(note = "remember this")))
        assertNull(annotationToNoteGlyphDecoration(annotation(note = null)))
        assertNull(annotationToNoteGlyphDecoration(annotation(note = "   ")))
    }

    // ── Emphasis (ADR 0056) ─────────────────────────────────────────────────────────────────

    @Test
    fun onlyUnderlineAndStrikeBecomeDecorations() {
        // BOLD and ITALIC reflow text; an overlay cannot. Emitting a decoration for them adds an
        // invisible box that still swallows taps.
        val boldOnly = annotationToEmphasisDecoration(
            annotation(type = AnnotationEntity.TYPE_EMPHASIS, styles = setOf(EmphasisStyle.BOLD)),
        )
        assertNull(boldOnly, "bold is applied by DOM injection, not by a decoration")

        val mixed = annotationToEmphasisDecoration(
            annotation(
                type = AnnotationEntity.TYPE_EMPHASIS,
                styles = setOf(EmphasisStyle.BOLD, EmphasisStyle.UNDERLINE, EmphasisStyle.STRIKE),
            ),
        )
        assertEquals(setOf(EmphasisStyle.UNDERLINE, EmphasisStyle.STRIKE), mixed?.styles)
    }

    @Test
    fun aHighlightIsNeverMappedAsEmphasis() {
        assertNull(
            annotationToEmphasisDecoration(
                annotation(type = AnnotationEntity.TYPE_HIGHLIGHT, styles = setOf(EmphasisStyle.UNDERLINE)),
            ),
        )
    }

    /**
     * The Swift bridge's fallback for a payload missing `color`/`alpha` reads these, so the
     * fallback can no longer be a literal that drifts away from the palette. It used to be
     * `"#FFFF00"` at alpha 0.4 — a colour `HighlightColor` does not contain.
     */
    @Test
    fun theBridgeFallbackIsThePaletteDefaultAndNotAHardcodedYellow() {
        assertEquals(highlightColorHex(HighlightColor.DEFAULT.token), ReaderHighlightDefaults.highlightHex)
        assertEquals(highlightColorAlpha(HighlightColor.DEFAULT.token), ReaderHighlightDefaults.highlightAlpha)
        assertEquals("#FBBF24", ReaderHighlightDefaults.highlightHex)
        assertEquals(0x80 / 255f, ReaderHighlightDefaults.highlightAlpha)
    }
}
