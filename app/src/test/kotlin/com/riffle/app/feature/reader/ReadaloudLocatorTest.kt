package com.riffle.app.feature.reader

import com.riffle.core.domain.SentenceQuote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the readaloud highlight against the regression we chased for a long time: Readium strips the
 * sentence span from the served HTML, so a cssSelector-only locator draws nothing. The highlight only
 * survives because the locator also carries the sentence TEXT, which Readium falls back to. If anyone
 * drops the text block, these fail.
 */
class ReadaloudLocatorTest {

    @Test
    fun `with a quote, the locator JSON carries the sentence text for the text-anchor fallback`() {
        val json = readaloudLocatorJson(
            "OEBPS/xhtml/c008.xhtml#c008-s0",
            SentenceQuote(before = "…prev. ", highlight = "Actual aliens.", after = " Next…"),
        )
        val text = json.getJSONObject("text")
        assertEquals("Actual aliens.", text.getString("highlight"))
        assertEquals("…prev. ", text.getString("before"))
        assertEquals(" Next…", text.getString("after"))
        // href + cssSelector still present as the fast path
        assertEquals("OEBPS/xhtml/c008.xhtml", json.getString("href"))
        assertEquals("#c008-s0", json.getJSONObject("locations").getString("cssSelector"))
    }

    @Test
    fun `without a quote, there is no text block (cssSelector only)`() {
        val json = readaloudLocatorJson("text/part0012_split_001.html#id259-s0", null)
        assertFalse("must not carry a text block when no quote is known", json.has("text"))
        assertEquals("#id259-s0", json.getJSONObject("locations").getString("cssSelector"))
    }

    @Test
    fun `a bare href with no fragment yields no cssSelector and no text`() {
        val json = readaloudLocatorJson("text/part0012_split_001.html", null)
        assertFalse(json.getJSONObject("locations").has("cssSelector"))
        assertFalse(json.has("text"))
    }
}
