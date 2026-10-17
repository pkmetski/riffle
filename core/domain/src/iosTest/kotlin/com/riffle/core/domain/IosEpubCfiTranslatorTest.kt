package com.riffle.core.domain

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.TextNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** iOS coverage for the shared Ksoup-backed CFI translator functions now in commonMain. */
class IosEpubCfiTranslatorTest {

    // ── ksoupExtractCfiDocPath ──────────────────────────────────────────────────

    @Test
    fun `ksoupExtractCfiDocPath returns doc portion for well-formed CFI`() {
        assertEquals("/4/2/1:42", ksoupExtractCfiDocPath("epubcfi(/6/4!/4/2/1:42)"))
    }

    @Test
    fun `ksoupExtractCfiDocPath returns null for missing bang separator`() {
        assertNull(ksoupExtractCfiDocPath("epubcfi(/6/4/2/1:42)"))
    }

    @Test
    fun `ksoupExtractCfiDocPath returns null for missing epubcfi prefix`() {
        assertNull(ksoupExtractCfiDocPath("/6/4!/4/2/1:42"))
    }

    // ── ksoupParseCfiDocPath ────────────────────────────────────────────────────

    @Test
    fun `ksoupParseCfiDocPath parses simple path without ID assertions`() {
        val result = ksoupParseCfiDocPath("/4/2/1:342")
        assertNotNull(result)
        assertEquals(listOf(4, 2, 1), result.steps)
        assertEquals(342, result.charOffset)
    }

    @Test
    fun `ksoupParseCfiDocPath strips ID assertions from all steps`() {
        val result = ksoupParseCfiDocPath("/4[body]/10[p5]/2[span]/1:0")
        assertNotNull(result)
        assertEquals(listOf(4, 10, 2, 1), result.steps)
        assertEquals(0, result.charOffset)
    }

    @Test
    fun `ksoupParseCfiDocPath handles path with no character offset step`() {
        val result = ksoupParseCfiDocPath("/4/2")
        assertNotNull(result)
        assertEquals(listOf(4, 2), result.steps)
        assertEquals(0, result.charOffset)
    }

    @Test
    fun `ksoupParseCfiDocPath returns null for empty path`() {
        assertNull(ksoupParseCfiDocPath(""))
        assertNull(ksoupParseCfiDocPath("/"))
    }

    @Test
    fun `ksoupParseCfiDocPath returns null for non-numeric step`() {
        assertNull(ksoupParseCfiDocPath("/4/abc/1:0"))
    }

    // ── ksoupWalkCfiSteps ───────────────────────────────────────────────────────

    private val simpleHtml = "<html><body><p>Hello world</p><p>Second paragraph</p></body></html>"

    @Test
    fun `ksoupWalkCfiSteps navigates to first paragraph`() {
        val doc = Ksoup.parse(simpleHtml)
        val htmlEl = doc.child(0)
        val node = ksoupWalkCfiSteps(htmlEl, listOf(4, 2))
        assertEquals("p", (node as? Element)?.tagName())
        assertEquals("Hello world", (node as Element).text())
    }

    @Test
    fun `ksoupWalkCfiSteps odd step navigates to text node`() {
        val doc = Ksoup.parse(simpleHtml)
        val htmlEl = doc.child(0)
        val node = ksoupWalkCfiSteps(htmlEl, listOf(4, 2, 1))
        assertTrue(node is TextNode)
        assertEquals("Hello world", (node as TextNode).text())
    }

    @Test
    fun `ksoupWalkCfiSteps returns null for step beyond available children`() {
        val doc = Ksoup.parse(simpleHtml)
        val htmlEl = doc.child(0)
        assertNull(ksoupWalkCfiSteps(htmlEl, listOf(4, 6)))
    }

    @Test
    fun `ksoupWalkCfiSteps returns null when even step applied to non-element`() {
        val doc = Ksoup.parse(simpleHtml)
        val htmlEl = doc.child(0)
        assertNull(ksoupWalkCfiSteps(htmlEl, listOf(4, 2, 1, 2)))
    }

    // ── ksoupCountBodyChars / ksoupCountCharsBefore / ksoupFindNodeAtChar ──────────

    @Test
    fun `ksoupCountBodyChars counts only non-blank text nodes`() {
        val doc = Ksoup.parse(simpleHtml)
        assertEquals(27L, ksoupCountBodyChars(doc.body()))
    }

    @Test
    fun `ksoupCountBodyChars counts nested text`() {
        val html = "<html><body><p>Hello <em>world</em>!</p></body></html>"
        val doc = Ksoup.parse(html)
        assertEquals(12L, ksoupCountBodyChars(doc.body()))
    }

    @Test
    fun `ksoupCountCharsBefore counts preceding text nodes`() {
        val doc = Ksoup.parse(simpleHtml)
        val body = doc.body()
        val target = doc.select("p")[1].textNodes().first()
        assertEquals(14L, ksoupCountCharsBefore(body, target, 3))
    }

    @Test
    fun `ksoupCountCharsBefore returns minus one when target not in subtree`() {
        val doc1 = Ksoup.parse("<html><body><p>AAA</p></body></html>")
        val doc2 = Ksoup.parse("<html><body><p>BBB</p></body></html>")
        val foreignNode = doc2.select("p").first()!!.textNodes().first()
        assertEquals(-1L, ksoupCountCharsBefore(doc1.body(), foreignNode, 0))
    }

    @Test
    fun `ksoupFindNodeAtChar mid-node returns correct text node and offset`() {
        val doc = Ksoup.parse("<html><body><p>Hello</p><p>World</p></body></html>")
        val (node, offset) = ksoupFindNodeAtChar(doc.body(), 7L)!!
        assertEquals("World", node.getWholeText())
        assertEquals(2, offset)
    }

    @Test
    fun `ksoupFindNodeAtChar returns null beyond total length`() {
        val doc = Ksoup.parse("<html><body><p>Hi</p></body></html>")
        assertNull(ksoupFindNodeAtChar(doc.body(), 100L))
    }

    // ── ksoupBuildCfiDocPath ────────────────────────────────────────────────────

    @Test
    fun `ksoupBuildCfiDocPath builds correct path for first paragraph text node`() {
        val doc = Ksoup.parse(simpleHtml)
        val htmlEl = doc.child(0)
        val textNode = doc.select("p").first()!!.textNodes().first()
        assertEquals("/4/2/1:5", ksoupBuildCfiDocPath(htmlEl, textNode, 5))
    }

    @Test
    fun `ksoupBuildCfiDocPath includes id assertion for element with id`() {
        val doc = Ksoup.parse("<html><body><p id=\"para1\">Hello world</p></body></html>")
        val htmlEl = doc.child(0)
        val textNode = doc.select("p").first()!!.textNodes().first()
        assertEquals("/4/2[para1]/1:3", ksoupBuildCfiDocPath(htmlEl, textNode, 3))
    }

    @Test
    fun `ksoupBuildCfiDocPath returns null when text node not in htmlEl subtree`() {
        val doc1 = Ksoup.parse("<html><body><p>A</p></body></html>")
        val doc2 = Ksoup.parse("<html><body><p>B</p></body></html>")
        val foreignNode = doc2.select("p").first()!!.textNodes().first()
        assertNull(ksoupBuildCfiDocPath(doc1.child(0), foreignNode, 0))
    }

    // ── ksoupCfiDocPathToProgression ────────────────────────────────────────────

    @Test
    fun `ksoupCfiDocPathToProgression start of first paragraph is near zero`() {
        val prog = ksoupCfiDocPathToProgression("/4/2/1:0", simpleHtml)
        assertNotNull(prog)
        assertEquals(0.0, prog, 0.001)
    }

    @Test
    fun `ksoupCfiDocPathToProgression mid first paragraph`() {
        val prog = ksoupCfiDocPathToProgression("/4/2/1:5", simpleHtml)
        assertNotNull(prog)
        assertEquals(5.0 / 27.0, prog, 0.001)
    }

    @Test
    fun `ksoupCfiDocPathToProgression returns null for path pointing to element not text`() {
        assertNull(ksoupCfiDocPathToProgression("/4/2", "<html><body><p>Hello</p></body></html>"))
    }

    @Test
    fun `ksoupCfiDocPathToProgression returns null for empty body`() {
        assertNull(ksoupCfiDocPathToProgression("/4/2/1:0", "<html><body></body></html>"))
    }

    @Test
    fun `ksoupCfiDocPathToProgression works with nested inline elements`() {
        val html = "<html><body><p>Hello <em>world</em>!</p></body></html>"
        val prog = ksoupCfiDocPathToProgression("/4/2/3:0", html)
        assertNotNull(prog)
        assertEquals(11.0 / 12.0, prog, 0.001)
    }

    // ── ID-anchored navigation ────────────────────────────────────────────────

    @Test
    fun `ksoupCfiDocPathToProgression uses ID anchor when present`() {
        val html = "<html><body><p>First</p><p id=\"second\">Second</p></body></html>"
        val prog = ksoupCfiDocPathToProgression("/4/4[second]/1:0", html)
        assertNotNull(prog)
        assertEquals(5.0 / 11.0, prog, 0.001)
    }

    @Test
    fun `ksoupCfiDocPathToProgression falls back to numeric when ID not in html`() {
        val prog = ksoupCfiDocPathToProgression("/4/4[nonexistent]/1:0", simpleHtml)
        assertNotNull(prog)
        assertEquals(11.0 / 27.0, prog, 0.001)
    }

    @Test
    fun `ksoupCfiDocPathToProgression deepest ID wins when multiple IDs present`() {
        val html = "<html><body><p>AAA</p><div id=\"outer\"><p id=\"inner\">Inner text</p></div></body></html>"
        val prog = ksoupCfiDocPathToProgression("/4[body]/4[outer]/2[inner]/1:2", html)
        assertNotNull(prog)
        assertEquals(5.0 / 13.0, prog, 0.001)
    }

    // ── ksoupProgressionToCfiDocPath ────────────────────────────────────────────

    @Test
    fun `ksoupProgressionToCfiDocPath zero returns path at start of body`() {
        val path = ksoupProgressionToCfiDocPath(0.0, simpleHtml)
        assertNotNull(path)
        assertTrue(path.endsWith(":0"))
    }

    @Test
    fun `ksoupProgressionToCfiDocPath returns null for empty body`() {
        assertNull(ksoupProgressionToCfiDocPath(0.5, "<html><body></body></html>"))
    }

    @Test
    fun `ksoupProgressionToCfiDocPath emits id assertion when element has id`() {
        val html = "<html><body><p>First</p><h2 id=\"section\">Section</h2></body></html>"
        val path = ksoupProgressionToCfiDocPath(5.0 / 12.0, html)
        assertNotNull(path)
        assertTrue(path.contains("[section]"), "Expected [section] in $path")
    }

    // ── Round-trip ────────────────────────────────────────────────────────────

    @Test
    fun `round-trip cfi to progression to cfi is self-consistent`() {
        val originalPath = "/4/2/1:5"
        val progression = ksoupCfiDocPathToProgression(originalPath, simpleHtml)!!
        val rebuiltPath = ksoupProgressionToCfiDocPath(progression, simpleHtml)!!
        val rebuiltProgression = ksoupCfiDocPathToProgression(rebuiltPath, simpleHtml)!!
        assertEquals(progression, rebuiltProgression, 1.0 / 27.0)
    }

    @Test
    fun `round-trip progression to cfi to progression is self-consistent`() {
        val samples = listOf(0.0, 0.1, 0.25, 0.5, 0.75, 0.99)
        for (original in samples) {
            val path = ksoupProgressionToCfiDocPath(original, simpleHtml) ?: continue
            val recovered = ksoupCfiDocPathToProgression(path, simpleHtml)!!
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
        val path = ksoupProgressionToCfiDocPath(originalProg, html)!!
        val recovered = ksoupCfiDocPathToProgression(path, html)!!
        assertEquals(originalProg, recovered, 1.0 / 21.0, "Round-trip failed for progression=$originalProg")
    }
}
