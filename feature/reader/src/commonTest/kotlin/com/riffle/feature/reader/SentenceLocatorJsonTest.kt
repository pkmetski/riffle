package com.riffle.feature.reader

import com.riffle.core.domain.SentenceQuote
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sentence-anchor contract, now shared by Android's Readaloud decoration and iOS's Cadence
 * highlight. Android's `ReadaloudLocatorTest` pins the same claims through the `org.json` wrapper
 * it still hands to Readium-Android; these run on `iosSimulatorArm64` too, so the iOS decoration
 * path is covered by the same assertions rather than by a second, drifting copy.
 */
class SentenceLocatorJsonTest {

    private fun parse(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun aFragmentRefYieldsBothACssSelectorAndAFragment() {
        val json = parse(sentenceLocatorJson("OEBPS/chapter8.xhtml#cd-7", null))
        assertEquals("OEBPS/chapter8.xhtml", json["href"]?.jsonPrimitive?.content)
        assertEquals(SENTENCE_LOCATOR_TYPE, json["type"]?.jsonPrimitive?.content)
        val locations = json["locations"]!!.jsonObject
        assertEquals("#cd-7", locations["cssSelector"]?.jsonPrimitive?.content)
        assertEquals(listOf("cd-7"), locations["fragments"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    /**
     * The text block is the TextQuoteAnchor fallback Readium uses when the span is not in the
     * rendered DOM. Dropping it is how a sentence highlight silently stops appearing on
     * publications that strip the span.
     */
    @Test
    fun aQuoteBecomesTheTextAnchorWithItsNeighbouringContext() {
        val json = parse(
            sentenceLocatorJson(
                "c.xhtml#cd-1",
                SentenceQuote(before = "…prev. ", highlight = "Actual aliens.", after = " Next…"),
            ),
        )
        val text = json["text"]!!.jsonObject
        assertEquals("…prev. ", text["before"]?.jsonPrimitive?.content)
        assertEquals("Actual aliens.", text["highlight"]?.jsonPrimitive?.content)
        assertEquals(" Next…", text["after"]?.jsonPrimitive?.content)
    }

    @Test
    fun withoutAQuoteThereIsNoTextBlock() {
        val json = parse(sentenceLocatorJson("c.xhtml#cd-1", null))
        assertNull(json["text"], "an absent quote must not become an empty text anchor")
    }

    @Test
    fun aBareHrefYieldsNoCssSelector() {
        val json = parse(sentenceLocatorJson("c.xhtml", null))
        assertFalse(json["locations"]!!.jsonObject.containsKey("cssSelector"))
        assertNull(json["text"])
    }

    @Test
    fun quotesAndBackslashesInTheTextAreEscaped() {
        // Hand-rolled string concatenation is what usually breaks here; the result has to survive
        // a round trip through a real JSON parser on both platforms.
        val json = parse(
            sentenceLocatorJson(
                "c.xhtml#cd-1",
                SentenceQuote(before = "", highlight = """He said "hi" \ then left""", after = ""),
            ),
        )
        assertEquals(
            """He said "hi" \ then left""",
            json["text"]!!.jsonObject["highlight"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun theLocatorIsWhatReadiumsDecorationParserAccepts() {
        // Guards the shape rather than the bytes: a locator without href or type is rejected by
        // Readium's Locator(json:) on both toolkits and the decoration silently never appears.
        val json = parse(sentenceLocatorJson("c.xhtml#cd-1", null))
        assertTrue(json.containsKey("href"))
        assertTrue(json.containsKey("type"))
        assertTrue(json.containsKey("locations"))
    }
}
