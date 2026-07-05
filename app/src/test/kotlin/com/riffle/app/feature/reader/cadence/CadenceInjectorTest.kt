package com.riffle.app.feature.reader.cadence

import com.riffle.core.domain.SentenceQuote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CadenceInjectorTest {

    @Test
    fun `null and blank inputs are Unsupported`() {
        assertEquals(CadenceInjector.Result.Unsupported, CadenceInjector.parse(null))
        assertEquals(CadenceInjector.Result.Unsupported, CadenceInjector.parse(""))
        assertEquals(CadenceInjector.Result.Unsupported, CadenceInjector.parse("  "))
        assertEquals(CadenceInjector.Result.Unsupported, CadenceInjector.parse("null"))
    }

    @Test
    fun `supported false yields Unsupported`() {
        val raw = "{\"supported\":false,\"quotes\":{},\"chapterHrefs\":{}}"
        assertEquals(CadenceInjector.Result.Unsupported, CadenceInjector.parse(raw))
    }

    @Test
    fun `well-formed JSON with supported true parses quotes and chapterHrefs`() {
        val raw = """{"supported":true,"quotes":{"c.xhtml#cd-0":{"before":"","highlight":"Hello.","after":""},"c.xhtml#cd-1":{"before":"","highlight":"World.","after":""}},"chapterHrefs":{"c.xhtml#cd-0":"c.xhtml","c.xhtml#cd-1":"c.xhtml"}}"""
        val out = CadenceInjector.parse(raw) as CadenceInjector.Result.Ready
        assertEquals(
            mapOf(
                "c.xhtml#cd-0" to SentenceQuote(before = "", highlight = "Hello.", after = ""),
                "c.xhtml#cd-1" to SentenceQuote(before = "", highlight = "World.", after = ""),
            ),
            out.quotes,
        )
        assertEquals(
            mapOf("c.xhtml#cd-0" to "c.xhtml", "c.xhtml#cd-1" to "c.xhtml"),
            out.chapterHrefs,
        )
    }

    @Test
    fun `WebView-wrapped JSON string literal is unwrapped and parsed`() {
        // Android's WebView.evaluateJavascript wraps string returns in extra quotes and escapes
        // inner quotes with backslashes. The parser must undo that wrapping before parsing JSON.
        val raw = "\"{\\\"supported\\\":true,\\\"quotes\\\":{\\\"c.xhtml#cd-0\\\":{\\\"before\\\":\\\"\\\",\\\"highlight\\\":\\\"Hi.\\\",\\\"after\\\":\\\"\\\"}},\\\"chapterHrefs\\\":{\\\"c.xhtml#cd-0\\\":\\\"c.xhtml\\\"}}\""
        val out = CadenceInjector.parse(raw)
        assertTrue("wrapped literal must parse, was $out", out is CadenceInjector.Result.Ready)
        val r = out as CadenceInjector.Result.Ready
        assertEquals("Hi.", r.quotes["c.xhtml#cd-0"]?.highlight)
    }

    @Test
    fun `malformed JSON short-circuits to Unsupported`() {
        assertEquals(CadenceInjector.Result.Unsupported, CadenceInjector.parse("{"))
        assertEquals(CadenceInjector.Result.Unsupported, CadenceInjector.parse("garbled}"))
    }

    @Test
    fun `missing quotes or chapterHrefs key yields Unsupported`() {
        // Regression: an incomplete JSON without the two required keys must NOT partial-load, or
        // Cadence would tick against a half-map and hang on missing lookups.
        assertEquals(CadenceInjector.Result.Unsupported, CadenceInjector.parse("{\"supported\":true}"))
        assertEquals(CadenceInjector.Result.Unsupported, CadenceInjector.parse("{\"supported\":true,\"quotes\":{}}"))
    }
}
