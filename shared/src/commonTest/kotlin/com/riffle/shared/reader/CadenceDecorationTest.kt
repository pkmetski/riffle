package com.riffle.shared.reader

import com.riffle.core.domain.SentenceQuote
import com.riffle.core.models.HighlightColor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cadence's "currently reading" decoration, the thing the reader actually sees.
 *
 * Runs on `iosSimulatorArm64`; this is the exact mapper `IosEpubReaderScreen` calls on every
 * sentence change.
 */
class CadenceDecorationTest {

    private val quote = SentenceQuote(before = "…prev. ", highlight = "Actual aliens.", after = " Next…")

    /**
     * The colour must be resolved through [HighlightColor], never passed as the stored token.
     * The Swift bridge parses it with `Scanner.scanHexInt64`, which cannot read `"yellow"` and
     * yields rgb = 0 — the bug that rendered every iOS highlight black (#1071 §6). Reverting
     * `highlightColorHex(color.token)` to `color.token` makes this assertion red.
     */
    @Test
    fun theColourIsAHexTripletNotTheStoredToken() {
        val decoration = cadenceDecoration("c.xhtml#cd-1", quote, HighlightColor.GREEN)
        assertTrue(decoration.color.startsWith("#"), "got '${decoration.color}'")
        assertEquals(7, decoration.color.length)
        assertEquals(
            "#" + (HighlightColor.GREEN.argb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0'),
            decoration.color,
        )
    }

    /**
     * The alpha is the one baked into [HighlightColor.argb] (0x80), so the sentence highlight is
     * the same pixel value as the swatch in the Settings picker and as Android's.
     */
    @Test
    fun theAlphaIsTheOneBakedIntoThePalette() {
        val decoration = cadenceDecoration("c.xhtml#cd-1", quote, HighlightColor.YELLOW)
        assertEquals(((HighlightColor.YELLOW.argb ushr 24) and 0xFF) / 255f, decoration.alpha)
    }

    @Test
    fun theLocatorAnchorsOnTheInjectedSpanAndCarriesTheSentenceText() {
        val decoration = cadenceDecoration("OEBPS/c8.xhtml#cd-7", quote, HighlightColor.YELLOW)
        val json = Json.parseToJsonElement(decoration.locatorJson).jsonObject
        assertEquals("OEBPS/c8.xhtml", json["href"]?.jsonPrimitive?.content)
        assertEquals("#cd-7", json["locations"]!!.jsonObject["cssSelector"]?.jsonPrimitive?.content)
        assertEquals("Actual aliens.", json["text"]!!.jsonObject["highlight"]?.jsonPrimitive?.content)
    }

    /**
     * One constant id and one group: the reader replaces the whole group on every sentence, so
     * the highlight moves instead of accumulating. A per-sentence id would leave the previous
     * sentences lit until the group was cleared.
     */
    @Test
    fun everySentenceReusesTheSameDecorationId() {
        assertEquals(
            cadenceDecoration("c.xhtml#cd-1", quote, HighlightColor.RED).id,
            cadenceDecoration("c.xhtml#cd-2", quote, HighlightColor.RED).id,
        )
        assertEquals(CADENCE_DECORATION_ID, cadenceDecoration("c.xhtml#cd-1", quote, HighlightColor.RED).id)
    }

    @Test
    fun cadenceHasItsOwnGroupSoItCannotClobberTheAnnotationHighlights() {
        // Mechanical rename only: the group names moved into ReaderDecorationGroups so the
        // Swift side can read the same values. The claim — Cadence must not share a group with
        // any annotation surface — is unchanged, and now covers all four of them.
        val annotationGroups = listOf(
            ReaderDecorationGroups.highlights,
            ReaderDecorationGroups.bookmarks,
            ReaderDecorationGroups.noteGlyphs,
            ReaderDecorationGroups.emphasis,
        )
        annotationGroups.forEach { group ->
            assertTrue(ReaderDecorationGroups.cadence != group, "cadence must not share \"$group\"")
        }
        assertEquals(annotationGroups.size, annotationGroups.toSet().size, "groups must be distinct")
    }

    @Test
    fun aSentenceWithNoKnownQuoteStillAnchorsOnItsSpan() {
        val json = Json.parseToJsonElement(
            cadenceDecoration("c.xhtml#cd-1", null, HighlightColor.BLUE).locatorJson,
        ).jsonObject
        assertEquals("#cd-1", json["locations"]!!.jsonObject["cssSelector"]?.jsonPrimitive?.content)
    }
}
