package com.riffle.app.feature.reader.cadence

import org.junit.Assert.assertEquals
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
    fun `cadenceStartSpanIdJs returns JSON payload with id and rule diagnostic`() {
        // Regression: the bridge parses JSON. If this ever regresses to returning the raw id
        // as a string literal, DefaultRendererBridge.cadenceStartSpanId's JSONObject parse will
        // throw and every Start returns null → back to "starts on cd-0 of merged history".
        val js = CadenceDomScript.cadenceStartSpanIdJs()
        assertTrue("must return JSON stringified payload", js.contains("JSON.stringify(dbg)"))
        assertTrue("payload must carry an id key", js.contains("dbg.id"))
        assertTrue("payload must carry a rule tag for logcat diagnostics", js.contains("dbg.rule"))
    }

    @Test
    fun `cadenceStartSpanIdJs queries headings first, then falls back to visible sentence`() {
        // The start position is section-aware: rule (1) picks the first h1..h6 visible in the
        // viewport (document order), rule (2) picks the nearest preceding heading if none is
        // visible, rule (3) falls back to the first .riffle-cd visible in the viewport via the
        // plural `elementsFromPoint` sweep. If any of these steps get removed, the "Cadence
        // starts on the wrong sentence" class of bugs comes back.
        val js = CadenceDomScript.cadenceStartSpanIdJs()
        // Semantic HTML and ARIA — the two most-universal heading conventions must both be
        // covered. Semantic-only would miss books that render titles via ARIA on a <p>.
        assertTrue("must enumerate semantic HTML headings", js.contains("'h1'") && js.contains("'h6'"))
        assertTrue("must enumerate ARIA heading role", js.contains("[role=\"heading\"]"))
        // EPUB 3 structural semantics — many publisher toolchains omit h1..h6 and rely on
        // `epub:type` alone (see book "Philosophy of Software Design 2nd ed" which has zero
        // h1..h6 in its DOM).
        assertTrue("must recognise epub:type=chapter", js.contains("epub\\\\:type~=\"chapter\""))
        assertTrue("must recognise epub:type=title", js.contains("epub\\\\:type~=\"title\""))
        assertTrue("must use elementsFromPoint (plural) as the rule 3 fallback", js.contains("elementsFromPoint"))
        assertTrue("must identify .riffle-cd ancestors", js.contains("'riffle-cd'"))
    }

    @Test
    fun `cadenceStartSpanIdJs excludes TOC and nav headings the same way the tokeniser does`() {
        // Regression: without the nav guard, an EPUB's TOC list ("3.1", "3.2", "3.3", "3.4
        // Startups and investment") would be treated as visible headings and Cadence would start
        // reading the TOC label instead of the actual section — the same bug commit eaf5e14a
        // fixed for the tokeniser.
        val js = CadenceDomScript.cadenceStartSpanIdJs()
        assertTrue(js.contains("'nav'"))
        assertTrue(js.contains("toc|landmarks|page-list|doc-toc"))
    }

    @Test
    fun `tokeniseChapterJs tags heading-like parents with riffle-heading class`() {
        // Regression: this is the ONLY selector that catches obfuscated-markup books like
        // "Philosophy of Software Design 2nd ed" — no h1..h6, no epub:type, no ARIA, just
        // classes like `class_s6k2`. If the tag emission is removed here or the parent-heuristic
        // (font-size / font-weight / numbered-section text) regresses, the resolver's Rules 1+2
        // won't fire on that class of books and Cadence starts on cd-N-of-random-viewport-top.
        val js = CadenceDomScript.tokeniseChapterJs("chapter1.xhtml", "en")
        assertTrue("must tag heading-like parents", js.contains("'riffle-heading'"))
        assertTrue("must sample font-size against body baseline", js.contains("baselineFontSize"))
        assertTrue("must consider font-weight for bold heading paragraphs", js.contains("fontWeight"))
        assertTrue("must detect numbered-section text pattern", js.contains("SECTION_NUMBER_RE"))
    }

    @Test
    fun `cadenceStartSpanIdJs picks up the riffle-heading marker class`() {
        // Pair with `tokeniseChapterJs tags heading-like parents`. If the resolver drops this
        // selector, the tokeniser's marker is dead weight and obfuscated-markup books regress.
        val js = CadenceDomScript.cadenceStartSpanIdJs()
        assertTrue("must include the .riffle-heading marker in HEADING_SELECTOR", js.contains(".riffle-heading"))
    }

    @Test
    fun `tokeniseChapterJs stamps chapter href onto documentElement so resolver can read it back`() {
        // Regression (recording 20260707_155950): Readium recycles the same WebView across
        // chapter turns, so the Kotlin field `wv.chapterHref` we pass into `tokeniseChapterJs`
        // can lag the DOM by one chapter after a paginated turn. Without this stamp, the
        // resolver builds `readiumLocatorHref + '#' + id` — a ref whose CHAPTER doesn't
        // exist in the ticker's ordered list — and playback falls to cd-0 of the first
        // tokenised chapter (Cover design credits). Stamping the tokenised chapter onto
        // <html data-riffle-chapter=…> lets the resolver report it back in the payload and
        // Kotlin build a chapter-authoritative ref.
        val js = CadenceDomScript.tokeniseChapterJs("chapter1.xhtml", "en")
        assertTrue(
            "must stamp chapterHref onto documentElement",
            js.contains("setAttribute('data-riffle-chapter', chapterHref)"),
        )
    }

    @Test
    fun `cadenceStartSpanIdJs echoes the chapter attribute so Kotlin can build a full ref`() {
        // Pair with the tokeniser stamp above. If the resolver drops the chapter field the
        // Kotlin parser falls back to bare-id output and the "wrong chapter" bug returns.
        val js = CadenceDomScript.cadenceStartSpanIdJs()
        assertTrue(
            "must read the tokeniser-stamped chapter attribute back",
            js.contains("getAttribute('data-riffle-chapter')"),
        )
        assertTrue(
            "must include the chapter field in the dbg payload",
            js.contains("chapter: chapter"),
        )
    }

    @Test
    fun `parseCadenceStartId returns full ref when chapter field is present`() {
        val raw = """{"id":"cd-5","chapter":"OEBPS/ch1.xhtml","rule":1}"""
        assertEquals("OEBPS/ch1.xhtml#cd-5", CadenceDomScript.parseCadenceStartId(raw))
    }

    @Test
    fun `parseCadenceStartId returns bare id when chapter is missing so caller can fall back`() {
        val raw = """{"id":"cd-5","rule":1}"""
        assertEquals("cd-5", CadenceDomScript.parseCadenceStartId(raw))
    }

    @Test
    fun `parseCadenceStartId returns bare id when chapter is empty string`() {
        // The tokeniser writes `""` when the DOM hasn't been tokenised yet — same fallback
        // path as when the field is absent entirely (belt-and-braces).
        val raw = """{"id":"cd-5","chapter":"","rule":1}"""
        assertEquals("cd-5", CadenceDomScript.parseCadenceStartId(raw))
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
