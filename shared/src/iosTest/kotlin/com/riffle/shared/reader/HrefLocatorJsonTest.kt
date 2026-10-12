package com.riffle.shared.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HrefLocatorJsonTest {

    @Test
    fun basicHrefProducesValidJson() {
        val json = hrefLocatorJson("OEBPS/chapter01.xhtml")
        assertTrue(json.contains("\"href\":\"OEBPS/chapter01.xhtml\""))
        assertTrue(json.contains("\"progression\":0.0"))
        assertTrue(json.contains("\"type\":\"application/xhtml+xml\""))
    }

    @Test
    fun doubleQuoteInHrefIsEscaped() {
        val json = hrefLocatorJson("chapter\"1\".xhtml")
        assertTrue(json.contains("chapter\\\"1\\\""))
    }

    @Test
    fun backslashInHrefIsEscaped() {
        val json = hrefLocatorJson("OEBPS\\chapter.xhtml")
        assertTrue(json.contains("OEBPS\\\\chapter.xhtml"))
    }

    @Test
    fun plainHrefRoundTripsAsJsonString() {
        val href = "OEBPS/Text/part1/chapter-01.xhtml"
        val json = hrefLocatorJson(href)
        // Verify the href literal is embedded verbatim (no other escaping needed for plain paths).
        assertEquals(
            """{"href":"$href","type":"application/xhtml+xml","locations":{"progression":0.0}}""",
            json,
        )
    }
}
