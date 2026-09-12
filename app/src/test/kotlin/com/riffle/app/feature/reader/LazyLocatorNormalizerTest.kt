package com.riffle.app.feature.reader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LazyLocatorNormalizerTest {

    private fun locatorJson(href: String, type: String = "application/xhtml+xml") =
        """{"href":"$href","type":"$type","locations":{"progression":0.23}}"""

    @Test
    fun `strips OEBPS prefix when bare path is in known hrefs`() {
        val json = locatorJson("OEBPS/ch09.html")
        val known = setOf("ch09.html", "ch10.html")
        val result = normalizeLocatorHrefForLazyPub(json, known)
        assertEquals("ch09.html", JSONObject(result).getString("href"))
    }

    @Test
    fun `preserves other locator fields when stripping OEBPS prefix`() {
        val json = locatorJson("OEBPS/ch09.html")
        val known = setOf("ch09.html")
        val result = normalizeLocatorHrefForLazyPub(json, known)
        val resultJson = JSONObject(result)
        assertEquals("application/xhtml+xml", resultJson.getString("type"))
        assertEquals(0.23, resultJson.getJSONObject("locations").getDouble("progression"), 0.001)
    }

    @Test
    fun `returns original when href already matches known href without prefix`() {
        val json = locatorJson("ch09.html")
        val known = setOf("ch09.html")
        val result = normalizeLocatorHrefForLazyPub(json, known)
        assertEquals(json, result)
    }

    @Test
    fun `returns original when stripped href does not match any known href`() {
        val json = locatorJson("OEBPS/unknown.html")
        val known = setOf("ch09.html", "ch10.html")
        val result = normalizeLocatorHrefForLazyPub(json, known)
        assertEquals(json, result)
    }

    @Test
    fun `returns null input as null`() {
        // null is handled by the caller; this just tests the non-null contract
        val json = locatorJson("OEBPS/ch01.html")
        val result = normalizeLocatorHrefForLazyPub(json, emptySet())
        assertEquals(json, result)
    }

    @Test
    fun `returns original when knownHrefs is empty`() {
        val json = locatorJson("OEBPS/ch01.html")
        val result = normalizeLocatorHrefForLazyPub(json, emptySet())
        assertEquals(json, result)
    }

    @Test
    fun `does not strip when href has no OEBPS prefix even if bare path is in knownHrefs`() {
        val json = locatorJson("xhtml/ch09.html")
        val known = setOf("xhtml/ch09.html")
        val result = normalizeLocatorHrefForLazyPub(json, known)
        assertEquals(json, result)
    }

    @Test
    fun `returns original when locatorJson is malformed`() {
        val result = normalizeLocatorHrefForLazyPub("not valid json {{{", setOf("ch09.html"))
        assertEquals("not valid json {{{", result)
    }
}
