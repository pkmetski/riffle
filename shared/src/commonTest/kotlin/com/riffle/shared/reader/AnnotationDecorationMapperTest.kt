package com.riffle.shared.reader

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation
import com.riffle.core.models.HighlightColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnnotationDecorationMapperTest {

    private fun annotation(
        id: String = "ann-1",
        type: String = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi: String = "epubcfi(/6/4!/4/2/16)",
        chapterHref: String = "ch1.xhtml",
        progression: Double = 0.5,
        color: String = HighlightColor.YELLOW.token,
    ) = Annotation(
        id = id,
        sourceId = "src-1",
        itemId = "item-1",
        type = type,
        cfi = cfi,
        color = color,
        note = null,
        textSnippet = "",
        textBefore = "",
        textAfter = "",
        chapterHref = chapterHref,
        spineIndex = 0,
        progression = progression,
        bookmarkTitle = "",
        createdAt = 0L,
        updatedAt = 0L,
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

    @Test
    fun blankColorFallsBackToYellow() {
        val decoration = annotationToHighlightDecoration(annotation(color = ""))
        assertEquals("#FBBF24", decoration?.color)
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
