package com.riffle.core.domain

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.TextNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * iOS-port coverage for [IosEpubCfiTranslator]'s ksoup-based DOM-walking functions, mirroring
 * core/domain/src/jvmTest/kotlin/com/riffle/core/domain/EpubCfiTranslatorTest.kt (jsoup-based).
 * These are separate implementations (different DOM libraries — see IosEpubCfiTranslator.kt's
 * doc comment) so the algorithm parity has to be pinned on this side independently; the JVM test
 * only proves the jsoup path.
 */
class IosEpubCfiTranslatorTest {

    // ── iosExtractCfiDocPath ──────────────────────────────────────────────────

    @Test
    fun `iosExtractCfiDocPath returns doc portion for well-formed CFI`() {
        assertEquals("/4/2/1:42", iosExtractCfiDocPath("epubcfi(/6/4!/4/2/1:42)"))
    }

    @Test
    fun `iosExtractCfiDocPath returns null for missing bang separator`() {
        assertNull(iosExtractCfiDocPath("epubcfi(/6/4/2/1:42)"))
    }

    @Test
    fun `iosExtractCfiDocPath returns null for missing epubcfi prefix`() {
        assertNull(iosExtractCfiDocPath("/6/4!/4/2/1:42"))
    }

    // ── iosParseCfiDocPath ────────────────────────────────────────────────────

    @Test
    fun `iosParseCfiDocPath parses simple path without ID assertions`() {
        val result = iosParseCfiDocPath("/4/2/1:342")
        assertNotNull(result)
        assertEquals(listOf(4, 2, 1), result.steps)
        assertEquals(342, result.charOffset)
    }

    @Test
    fun `iosParseCfiDocPath strips ID assertions from all steps`() {
        val result = iosParseCfiDocPath("/4[body]/10[p5]/2[span]/1:0")
        assertNotNull(result)
        assertEquals(listOf(4, 10, 2, 1), result.steps)
        assertEquals(0, result.charOffset)
    }

    @Test
    fun `iosParseCfiDocPath handles path with no character offset step`() {
        val result = iosParseCfiDocPath("/4/2")
        assertNotNull(result)
        assertEquals(listOf(4, 2), result.steps)
        assertEquals(0, result.charOffset)
    }

    @Test
    fun `iosParseCfiDocPath returns null for empty path`() {
        assertNull(iosParseCfiDocPath(""))
        assertNull(iosParseCfiDocPath("/"))
    }

    @Test
    fun `iosParseCfiDocPath returns null for non-numeric step`() {
        assertNull(iosParseCfiDocPath("/4/abc/1:0"))
    }

    // ── iosWalkCfiSteps ───────────────────────────────────────────────────────

    private val simpleHtml = "<html><body><p>Hello world</p><p>Second paragraph</p></body></html>"

    @Test
    fun `iosWalkCfiSteps navigates to first paragraph`() {
        val doc = Ksoup.parse(simpleHtml)
        val htmlEl = doc.child(0)
        val node = iosWalkCfiSteps(htmlEl, listOf(4, 2))
        assertEquals("p", (node as? Element)?.tagName())
        assertEquals("Hello world", (node as Element).text())
    }

    @Test
    fun `iosWalkCfiSteps odd step navigates to text node`() {
        val doc = Ksoup.parse(simpleHtml)
        val htmlEl = doc.child(0)
        val node = iosWalkCfiSteps(htmlEl, listOf(4, 2, 1))
        assertTrue(node is TextNode)
        assertEquals("Hello world", (node as TextNode).text())
    }

    @Test
    fun `iosWalkCfiSteps returns null for step beyond available children`() {
        val doc = Ksoup.parse(simpleHtml)
        val htmlEl = doc.child(0)
        assertNull(iosWalkCfiSteps(htmlEl, listOf(4, 6)))
    }

    @Test
    fun `iosWalkCfiSteps returns null when even step applied to non-element`() {
        val doc = Ksoup.parse(simpleHtml)
        val htmlEl = doc.child(0)
        assertNull(iosWalkCfiSteps(htmlEl, listOf(4, 2, 1, 2)))
    }

    // ── iosCountBodyChars / iosCountCharsBefore / iosFindNodeAtChar ──────────

    @Test
    fun `iosCountBodyChars counts only non-blank text nodes`() {
        val doc = Ksoup.parse(simpleHtml)
        assertEquals(27L, iosCountBodyChars(doc.body()))
    }

    @Test
    fun `iosCountBodyChars counts nested text`() {
        val html = "<html><body><p>Hello <em>world</em>!</p></body></html>"
        val doc = Ksoup.parse(html)
        assertEquals(12L, iosCountBodyChars(doc.body()))
    }

    @Test
    fun `iosCountCharsBefore counts preceding text nodes`() {
        val doc = Ksoup.parse(simpleHtml)
        val body = doc.body()
        val target = doc.select("p")[1].textNodes().first()
        assertEquals(14L, iosCountCharsBefore(body, target, 3))
    }

    @Test
    fun `iosCountCharsBefore returns minus one when target not in subtree`() {
        val doc1 = Ksoup.parse("<html><body><p>AAA</p></body></html>")
        val doc2 = Ksoup.parse("<html><body><p>BBB</p></body></html>")
        val foreignNode = doc2.select("p").first()!!.textNodes().first()
        assertEquals(-1L, iosCountCharsBefore(doc1.body(), foreignNode, 0))
    }

    @Test
    fun `iosFindNodeAtChar mid-node returns correct text node and offset`() {
        val doc = Ksoup.parse("<html><body><p>Hello</p><p>World</p></body></html>")
        val (node, offset) = iosFindNodeAtChar(doc.body(), 7L)!!
        assertEquals("World", node.getWholeText())
        assertEquals(2, offset)
    }

    @Test
    fun `iosFindNodeAtChar returns null beyond total length`() {
        val doc = Ksoup.parse("<html><body><p>Hi</p></body></html>")
        assertNull(iosFindNodeAtChar(doc.body(), 100L))
    }

    // ── iosBuildCfiDocPath ────────────────────────────────────────────────────

    @Test
    fun `iosBuildCfiDocPath builds correct path for first paragraph text node`() {
        val doc = Ksoup.parse(simpleHtml)
        val htmlEl = doc.child(0)
        val textNode = doc.select("p").first()!!.textNodes().first()
        assertEquals("/4/2/1:5", iosBuildCfiDocPath(htmlEl, textNode, 5))
    }

    @Test
    fun `iosBuildCfiDocPath includes id assertion for element with id`() {
        val doc = Ksoup.parse("<html><body><p id=\"para1\">Hello world</p></body></html>")
        val htmlEl = doc.child(0)
        val textNode = doc.select("p").first()!!.textNodes().first()
        assertEquals("/4/2[para1]/1:3", iosBuildCfiDocPath(htmlEl, textNode, 3))
    }

    @Test
    fun `iosBuildCfiDocPath returns null when text node not in htmlEl subtree`() {
        val doc1 = Ksoup.parse("<html><body><p>A</p></body></html>")
        val doc2 = Ksoup.parse("<html><body><p>B</p></body></html>")
        val foreignNode = doc2.select("p").first()!!.textNodes().first()
        assertNull(iosBuildCfiDocPath(doc1.child(0), foreignNode, 0))
    }

    // ── iosCfiDocPathToProgression ────────────────────────────────────────────

    @Test
    fun `iosCfiDocPathToProgression start of first paragraph is near zero`() {
        val prog = iosCfiDocPathToProgression("/4/2/1:0", simpleHtml)
        assertNotNull(prog)
        assertEquals(0.0, prog, 0.001)
    }

    @Test
    fun `iosCfiDocPathToProgression mid first paragraph`() {
        val prog = iosCfiDocPathToProgression("/4/2/1:5", simpleHtml)
        assertNotNull(prog)
        assertEquals(5.0 / 27.0, prog, 0.001)
    }

    @Test
    fun `iosCfiDocPathToProgression returns null for path pointing to element not text`() {
        assertNull(iosCfiDocPathToProgression("/4/2", "<html><body><p>Hello</p></body></html>"))
    }

    @Test
    fun `iosCfiDocPathToProgression returns null for empty body`() {
        assertNull(iosCfiDocPathToProgression("/4/2/1:0", "<html><body></body></html>"))
    }

    @Test
    fun `iosCfiDocPathToProgression works with nested inline elements`() {
        val html = "<html><body><p>Hello <em>world</em>!</p></body></html>"
        val prog = iosCfiDocPathToProgression("/4/2/3:0", html)
        assertNotNull(prog)
        assertEquals(11.0 / 12.0, prog, 0.001)
    }

    // ── ID-anchored navigation ────────────────────────────────────────────────

    @Test
    fun `iosCfiDocPathToProgression uses ID anchor when present`() {
        val html = "<html><body><p>First</p><p id=\"second\">Second</p></body></html>"
        val prog = iosCfiDocPathToProgression("/4/4[second]/1:0", html)
        assertNotNull(prog)
        assertEquals(5.0 / 11.0, prog, 0.001)
    }

    @Test
    fun `iosCfiDocPathToProgression falls back to numeric when ID not in html`() {
        val prog = iosCfiDocPathToProgression("/4/4[nonexistent]/1:0", simpleHtml)
        assertNotNull(prog)
        assertEquals(11.0 / 27.0, prog, 0.001)
    }

    @Test
    fun `iosCfiDocPathToProgression deepest ID wins when multiple IDs present`() {
        val html = "<html><body><p>AAA</p><div id=\"outer\"><p id=\"inner\">Inner text</p></div></body></html>"
        val prog = iosCfiDocPathToProgression("/4[body]/4[outer]/2[inner]/1:2", html)
        assertNotNull(prog)
        assertEquals(5.0 / 13.0, prog, 0.001)
    }

    // ── iosProgressionToCfiDocPath ────────────────────────────────────────────

    @Test
    fun `iosProgressionToCfiDocPath zero returns path at start of body`() {
        val path = iosProgressionToCfiDocPath(0.0, simpleHtml)
        assertNotNull(path)
        assertTrue(path.endsWith(":0"))
    }

    @Test
    fun `iosProgressionToCfiDocPath returns null for empty body`() {
        assertNull(iosProgressionToCfiDocPath(0.5, "<html><body></body></html>"))
    }

    @Test
    fun `iosProgressionToCfiDocPath emits id assertion when element has id`() {
        val html = "<html><body><p>First</p><h2 id=\"section\">Section</h2></body></html>"
        val path = iosProgressionToCfiDocPath(5.0 / 12.0, html)
        assertNotNull(path)
        assertTrue(path.contains("[section]"), "Expected [section] in $path")
    }

    // ── Round-trip ────────────────────────────────────────────────────────────

    @Test
    fun `round-trip cfi to progression to cfi is self-consistent`() {
        val originalPath = "/4/2/1:5"
        val progression = iosCfiDocPathToProgression(originalPath, simpleHtml)!!
        val rebuiltPath = iosProgressionToCfiDocPath(progression, simpleHtml)!!
        val rebuiltProgression = iosCfiDocPathToProgression(rebuiltPath, simpleHtml)!!
        assertEquals(progression, rebuiltProgression, 1.0 / 27.0)
    }

    @Test
    fun `round-trip progression to cfi to progression is self-consistent`() {
        val samples = listOf(0.0, 0.1, 0.25, 0.5, 0.75, 0.99)
        for (original in samples) {
            val path = iosProgressionToCfiDocPath(original, simpleHtml) ?: continue
            val recovered = iosCfiDocPathToProgression(path, simpleHtml)!!
            assertEquals(
                original,
                recovered,
                1.0 / 27.0,
                "Round-trip failed for progression=$original",
            )
        }
    }

    @Test
    fun `round-trip with id assertions is self-consistent`() {
        val html = "<html><body><p>First</p><p id=\"second\">Second paragraph</p></body></html>"
        val originalProg = 0.4
        val path = iosProgressionToCfiDocPath(originalProg, html)!!
        val recovered = iosCfiDocPathToProgression(path, html)!!
        assertEquals(originalProg, recovered, 1.0 / 21.0, "Round-trip failed for progression=$originalProg")
    }
}
