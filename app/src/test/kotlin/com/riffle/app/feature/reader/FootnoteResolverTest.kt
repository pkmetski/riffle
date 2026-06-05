package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FootnoteResolverTest {

    // Convenience: assert a footnote resolves to plain text with no links.
    private fun assertPlain(expected: String, content: FootnoteContent?) {
        assertNotNull(content)
        assertEquals(expected, content!!.text)
        assertTrue("expected no links, got ${content.links}", content.links.isEmpty())
    }

    @Test
    fun `target with epub-type footnote is detected`() {
        val html = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <aside epub:type="footnote" id="fn1"><p>Note one.</p></aside>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        assertPlain("Note one.", FootnoteResolver.extractFootnoteContent(doc, "fn1"))
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
        assertPlain("Body text.", FootnoteResolver.extractFootnoteContent(doc, "ftn.ch01fn02"))
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
        assertPlain("Third note.", FootnoteResolver.extractFootnoteContent(doc, "ftn.ch01fn03"))
    }

    @Test
    fun `target with role doc-footnote is detected`() {
        val html = """
            <html><body>
              <div role="doc-footnote" id="dfn1"><p>Aria note.</p></div>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        assertPlain("Aria note.", FootnoteResolver.extractFootnoteContent(doc, "dfn1"))
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
        assertNull(FootnoteResolver.extractFootnoteContent(doc, "sec1"))
    }

    @Test
    fun `missing target id returns null`() {
        val doc = FootnoteResolver.parse("<html><body><p id=\"x\">Hi</p></body></html>")
        assertNull(FootnoteResolver.extractFootnoteContent(doc, "nope"))
    }

    @Test
    fun `empty footnote body returns null`() {
        val html = """<html><body><div class="footnote" id="fn1"></div></body></html>"""
        val doc = FootnoteResolver.parse(html)
        assertNull(FootnoteResolver.extractFootnoteContent(doc, "fn1"))
    }

    // Regression for Readium 3.0.0's `select("#$id")` bug. Many EPUBs (O'Reilly
    // toolchain in particular) emit `id="ftn.ch01fn01"`, where Jsoup's CSS
    // selector parses the dot as a class separator and matches nothing.
    // extractFootnoteContent must use getElementById, not select.
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
        assertPlain("Dotted body.", FootnoteResolver.extractFootnoteContent(doc, "ftn.ch01fn01"))
    }

    @Test
    fun `resolveAnchorTap returns content on cache hit and footnote target`() {
        val html = """
            <html><body>
              <div class="footnote" id="fn1"><p>Body.</p></div>
            </body></html>
        """.trimIndent()
        val cache = mapOf("OEBPS/ch01.html" to FootnoteResolver.parse(html))
        assertEquals(
            "Body.",
            FootnoteResolver.resolveAnchorTap("OEBPS/ch01.html", cache, "fn1")?.text,
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
            FootnoteResolver.AnchorTarget.Footnote(FootnoteContent("Note one.")),
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

    // An element that looks like a footnote but has an empty body resolves to CrossReference, not
    // Footnote: there's no popup text to show, so we treat it as an in-document target and snap to
    // its column (rather than the old behaviour of falling through to the WebView's default scroll).
    @Test
    fun `classifyAnchorTap reports an empty-body footnote as CrossReference`() {
        val html = """<html><body><div class="footnote" id="fn1"></div></body></html>"""
        val cache = mapOf("OEBPS/ch08.html" to FootnoteResolver.parse(html))
        assertEquals(
            FootnoteResolver.AnchorTarget.CrossReference,
            FootnoteResolver.classifyAnchorTap("OEBPS/ch08.html", cache, "fn1"),
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
    // just "1.", so we climb to the enclosing note entry and return its body.
    // The marker number is preserved (as plain text), only the dead link drops.
    @Test
    fun `back-reference marker target resolves to the note body with the number preserved`() {
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
        assertPlain(
            "1. Kanter, Change Masters.",
            FootnoteResolver.extractFootnoteContent(doc, "c07-note-0002"),
        )
    }

    // Even when the id points straight at the note entry, the leading marker
    // ("1.") is preserved as plain text — its number is useful context, only
    // the back-link target is dropped.
    @Test
    fun `note entry target preserves the leading marker number as plain text`() {
        val html = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <aside id="en2" class="noteEntry" epub:type="rearnote">
                <a id="c07-note-0002" href="#backTNT2">1.</a> Kanter, <i>Change Masters</i>.
              </aside>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        assertPlain(
            "1. Kanter, Change Masters.",
            FootnoteResolver.extractFootnoteContent(doc, "en2"),
        )
    }

    // The screenshot bug: brackets are plain text, the number is a back-reference
    // anchor. Stripping the anchor left an orphaned "[]". The number must survive.
    @Test
    fun `numeric marker wrapped by text brackets is preserved`() {
        val html = """
            <html><body>
              <div class="footnote" id="fn10">[<a href="#ref10">10</a>] KISSmetrics CEO talked.</div>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        assertPlain(
            "[10] KISSmetrics CEO talked.",
            FootnoteResolver.extractFootnoteContent(doc, "fn10"),
        )
    }

    // A footnote whose body legitimately starts with a number must keep it;
    // only back-reference *anchors* are special-cased, not arbitrary digits.
    @Test
    fun `numeric body content is preserved when not a back-reference`() {
        val html = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <aside id="en3" epub:type="rearnote"><p>1984 was a pivotal year.</p></aside>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        assertPlain(
            "1984 was a pivotal year.",
            FootnoteResolver.extractFootnoteContent(doc, "en3"),
        )
    }

    // Many EPUBs append a "return to text" back-link at the END of the note —
    // an up-arrow (↑) or hooked arrow. The leading marker number is preserved
    // as plain text; the trailing arrow is pure chrome and is removed.
    @Test
    fun `leading marker kept, trailing up-arrow back-link stripped`() {
        val html = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <div epub:type="footnote" id="note_3-1"><p>
                <a href="#ref_3-1" title="Back to text">[1]</a>
                Ostermalm is a district. Note. <a href="#ref_3-1" title="Back to text">↑</a>
              </p></div>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        assertPlain(
            "[1] Ostermalm is a district. Note.",
            FootnoteResolver.extractFootnoteContent(doc, "note_3-1"),
        )
    }

    // A non-numeric backlink (epub:type="backlink", text "Return") carries no
    // information and is removed entirely.
    @Test
    fun `non-numeric epub-type backlink is stripped`() {
        val html = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <aside epub:type="footnote" id="fn9"><p>
                Note body. <a epub:type="backlink" href="#ref9">Return</a>
              </p></aside>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        assertPlain("Note body.", FootnoteResolver.extractFootnoteContent(doc, "fn9"))
    }

    // Multi-block notes must keep a separating space between blocks — the old
    // Jsoup .text() inserted one, so dropping it would jam paragraphs together.
    @Test
    fun `adjacent block elements are separated by a space`() {
        val html = """
            <html><body>
              <div class="footnote" id="fn1"><p>First paragraph.</p><p>Second paragraph.</p></div>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        assertPlain(
            "First paragraph. Second paragraph.",
            FootnoteResolver.extractFootnoteContent(doc, "fn1"),
        )
    }

    @Test
    fun `line break is treated as a separating space`() {
        val html = """
            <html><body>
              <div class="footnote" id="fn1"><p>Line one<br/>Line two</p></div>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        assertPlain(
            "Line one Line two",
            FootnoteResolver.extractFootnoteContent(doc, "fn1"),
        )
    }

    // An inline element (e.g. <i>) must NOT introduce spurious spaces — the only
    // spacing comes from the surrounding text nodes.
    @Test
    fun `inline elements do not introduce spurious spaces`() {
        val html = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <aside id="en2" epub:type="rearnote">
                <a id="m" href="#b">1.</a> Kanter, <i>Change Masters</i>.
              </aside>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        assertPlain("1. Kanter, Change Masters.", FootnoteResolver.extractFootnoteContent(doc, "en2"))
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
        assertPlain("Container plural.", FootnoteResolver.extractFootnoteContent(doc, "fnX"))
    }

    // ── Clickable links ───────────────────────────────────────────────────────

    // A real <a href="http…"> anchor whose visible text differs from the URL
    // must survive as a link span covering its visible text, carrying the href.
    @Test
    fun `external anchor link is preserved with its href and visible-text offsets`() {
        val html = """
            <html><body>
              <div class="footnote" id="fn1"><p>See <a href="https://example.com/study">the study</a> for details.</p></div>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        val content = FootnoteResolver.extractFootnoteContent(doc, "fn1")
        assertNotNull(content)
        assertEquals("See the study for details.", content!!.text)
        assertEquals(1, content.links.size)
        val link = content.links.single()
        assertEquals("https://example.com/study", link.url)
        assertEquals("the study", content.text.substring(link.start, link.end))
    }

    // A bare-text URL (no anchor) — the screenshot's slideshare case — is
    // linkified in place.
    @Test
    fun `bare text url is linkified`() {
        val html = """
            <html><body>
              <div class="footnote" id="fn1"><p>Source: http://www.slideshare.net/hnshah/x</p></div>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        val content = FootnoteResolver.extractFootnoteContent(doc, "fn1")
        assertNotNull(content)
        assertEquals("Source: http://www.slideshare.net/hnshah/x", content!!.text)
        val link = content.links.single()
        assertEquals("http://www.slideshare.net/hnshah/x", link.url)
        assertEquals("http://www.slideshare.net/hnshah/x", content.text.substring(link.start, link.end))
    }

    // Trailing sentence punctuation must not be swallowed into the linkified URL.
    @Test
    fun `bare url trailing punctuation is trimmed from the link`() {
        val html = """
            <html><body>
              <div class="footnote" id="fn1"><p>See http://example.com/a.</p></div>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        val content = FootnoteResolver.extractFootnoteContent(doc, "fn1")
        assertNotNull(content)
        assertEquals("See http://example.com/a.", content!!.text)
        val link = content.links.single()
        assertEquals("http://example.com/a", link.url)
        assertEquals("http://example.com/a", content.text.substring(link.start, link.end))
    }

    // A URL that is already an anchor must not be double-linkified by the
    // bare-URL pass.
    @Test
    fun `url that is already an anchor is not double-linkified`() {
        val html = """
            <html><body>
              <div class="footnote" id="fn1"><p><a href="http://example.com">http://example.com</a></p></div>
            </body></html>
        """.trimIndent()
        val doc = FootnoteResolver.parse(html)
        val content = FootnoteResolver.extractFootnoteContent(doc, "fn1")
        assertNotNull(content)
        assertEquals("http://example.com", content!!.text)
        assertEquals(1, content.links.size)
        assertEquals("http://example.com", content.links.single().url)
    }

    // footnoteContent(rawHtml) backs the Readium shouldFollowInternalLink path;
    // it must apply the same marker/link processing as the getElementById path.
    @Test
    fun `footnoteContent parses raw note html with marker preserved and url linkified`() {
        val noteHtml = "<aside>[<a href=\"#r\">2</a>] See http://example.com/x</aside>"
        val content = FootnoteResolver.footnoteContent(noteHtml)
        assertNotNull(content)
        assertEquals("[2] See http://example.com/x", content!!.text)
        assertEquals("http://example.com/x", content.links.single().url)
    }

    @Test
    fun `footnoteContent returns null for empty note html`() {
        assertNull(FootnoteResolver.footnoteContent("<aside></aside>"))
    }
}
