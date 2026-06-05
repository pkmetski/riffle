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

    // Regression for the reader-navigation bug: tapping an in-document cross-
    // reference like "Figure 4.1" used to fall through to the WebView's default
    // same-document anchor scroll, which lands scrollLeft mid-column in a
    // paginated reflowable layout (the page splits between two columns and never
    // re-snaps). classifyAnchorTap must report CrossReference so the reader
    // navigates via Readium's go() instead, landing on a column-page boundary.
    @Test
    fun `classifyAnchorTap reports a non-footnote in-document target as CrossReference`() {
        val html = """
            <html><body>
              <p>See <a href="#fig41">Figure 4.1</a>.</p>
              <figure id="fig41"><img src="fig41.png"/><figcaption>Figure 4.1</figcaption></figure>
            </body></html>
        """.trimIndent()
        val cache = mapOf("OEBPS/ch08.html" to FootnoteResolver.parse(html))
        assertEquals(
            FootnoteResolver.AnchorTarget.CrossReference,
            FootnoteResolver.classifyAnchorTap("OEBPS/ch08.html", cache, "fig41"),
        )
    }

    @Test
    fun `classifyAnchorTap reports a footnote target as Footnote`() {
        val html = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <aside epub:type="footnote" id="fn1"><p>Note one.</p></aside>
            </body></html>
        """.trimIndent()
        val cache = mapOf("OEBPS/ch08.html" to FootnoteResolver.parse(html))
        assertEquals(
            FootnoteResolver.AnchorTarget.Footnote("Note one."),
            FootnoteResolver.classifyAnchorTap("OEBPS/ch08.html", cache, "fn1"),
        )
    }

    // An id that isn't in the cached doc (or a cold cache / no current href)
    // must stay Unresolved so the reader defers to the WebView rather than
    // navigating to a bogus locator.
    @Test
    fun `classifyAnchorTap reports a missing id as Unresolved`() {
        val html = """<html><body><h2 id="sec1">Section</h2></body></html>"""
        val cache = mapOf("OEBPS/ch08.html" to FootnoteResolver.parse(html))
        assertEquals(
            FootnoteResolver.AnchorTarget.Unresolved,
            FootnoteResolver.classifyAnchorTap("OEBPS/ch08.html", cache, "nope"),
        )
    }

    @Test
    fun `classifyAnchorTap reports a cold cache as Unresolved`() {
        assertEquals(
            FootnoteResolver.AnchorTarget.Unresolved,
            FootnoteResolver.classifyAnchorTap("OEBPS/ch08.html", emptyMap(), "fig41"),
        )
        assertEquals(
            FootnoteResolver.AnchorTarget.Unresolved,
            FootnoteResolver.classifyAnchorTap(null, emptyMap(), "fig41"),
        )
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

    // The real-world bug from "Influence Without Authority": the in-text
    // reference links to the inner back-reference anchor, not the <aside>:
    //   in body:  <a id="backTNT2" href="#c07-note-0002">1</a>
    //   target:   <aside id="en2" epub:type="rearnote">
    //               <a id="c07-note-0002" href="#backTNT2">1.</a> Kanter…
    //             </aside>
    // getElementById("c07-note-0002") lands on the marker anchor whose text is
    // just "1.", so the popup used to show only the number. The resolver must
    // climb to the enclosing note entry and return the body, marker stripped.
    @Test
    fun `back-reference marker target resolves to the note body`() {
        val html = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <section epub:type="rearnotes">
                <aside id="en2" class="noteEntry" epub:type="rearnote">
                  <a id="c07-note-0002" href="#backTNT2">1.</a> Kanter, <i>Change Masters</i>.
                </aside>
              </section>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        assertEquals(
            "Kanter, Change Masters.",
            FootnoteResolver.extractFootnoteText(doc, "c07-note-0002"),
        )
    }

    // Even when the id points straight at the note entry, the leading
    // back-reference marker ("1.") is chrome, not content — strip it.
    @Test
    fun `note entry target strips the leading back-reference marker`() {
        val html = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <aside id="en2" class="noteEntry" epub:type="rearnote">
                <a id="c07-note-0002" href="#backTNT2">1.</a> Kanter, <i>Change Masters</i>.
              </aside>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        assertEquals(
            "Kanter, Change Masters.",
            FootnoteResolver.extractFootnoteText(doc, "en2"),
        )
    }

    // A footnote whose body legitimately starts with a number must keep it;
    // only the back-reference *anchor* is stripped, not arbitrary digits.
    @Test
    fun `numeric body content is preserved when not a back-reference`() {
        val html = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <aside id="en3" epub:type="rearnote"><p>1984 was a pivotal year.</p></aside>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        assertEquals(
            "1984 was a pivotal year.",
            FootnoteResolver.extractFootnoteText(doc, "en3"),
        )
    }

    // Many EPUBs append a "return to text" back-link at the END of the note —
    // an up-arrow (↑) or hooked arrow that links back to the reference. Real
    // sample from a Swedish-to-Bulgarian translation:
    //   <div epub:type="footnote" id="note_3-1"><p>
    //     <a href="#ref_3-1">[1]</a> Йостермалм … Б. пр. <a href="#ref_3-1">↑</a>
    //   </p></div>
    // Both the leading marker and the trailing arrow are chrome; strip both.
    @Test
    fun `trailing up-arrow back-link is stripped`() {
        val html = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <div epub:type="footnote" id="note_3-1"><p>
                <a href="#ref_3-1" title="Back to text">[1]</a>
                Ostermalm is a district. Note. <a href="#ref_3-1" title="Back to text">↑</a>
              </p></div>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        assertEquals(
            "Ostermalm is a district. Note.",
            FootnoteResolver.extractFootnoteText(doc, "note_3-1"),
        )
    }

    @Test
    fun `standard epub-type backlink is stripped`() {
        val html = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <aside epub:type="footnote" id="fn9"><p>
                Note body. <a epub:type="backlink" href="#ref9">Return</a>
              </p></aside>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        assertEquals("Note body.", FootnoteResolver.extractFootnoteText(doc, "fn9"))
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
