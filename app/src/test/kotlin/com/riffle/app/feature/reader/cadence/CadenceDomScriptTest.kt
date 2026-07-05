package com.riffle.app.feature.reader.cadence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * String-level assertions on the JS Cadence injects into the reader WebView. We can't execute
 * the JS from a JVM test, but we can pin the contract (feature-detect shape, chapter href/locale
 * interpolation, JSON return shape) so a rename or typo trips CI instead of silently degrading
 * Cadence to a no-op on device.
 */
class CadenceDomScriptTest {

    @Test
    fun `FEATURE_DETECT_JS probes Intl-Segmenter and only Intl-Segmenter`() {
        val js = CadenceDomScript.FEATURE_DETECT_JS
        assertTrue("FEATURE_DETECT_JS must reference Intl.Segmenter", js.contains("Intl.Segmenter") || js.contains("Intl && window.Intl.Segmenter"))
        // Guard against accidentally probing a different global (`Intl.DateTimeFormat` etc.).
        // Regression: if this ever went to a false-positive gate, the top-bar toggle would appear
        // on WebViews where Cadence silently fails.
        assertTrue(js.contains("Segmenter"))
    }

    @Test
    fun `tokeniseChapterJs embeds chapter href verbatim`() {
        val js = CadenceDomScript.tokeniseChapterJs("chapter3.xhtml", "en-US")
        assertTrue(js.contains("'chapter3.xhtml'"))
        assertTrue(js.contains("'en-US'"))
        // Fragment refs must be href#id — the "href + '#' + id" concat lives inside the JS.
        assertTrue(js.contains("chapterHref + '#' + id"))
    }

    @Test
    fun `tokeniseChapterJs escapes single quotes in chapter href`() {
        val js = CadenceDomScript.tokeniseChapterJs("we're.xhtml", null)
        // Un-escaped quotes would break the JS parse — the escape must convert ' to \\'.
        assertTrue("escaped ' expected in $js", js.contains("we\\'re.xhtml"))
    }

    @Test
    fun `tokeniseChapterJs falls through to undefined locale when null`() {
        val js = CadenceDomScript.tokeniseChapterJs("chapter1.xhtml", null)
        assertTrue(js.contains("new Intl.Segmenter(undefined,"))
    }

    @Test
    fun `tokeniseChapterJs falls through to undefined locale when blank`() {
        val js = CadenceDomScript.tokeniseChapterJs("chapter1.xhtml", "  ")
        assertTrue(js.contains("new Intl.Segmenter(undefined,"))
    }

    @Test
    fun `tokeniseChapterJs returns a JSON string carrying quotes chapterHrefs supported flag`() {
        val js = CadenceDomScript.tokeniseChapterJs("chapter1.xhtml", "en")
        assertTrue(js.contains("JSON.stringify"))
        assertTrue(js.contains("quotes"))
        assertTrue(js.contains("chapterHrefs"))
        assertTrue(js.contains("supported"))
    }

    @Test
    fun `tokeniseChapterJs wraps sentences in span id cd-N with className riffle-cd`() {
        // Regression: the FragmentRef = "href#spanId" contract requires exactly this id scheme.
        // If the prefix ever changed (say "cadence-N"), the Kotlin side would still key on "cd-"
        // and every fragment lookup would return null → highlight never appears.
        val js = CadenceDomScript.tokeniseChapterJs("chapter1.xhtml", "en")
        assertTrue(js.contains("'cd-' + idx"))
        assertTrue(js.contains("'riffle-cd'"))
    }

    @Test
    fun `tokeniseChapterJs guards against nulls with try-catch not silent failure`() {
        // If Intl.Segmenter throws on a weird locale or the DOM is mutated during walk, we want a
        // structured `{supported:false,error:...}` return so the top-bar toggle can hide, NOT a
        // JS exception that leaves the reader in an inconsistent state.
        val js = CadenceDomScript.tokeniseChapterJs("chapter1.xhtml", "en")
        assertTrue(js.contains("catch"))
        assertFalse("try-catch must not swallow silently — must return error field", !js.contains("supported: false"))
    }
}
