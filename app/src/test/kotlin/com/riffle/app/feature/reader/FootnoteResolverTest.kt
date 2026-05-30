package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FootnoteResolverTest {

    @Test
    fun `target with epub-type footnote is detected`() {
        val html = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <aside epub:type="footnote" id="fn1"><p>Note one.</p></aside>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        assertEquals("Note one.", FootnoteResolver.extractFootnoteText(doc, "fn1"))
    }

    // The Lean Customer Development EPUB ships ch01fn02..ch01fn08 without
    // epub:type on the target div, relying solely on class="footnote" plus
    // the surrounding <section epub:type="footnotes">. Both signals must work.
    @Test
    fun `target with only class footnote is detected`() {
        val html = """
            <html><body>
              <section><div class="footnote" id="ftn.ch01fn02"><p>Body text.</p></div></section>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        assertEquals("Body text.", FootnoteResolver.extractFootnoteText(doc, "ftn.ch01fn02"))
    }

    @Test
    fun `target inside section with epub-type footnotes is detected`() {
        val html = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <section epub:type="footnotes">
                <div id="ftn.ch01fn03"><p>Third note.</p></div>
              </section>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        assertEquals("Third note.", FootnoteResolver.extractFootnoteText(doc, "ftn.ch01fn03"))
    }

    @Test
    fun `target with role doc-footnote is detected`() {
        val html = """
            <html><body>
              <div role="doc-footnote" id="dfn1"><p>Aria note.</p></div>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        assertEquals("Aria note.", FootnoteResolver.extractFootnoteText(doc, "dfn1"))
    }

    @Test
    fun `regular cross-reference target returns null`() {
        val html = """
            <html><body>
              <h2 id="sec1">Section One</h2>
              <p>Some paragraph.</p>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        assertNull(FootnoteResolver.extractFootnoteText(doc, "sec1"))
    }

    @Test
    fun `missing target id returns null`() {
        val doc = FootnoteResolver.parse("<html><body><p id=\"x\">Hi</p></body></html>")
        assertNull(FootnoteResolver.extractFootnoteText(doc, "nope"))
    }

    @Test
    fun `empty footnote body returns null`() {
        val html = """<html><body><div class="footnote" id="fn1"></div></body></html>"""
        val doc = FootnoteResolver.parse(html)
        assertNull(FootnoteResolver.extractFootnoteText(doc, "fn1"))
    }

    // Regression for Readium 3.0.0's `select("#$id")` bug. Many EPUBs (O'Reilly
    // toolchain in particular) emit `id="ftn.ch01fn01"`, where Jsoup's CSS
    // selector parses the dot as a class separator and matches nothing.
    // extractFootnoteText must use getElementById, not select.
    @Test
    fun `dotted target id is still found`() {
        val html = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <section epub:type="footnotes">
                <div id="ftn.ch01fn01"><p>Dotted body.</p></div>
              </section>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        assertEquals("Dotted body.", FootnoteResolver.extractFootnoteText(doc, "ftn.ch01fn01"))
    }

    @Test
    fun `resolveAnchorTap returns text on cache hit and footnote target`() {
        val html = """
            <html><body>
              <div class="footnote" id="fn1"><p>Body.</p></div>
            </body></html>
        """.trimIndent()
        val cache = mapOf("OEBPS/ch01.html" to FootnoteResolver.parse(html))
        assertEquals(
            "Body.",
            FootnoteResolver.resolveAnchorTap("OEBPS/ch01.html", cache, "fn1"),
        )
    }

    @Test
    fun `resolveAnchorTap strips fragment from current href before lookup`() {
        val html = """<html><body><div class="footnote" id="fn1">x</div></body></html>"""
        val cache = mapOf("OEBPS/ch01.html" to FootnoteResolver.parse(html))
        // currentHref may itself carry a fragment from the last locator emission.
        assertNotNull(
            FootnoteResolver.resolveAnchorTap("OEBPS/ch01.html#somewhere", cache, "fn1"),
        )
    }

    @Test
    fun `resolveAnchorTap returns null on cache miss`() {
        assertNull(
            FootnoteResolver.resolveAnchorTap("OEBPS/ch01.html", emptyMap(), "fn1"),
        )
    }

    @Test
    fun `resolveAnchorTap returns null when no current href`() {
        val cache = mapOf("OEBPS/ch01.html" to FootnoteResolver.parse("<html/>"))
        assertNull(FootnoteResolver.resolveAnchorTap(null, cache, "fn1"))
    }

    @Test
    fun `resolveAnchorTap returns null for non-footnote target`() {
        val html = """<html><body><h2 id="sec1">Section</h2></body></html>"""
        val cache = mapOf("OEBPS/ch01.html" to FootnoteResolver.parse(html))
        assertNull(FootnoteResolver.resolveAnchorTap("OEBPS/ch01.html", cache, "sec1"))
    }

    // Regression guard for the two install-script hazards burnt in this session:
    //   1. anchor matching must be case-insensitive (XHTML keeps tagName lowercase)
    //   2. listener must be capture-phase so it runs before Readium's bubble-phase ut()
    @Test
    fun `install script normalises tagName case`() {
        assertTrue(
            "INSTALL_SCRIPT must compare tagName case-insensitively (XHTML uses lowercase)",
            FootnoteAnchorBridge.INSTALL_SCRIPT.contains("toLowerCase"),
        )
        assertFalse(
            "INSTALL_SCRIPT must not compare to literal uppercase 'A'",
            FootnoteAnchorBridge.INSTALL_SCRIPT.contains("=== 'A'") ||
                FootnoteAnchorBridge.INSTALL_SCRIPT.contains("== 'A'"),
        )
    }

    @Test
    fun `install script registers click listener in capture phase`() {
        // addEventListener('click', handler, true) — the trailing true is what makes
        // our handler fire before Readium's bubble-phase ut() click handler.
        val pattern = Regex("""addEventListener\(\s*['"]click['"]\s*,[^,]+,\s*true\s*\)""")
        assertTrue(
            "INSTALL_SCRIPT must register a capture-phase click listener",
            pattern.containsMatchIn(FootnoteAnchorBridge.INSTALL_SCRIPT),
        )
    }

    @Test
    fun `class footnotes plural on container is detected`() {
        val html = """
            <html><body>
              <div class="footnotes">
                <div id="fnX"><p>Container plural.</p></div>
              </div>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        assertEquals("Container plural.", FootnoteResolver.extractFootnoteText(doc, "fnX"))
    }
}
