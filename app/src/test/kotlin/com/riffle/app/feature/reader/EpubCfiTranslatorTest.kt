package com.riffle.app.feature.reader

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// simpleHtml: body = "Hello world"(11) + "Second paragraph"(16) = 27 chars total; body=4, p[0]=2, p[1]=4
class EpubCfiTranslatorTest {

    private val simpleHtml = """
        <html>
          <head><title>Test</title></head>
          <body>
            <p>Hello world</p>
            <p>Second paragraph</p>
          </body>
        </html>
    """.trimIndent()

    // ── extractCfiDocPath ─────────────────────────────────────────────────────

    @Test
    fun `extractCfiDocPath returns doc portion for well-formed CFI`() {
        assertEquals("/4/2/1:42", extractCfiDocPath("epubcfi(/6/4!/4/2/1:42)"))
    }

    @Test
    fun `extractCfiDocPath returns doc portion with ID assertions intact`() {
        assertEquals("/4[body]/10[p5]/1:0", extractCfiDocPath("epubcfi(/6/4[ch1]!/4[body]/10[p5]/1:0)"))
    }

    @Test
    fun `extractCfiDocPath returns null for missing bang separator`() {
        assertNull(extractCfiDocPath("epubcfi(/6/4/2/1:42)"))
    }

    @Test
    fun `extractCfiDocPath returns null for missing epubcfi prefix`() {
        assertNull(extractCfiDocPath("/6/4!/4/2/1:42"))
    }

    @Test
    fun `extractCfiDocPath returns null for missing closing paren`() {
        assertNull(extractCfiDocPath("epubcfi(/6/4!/4/2/1:42"))
    }

    @Test
    fun `extractCfiDocPath returns null for empty string`() {
        assertNull(extractCfiDocPath(""))
    }

    @Test
    fun `extractCfiDocPath returns null when doc portion is empty`() {
        // Bang right before closing paren — empty doc path
        assertNull(extractCfiDocPath("epubcfi(/6/4!)"))
    }

    // ── parseCfiDocPath ───────────────────────────────────────────────────────

    @Test
    fun `parseCfiDocPath parses simple path without ID assertions`() {
        val result = parseCfiDocPath("/4/2/1:342")
        assertNotNull(result)
        assertEquals(listOf(4, 2, 1), result!!.steps)
        assertEquals(342, result.charOffset)
    }

    @Test
    fun `parseCfiDocPath strips ID assertions from all steps`() {
        val result = parseCfiDocPath("/4[body]/10[p5]/2[span]/1:0")
        assertNotNull(result)
        assertEquals(listOf(4, 10, 2, 1), result!!.steps)
        assertEquals(0, result.charOffset)
    }

    @Test
    fun `parseCfiDocPath handles zero character offset`() {
        val result = parseCfiDocPath("/4/2/1:0")
        assertNotNull(result)
        assertEquals(0, result!!.charOffset)
    }

    @Test
    fun `parseCfiDocPath handles large character offset`() {
        val result = parseCfiDocPath("/4/100/1:9999")
        assertNotNull(result)
        assertEquals(9999, result!!.charOffset)
    }

    @Test
    fun `parseCfiDocPath handles path with no character offset step`() {
        // Some CFIs point to an element, not a text node — no colon terminus
        val result = parseCfiDocPath("/4/2")
        assertNotNull(result)
        assertEquals(listOf(4, 2), result!!.steps)
        assertEquals(0, result.charOffset)
    }

    @Test
    fun `parseCfiDocPath returns null for empty path`() {
        assertNull(parseCfiDocPath(""))
        assertNull(parseCfiDocPath("/"))
    }

    @Test
    fun `parseCfiDocPath returns null for non-numeric step`() {
        assertNull(parseCfiDocPath("/4/abc/1:0"))
    }

    @Test
    fun `parseCfiDocPath returns null for non-numeric char offset`() {
        assertNull(parseCfiDocPath("/4/2/1:xyz"))
    }

    // ── walkCfiSteps ──────────────────────────────────────────────────────────

    @Test
    fun `walkCfiSteps even step navigates to element child`() {
        val doc = Jsoup.parse(simpleHtml)
        val htmlEl = doc.child(0)!!
        // Step 4 from html → body (2nd element child of html: head=2, body=4)
        val node = walkCfiSteps(htmlEl, listOf(4))
        assertEquals("body", (node as? org.jsoup.nodes.Element)?.tagName())
    }

    @Test
    fun `walkCfiSteps navigates to first paragraph`() {
        val doc = Jsoup.parse(simpleHtml)
        val htmlEl = doc.child(0)!!
        // /4 → body, /2 → first <p>
        val node = walkCfiSteps(htmlEl, listOf(4, 2))
        assertEquals("p", (node as? org.jsoup.nodes.Element)?.tagName())
        assertEquals("Hello world", (node as org.jsoup.nodes.Element).text())
    }

    @Test
    fun `walkCfiSteps odd step navigates to text node`() {
        val doc = Jsoup.parse(simpleHtml)
        val htmlEl = doc.child(0)!!
        // /4/2/1 → text node of first <p>
        val node = walkCfiSteps(htmlEl, listOf(4, 2, 1))
        assertTrue(node is org.jsoup.nodes.TextNode)
        assertEquals("Hello world", (node as org.jsoup.nodes.TextNode).text())
    }

    @Test
    fun `walkCfiSteps navigates to second paragraph text node`() {
        val doc = Jsoup.parse(simpleHtml)
        val htmlEl = doc.child(0)!!
        val node = walkCfiSteps(htmlEl, listOf(4, 4, 1))
        assertTrue(node is org.jsoup.nodes.TextNode)
        assertEquals("Second paragraph", (node as org.jsoup.nodes.TextNode).text())
    }

    @Test
    fun `walkCfiSteps returns null for step beyond available children`() {
        val doc = Jsoup.parse(simpleHtml)
        val htmlEl = doc.child(0)!!
        // Body only has 2 element children; step /6 would be the 3rd → null
        assertNull(walkCfiSteps(htmlEl, listOf(4, 6)))
    }

    @Test
    fun `walkCfiSteps returns null for odd step pointing to non-existent text node`() {
        val doc = Jsoup.parse(simpleHtml)
        val htmlEl = doc.child(0)!!
        // First <p> has only one text node; step /3 would be the 2nd text node → null
        assertNull(walkCfiSteps(htmlEl, listOf(4, 2, 3)))
    }

    @Test
    fun `walkCfiSteps returns null when even step applied to non-element`() {
        val doc = Jsoup.parse(simpleHtml)
        val htmlEl = doc.child(0)!!
        // Navigate to text node first, then try even step from it → null
        assertNull(walkCfiSteps(htmlEl, listOf(4, 2, 1, 2)))
    }

    // ── countBodyChars ────────────────────────────────────────────────────────

    @Test
    fun `countBodyChars counts only non-blank text nodes`() {
        val doc = Jsoup.parse(simpleHtml)
        // Whitespace-only text nodes (indentation between tags) are excluded.
        // Only "Hello world" (11) + "Second paragraph" (16) = 27 are counted.
        val total = countBodyChars(doc.body()!!)
        assertEquals(27L, total)
    }

    @Test
    fun `countBodyChars returns zero for body with no text`() {
        val html = "<html><body><div></div></body></html>"
        val doc = Jsoup.parse(html)
        assertEquals(0L, countBodyChars(doc.body()!!))
    }

    @Test
    fun `countBodyChars counts nested text`() {
        val html = "<html><body><p>Hello <em>world</em>!</p></body></html>"
        val doc = Jsoup.parse(html)
        // "Hello " (6) + "world" (5) + "!" (1) = 12
        assertEquals(12L, countBodyChars(doc.body()!!))
    }

    // ── countCharsBefore ──────────────────────────────────────────────────────

    @Test
    fun `countCharsBefore returns offset when target is first text node`() {
        val html = "<html><body><p>Hello world</p><p>Second</p></body></html>"
        val doc = Jsoup.parse(html)
        val body = doc.body()!!
        // Find "Hello world" text node
        val target = doc.select("p").first()!!.textNodes().first()
        // 0 chars before it + offset 5
        assertEquals(5L, countCharsBefore(body, target, 5))
    }

    @Test
    fun `countCharsBefore counts preceding text nodes`() {
        val html = "<html><body><p>Hello world</p><p>Second</p></body></html>"
        val doc = Jsoup.parse(html)
        val body = doc.body()!!
        val target = doc.select("p")[1].textNodes().first()
        // "Hello world" (11) before "Second", plus offset 3
        assertEquals(14L, countCharsBefore(body, target, 3))
    }

    @Test
    fun `countCharsBefore returns minus one when target not in subtree`() {
        val html1 = "<html><body><p>AAA</p></body></html>"
        val html2 = "<html><body><p>BBB</p></body></html>"
        val doc1 = Jsoup.parse(html1)
        val doc2 = Jsoup.parse(html2)
        val foreignNode = doc2.select("p").first()!!.textNodes().first()
        assertEquals(-1L, countCharsBefore(doc1.body()!!, foreignNode, 0))
    }

    @Test
    fun `countCharsBefore with zero offset returns chars before target`() {
        val html = "<html><body><p>Abc</p><p>Def</p></body></html>"
        val doc = Jsoup.parse(html)
        val body = doc.body()!!
        val target = doc.select("p")[1].textNodes().first()
        assertEquals(3L, countCharsBefore(body, target, 0)) // "Abc" = 3 chars before
    }

    // ── findNodeAtChar ────────────────────────────────────────────────────────

    @Test
    fun `findNodeAtChar zero returns start of first text node`() {
        val html = "<html><body><p>Hello</p></body></html>"
        val doc = Jsoup.parse(html)
        val (node, offset) = findNodeAtChar(doc.body()!!, 0L)!!
        assertEquals("Hello", node.wholeText)
        assertEquals(0, offset)
    }

    @Test
    fun `findNodeAtChar mid-node returns correct text node and offset`() {
        val html = "<html><body><p>Hello</p><p>World</p></body></html>"
        val doc = Jsoup.parse(html)
        // char 7 = 2nd char of "World" (0-4 = Hello, 5-9 = World → 7 = 'o')
        val (node, offset) = findNodeAtChar(doc.body()!!, 7L)!!
        assertEquals("World", node.wholeText)
        assertEquals(2, offset)
    }

    @Test
    fun `findNodeAtChar boundary last char of first node`() {
        val html = "<html><body><p>ABC</p><p>DEF</p></body></html>"
        val doc = Jsoup.parse(html)
        // char 2 = last char of "ABC"
        val (node, offset) = findNodeAtChar(doc.body()!!, 2L)!!
        assertEquals("ABC", node.wholeText)
        assertEquals(2, offset)
    }

    @Test
    fun `findNodeAtChar boundary first char of second node`() {
        val html = "<html><body><p>ABC</p><p>DEF</p></body></html>"
        val doc = Jsoup.parse(html)
        // char 3 = first char of "DEF"
        val (node, offset) = findNodeAtChar(doc.body()!!, 3L)!!
        assertEquals("DEF", node.wholeText)
        assertEquals(0, offset)
    }

    @Test
    fun `findNodeAtChar returns null beyond total length`() {
        val html = "<html><body><p>Hi</p></body></html>"
        val doc = Jsoup.parse(html)
        assertNull(findNodeAtChar(doc.body()!!, 100L))
    }

    // ── buildCfiDocPath ───────────────────────────────────────────────────────

    @Test
    fun `buildCfiDocPath builds correct path for first paragraph text node`() {
        val html = "<html><body><p>Hello world</p><p>Second</p></body></html>"
        val doc = Jsoup.parse(html)
        val htmlEl = doc.child(0)!!
        val textNode = doc.select("p").first()!!.textNodes().first()
        val path = buildCfiDocPath(htmlEl, textNode, 5)
        // html→body=4, body→p=2, p→text=1 → /4/2/1:5
        assertEquals("/4/2/1:5", path)
    }

    @Test
    fun `buildCfiDocPath builds correct path for second paragraph text node`() {
        val html = "<html><body><p>Hello world</p><p>Second</p></body></html>"
        val doc = Jsoup.parse(html)
        val htmlEl = doc.child(0)!!
        val textNode = doc.select("p")[1].textNodes().first()
        val path = buildCfiDocPath(htmlEl, textNode, 0)
        // html→body=4, body→p[1]=4, p→text=1 → /4/4/1:0
        assertEquals("/4/4/1:0", path)
    }

    @Test
    fun `buildCfiDocPath builds correct path for nested element`() {
        val html = "<html><body><div><p>Nested text</p></div></body></html>"
        val doc = Jsoup.parse(html)
        val htmlEl = doc.child(0)!!
        val textNode = doc.select("p").first()!!.textNodes().first()
        val path = buildCfiDocPath(htmlEl, textNode, 3)
        // html→body=4, body→div=2, div→p=2, p→text=1 → /4/2/2/1:3
        assertEquals("/4/2/2/1:3", path)
    }

    @Test
    fun `buildCfiDocPath returns null when text node not in htmlEl subtree`() {
        val html1 = "<html><body><p>A</p></body></html>"
        val html2 = "<html><body><p>B</p></body></html>"
        val doc1 = Jsoup.parse(html1)
        val doc2 = Jsoup.parse(html2)
        val foreignNode = doc2.select("p").first()!!.textNodes().first()
        assertNull(buildCfiDocPath(doc1.child(0)!!, foreignNode, 0))
    }

    // ── cfiDocPathToProgression ───────────────────────────────────────────────

    @Test
    fun `cfiDocPathToProgression start of first paragraph is near zero`() {
        // Use compact HTML to avoid whitespace text nodes affecting the count
        val html = "<html><body><p>Hello world</p><p>Second paragraph</p></body></html>"
        val prog = cfiDocPathToProgression("/4/2/1:0", html)
        assertNotNull(prog)
        assertEquals(0.0, prog!!, 0.001)
    }

    @Test
    fun `cfiDocPathToProgression mid first paragraph`() {
        val html = "<html><body><p>Hello world</p><p>Second paragraph</p></body></html>"
        // "Hello world" = 11, "Second paragraph" = 16, total = 27
        // char 5 → progression = 5/27
        val prog = cfiDocPathToProgression("/4/2/1:5", html)
        assertNotNull(prog)
        assertEquals(5.0 / 27.0, prog!!, 0.001)
    }

    @Test
    fun `cfiDocPathToProgression start of second paragraph`() {
        val html = "<html><body><p>Hello world</p><p>Second paragraph</p></body></html>"
        // 11 chars of first paragraph before second
        val prog = cfiDocPathToProgression("/4/4/1:0", html)
        assertNotNull(prog)
        assertEquals(11.0 / 27.0, prog!!, 0.001)
    }

    @Test
    fun `cfiDocPathToProgression end of second paragraph approaches 1`() {
        val html = "<html><body><p>Hello world</p><p>Second paragraph</p></body></html>"
        // "Second paragraph" = 16, char 15 → (11+15)/27 = 26/27
        val prog = cfiDocPathToProgression("/4/4/1:15", html)
        assertNotNull(prog)
        assertEquals(26.0 / 27.0, prog!!, 0.001)
    }

    @Test
    fun `cfiDocPathToProgression with ID assertions in path`() {
        val html = "<html><body><p>Hello world</p><p>Second paragraph</p></body></html>"
        val prog = cfiDocPathToProgression("/4[body]/2[first]/1:5", html)
        assertNotNull(prog)
        assertEquals(5.0 / 27.0, prog!!, 0.001)
    }

    @Test
    fun `cfiDocPathToProgression returns null for path pointing to element not text`() {
        val html = "<html><body><p>Hello</p></body></html>"
        // /4/2 points to <p>, not a TextNode
        assertNull(cfiDocPathToProgression("/4/2", html))
    }

    @Test
    fun `cfiDocPathToProgression returns null for invalid step`() {
        val html = "<html><body><p>Hello</p></body></html>"
        assertNull(cfiDocPathToProgression("/4/999/1:0", html))
    }

    @Test
    fun `cfiDocPathToProgression returns null for empty body`() {
        val html = "<html><body></body></html>"
        assertNull(cfiDocPathToProgression("/4/2/1:0", html))
    }

    @Test
    fun `cfiDocPathToProgression works with nested inline elements`() {
        // "Hello " (6) + "world" (5) + "!" (1) = 12 total
        val html = "<html><body><p>Hello <em>world</em>!</p></body></html>"
        // "!" is the 2nd non-blank text node of <p> → step /3
        val prog = cfiDocPathToProgression("/4/2/3:0", html)
        assertNotNull(prog)
        // chars before "!" = "Hello " (6) + "world" (5) = 11, plus offset 0
        assertEquals(11.0 / 12.0, prog!!, 0.001)
    }

    @Test
    fun `cfiDocPathToProgression for text inside nested element`() {
        val html = "<html><body><p>Hello <em>world</em>!</p></body></html>"
        // "world" is inside <em>: step /4/2/2/1 (html→body=4, body→p=2, p→em=2, em→text=1)
        val prog = cfiDocPathToProgression("/4/2/2/1:3", html)
        assertNotNull(prog)
        // chars before: "Hello " (6) + offset 3 = 9, total = 12
        assertEquals(9.0 / 12.0, prog!!, 0.001)
    }

    // ── progressionToCfiDocPath ───────────────────────────────────────────────

    @Test
    fun `progressionToCfiDocPath zero returns path at start of body`() {
        val html = "<html><body><p>Hello world</p><p>Second paragraph</p></body></html>"
        val path = progressionToCfiDocPath(0.0, html)
        assertNotNull(path)
        // Should point to the first text node at offset 0
        assertTrue(path!!.endsWith(":0"))
    }

    @Test
    fun `progressionToCfiDocPath one returns path near end of body`() {
        val html = "<html><body><p>Hello world</p><p>Second paragraph</p></body></html>"
        val path = progressionToCfiDocPath(1.0, html)
        assertNotNull(path)
        assertNotNull(path)
    }

    @Test
    fun `progressionToCfiDocPath returns null for empty body`() {
        val html = "<html><body></body></html>"
        assertNull(progressionToCfiDocPath(0.5, html))
    }

    @Test
    fun `progressionToCfiDocPath mid progression lands in correct paragraph`() {
        val html = "<html><body><p>Hello world</p><p>Second paragraph</p></body></html>"
        // total = 27, mid = 0.5 → char 13 → inside "Second paragraph" (starts at 11)
        val path = progressionToCfiDocPath(0.5, html)
        assertNotNull(path)
        // Should reference /4/4/1 (second paragraph text node)
        assertTrue("Expected second paragraph reference in $path", path!!.contains("/4/1:"))
    }

    // ── Round-trip tests ──────────────────────────────────────────────────────

    @Test
    fun `round-trip cfi to progression to cfi is self-consistent`() {
        val html = "<html><body><p>Hello world</p><p>Second paragraph</p></body></html>"
        val originalPath = "/4/2/1:5"
        val progression = cfiDocPathToProgression(originalPath, html)!!
        val rebuiltPath = progressionToCfiDocPath(progression, html)!!
        val rebuiltProgression = cfiDocPathToProgression(rebuiltPath, html)!!
        // The rebuilt progression should be within 1 character of the original
        assertEquals(progression, rebuiltProgression, 1.0 / 27.0)
    }

    @Test
    fun `round-trip progression to cfi to progression is self-consistent`() {
        val html = "<html><body><p>Hello world</p><p>Second paragraph</p></body></html>"
        val samples = listOf(0.0, 0.1, 0.25, 0.5, 0.75, 0.99)
        for (original in samples) {
            val path = progressionToCfiDocPath(original, html) ?: continue
            val recovered = cfiDocPathToProgression(path, html)!!
            assertEquals(
                "Round-trip failed for progression=$original",
                original, recovered, 1.0 / 27.0
            )
        }
    }

    @Test
    fun `round-trip preserves ordering across paragraphs`() {
        val html = "<html><body><p>Hello world</p><p>Second paragraph</p></body></html>"
        val progressions = listOf(0.0, 0.2, 0.4, 0.6, 0.8, 1.0)
            .map { p -> progressionToCfiDocPath(p, html)?.let { cfiDocPathToProgression(it, html) } ?: p }
        for (i in 1 until progressions.size) {
            assertTrue(
                "Ordering not preserved: ${progressions[i - 1]} > ${progressions[i]}",
                progressions[i - 1] <= progressions[i] + 0.001
            )
        }
    }

    // ── Full-stack integration via extractCfiDocPath ──────────────────────────

    @Test
    fun `full stack extract doc path then convert to progression`() {
        val html = "<html><body><p>Hello world</p><p>Second paragraph</p></body></html>"
        val fullCfi = "epubcfi(/6/4[ch1]!/4/2/1:5)"
        val docPath = extractCfiDocPath(fullCfi)!!
        val prog = cfiDocPathToProgression(docPath, html)!!
        assertEquals(5.0 / 27.0, prog, 0.001)
    }

    @Test
    fun `full stack zero-offset CFI from start of book`() {
        val html = "<html><body><p>Content here</p></body></html>"
        val fullCfi = "epubcfi(/6/2!/4/2/1:0)"
        val docPath = extractCfiDocPath(fullCfi)!!
        val prog = cfiDocPathToProgression(docPath, html)!!
        assertEquals(0.0, prog, 0.001)
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `handles single text node spanning entire body`() {
        val html = "<html><body><p>Only text</p></body></html>"
        val prog = cfiDocPathToProgression("/4/2/1:4", html)
        assertNotNull(prog)
        assertEquals(4.0 / 9.0, prog!!, 0.001) // "Only text" = 9 chars
    }

    @Test
    fun `handles deeply nested structure`() {
        val html = "<html><body><div><section><article><p>Deep text</p></article></section></div></body></html>"
        // html→body=4, body→div=2, div→section=2, section→article=2, article→p=2, p→text=1
        val prog = cfiDocPathToProgression("/4/2/2/2/2/1:4", html)
        assertNotNull(prog)
        assertEquals(4.0 / 9.0, prog!!, 0.001) // "Deep text" = 9 chars
    }

    @Test
    fun `handles EPUB with multiple text nodes in one paragraph`() {
        // <p>First <strong>bold</strong> end</p>
        // text nodes of <p>: "First " (step 1), "bold" inside strong, " end" (step 3)
        val html = "<html><body><p>First <strong>bold</strong> end</p></body></html>"
        // " end" is the 2nd non-blank text child of <p> → step /3
        // chars: "First " (6) + "bold" (4) = 10 before " end"
        val prog = cfiDocPathToProgression("/4/2/3:1", html)
        assertNotNull(prog)
        val total = 6 + 4 + 4 // "First " + "bold" + " end"
        assertEquals((10 + 1).toDouble() / total, prog!!, 0.001)
    }

    @Test
    fun `cfiDocPathToProgression is bounded to 0 and 1`() {
        val html = "<html><body><p>Short</p></body></html>"
        // Offset beyond node length — coerced to 1.0 (charsBefore = 5 = total)
        val prog = cfiDocPathToProgression("/4/2/1:5", html) // "Short" = 5 chars, :5 = at end
        assertNotNull(prog)
        assertEquals(1.0, prog!!, 0.001)
    }

    // ── extractCfiElementIds ──────────────────────────────────────────────────

    @Test
    fun `extractCfiElementIds returns deepest-first for multi-ID path`() {
        val ids = extractCfiElementIds("/4[body]/10[para5]/2/1:0")
        assertEquals(listOf("para5", "body"), ids)
    }

    @Test
    fun `extractCfiElementIds returns empty for path with no assertions`() {
        assertTrue(extractCfiElementIds("/4/10/2/1:0").isEmpty())
    }

    @Test
    fun `extractCfiElementIds skips odd steps`() {
        // Odd step /1 carries no element ID assertion in epub.js
        val ids = extractCfiElementIds("/4[body]/2/1:0")
        assertEquals(listOf("body"), ids)
    }

    @Test
    fun `extractCfiElementIds handles single ID`() {
        assertEquals(listOf("s1"), extractCfiElementIds("/4/12[s1]/1:0"))
    }

    @Test
    fun `extractCfiElementIds ignores empty assertions`() {
        // Malformed assertion [] should be ignored
        assertTrue(extractCfiElementIds("/4[]/2/1:0").isEmpty())
    }

    @Test
    fun `extractCfiElementIds with ID on last element step before text offset`() {
        assertEquals(listOf("p2"), extractCfiElementIds("/4/4[p2]/1:7"))
    }

    // ── hasElementWithId ──────────────────────────────────────────────────────

    @Test
    fun `hasElementWithId returns true when id exists`() {
        val html = "<html><body><p id=\"target\">Hello</p></body></html>"
        assertTrue(hasElementWithId(html, "target"))
    }

    @Test
    fun `hasElementWithId returns false when id absent`() {
        val html = "<html><body><p>Hello</p></body></html>"
        assertTrue(!hasElementWithId(html, "target"))
    }

    @Test
    fun `hasElementWithId returns false for empty html`() {
        assertTrue(!hasElementWithId("<html><body></body></html>", "anything"))
    }

    @Test
    fun `hasElementWithId finds deeply nested element`() {
        val html = "<html><body><div><section><p id=\"deep\">Text</p></section></div></body></html>"
        assertTrue(hasElementWithId(html, "deep"))
        assertTrue(!hasElementWithId(html, "shallow"))
    }

    // ── extractAnchorFromCfi ─────────────────────────────────────────────────

    @Test
    fun `extractAnchorFromCfi returns innermost element id present in html`() {
        val html = "<html><body><div id=\"outer\"><p id=\"inner\">Hello</p></div></body></html>"
        val cfi = "epubcfi(/6/4!/4/2[outer]/2[inner]/1:5)"
        assertEquals("inner", extractAnchorFromCfi(cfi, html))
    }

    @Test
    fun `extractAnchorFromCfi falls back to next id when innermost is absent`() {
        val html = "<html><body><div id=\"outer\"><p>Hello</p></div></body></html>"
        val cfi = "epubcfi(/6/4!/4/2[outer]/2[missing]/1:5)"
        assertEquals("outer", extractAnchorFromCfi(cfi, html))
    }

    @Test
    fun `extractAnchorFromCfi returns null when no element ids in cfi`() {
        val html = "<html><body><p>Hello</p></body></html>"
        assertNull(extractAnchorFromCfi("epubcfi(/6/4!/4/2/1:5)", html))
    }

    @Test
    fun `extractAnchorFromCfi returns null when no ids match html`() {
        val html = "<html><body><p>Hello</p></body></html>"
        assertNull(extractAnchorFromCfi("epubcfi(/6/4!/4/2[gone]/1:5)", html))
    }

    @Test
    fun `extractAnchorFromCfi works with range cfi extracting id from base path`() {
        val html = "<html><body><p id=\"p1\">Hello world</p></body></html>"
        val cfi = "epubcfi(/6/4!/4/2[p1]/1,/1:0,/1:5)"
        assertEquals("p1", extractAnchorFromCfi(cfi, html))
    }

    // ── ID-anchored cfiDocPathToProgression ───────────────────────────────────

    @Test
    fun `cfiDocPathToProgression uses ID anchor when present`() {
        // HTML: "First" (5) | <p id="second">Second</p> (6) = total 11
        val html = "<html><body><p>First</p><p id=\"second\">Second</p></body></html>"
        // CFI with ID assertion pointing to start of "second"
        val prog = cfiDocPathToProgression("/4/4[second]/1:0", html)
        assertNotNull(prog)
        assertEquals(5.0 / 11.0, prog!!, 0.001)
    }

    @Test
    fun `cfiDocPathToProgression ID anchor mid-word offset`() {
        val html = "<html><body><p>First</p><p id=\"second\">Second</p></body></html>"
        // char 3 into "Second" → (5 + 3) / 11
        val prog = cfiDocPathToProgression("/4/4[second]/1:3", html)
        assertNotNull(prog)
        assertEquals(8.0 / 11.0, prog!!, 0.001)
    }

    @Test
    fun `cfiDocPathToProgression falls back to numeric when ID not in html`() {
        // Same HTML but CFI references an ID that doesn't exist → numeric walk
        val html = "<html><body><p>Hello world</p><p>Second paragraph</p></body></html>"
        val prog = cfiDocPathToProgression("/4/4[nonexistent]/1:0", html)
        // Falls back to numeric: /4/4/1:0 → 11 chars before second paragraph
        assertNotNull(prog)
        assertEquals(11.0 / 27.0, prog!!, 0.001)
    }

    @Test
    fun `cfiDocPathToProgression ID anchor is image-resistant`() {
        // An image before the anchor paragraph contributes zero text chars.
        // Both ID-anchored and numeric walks give the same char-count result,
        // but the ID anchor is robust if epub.js assigns different step numbers.
        val html = "<html><body><p>Before</p><img src=\"x.png\" alt=\"\"/><p id=\"after\">After</p></body></html>"
        // "Before" = 6 chars, img = 0 chars, "After" = 5 chars → total 11
        val prog = cfiDocPathToProgression("/4/6[after]/1:0", html)
        assertNotNull(prog)
        assertEquals(6.0 / 11.0, prog!!, 0.001)
    }

    @Test
    fun `cfiDocPathToProgression deepest ID wins when multiple IDs present`() {
        // /4[body]/4[outer]/2[inner]/1:2 — "inner" is deeper and resolves
        val html = "<html><body><p>AAA</p><div id=\"outer\"><p id=\"inner\">Inner text</p></div></body></html>"
        // "AAA"=3, "Inner text"=10, total=13
        // Anchor = "inner" element (the <p>); chars before it = "AAA"(3) + 0 (outer starts immediately)
        // Within "inner": text node "Inner text", offset 2 → charsWithin = 2
        // Total: (3 + 2) / 13
        val prog = cfiDocPathToProgression("/4[body]/4[outer]/2[inner]/1:2", html)
        assertNotNull(prog)
        assertEquals(5.0 / 13.0, prog!!, 0.001)
    }

    @Test
    fun `cfiDocPathToProgression no remaining steps after anchor returns anchor start`() {
        // CFI ending at the anchor element itself (no text node step)
        val html = "<html><body><p>First</p><p id=\"second\">Second</p></body></html>"
        val prog = cfiDocPathToProgression("/4/4[second]", html)
        assertNotNull(prog)
        assertEquals(5.0 / 11.0, prog!!, 0.001)
    }

    // ── buildCfiDocPath with ID assertions ────────────────────────────────────

    @Test
    fun `buildCfiDocPath includes id assertion for element with id`() {
        val html = "<html><body><p id=\"para1\">Hello world</p></body></html>"
        val doc = Jsoup.parse(html)
        val htmlEl = doc.child(0)!!
        val textNode = doc.select("p").first()!!.textNodes().first()
        val path = buildCfiDocPath(htmlEl, textNode, 3)
        assertEquals("/4/2[para1]/1:3", path)
    }

    @Test
    fun `buildCfiDocPath omits id assertion for element without id`() {
        val html = "<html><body><p>Hello world</p></body></html>"
        val doc = Jsoup.parse(html)
        val htmlEl = doc.child(0)!!
        val textNode = doc.select("p").first()!!.textNodes().first()
        val path = buildCfiDocPath(htmlEl, textNode, 0)
        assertEquals("/4/2/1:0", path)
    }

    @Test
    fun `buildCfiDocPath includes id only on element that has it`() {
        // <body> has no id, <p id="p1"> has id — only p1 gets assertion
        val html = "<html><body><p id=\"p1\">Text</p></body></html>"
        val doc = Jsoup.parse(html)
        val htmlEl = doc.child(0)!!
        val textNode = doc.select("p").first()!!.textNodes().first()
        val path = buildCfiDocPath(htmlEl, textNode, 0)
        assertEquals("/4/2[p1]/1:0", path)
    }

    @Test
    fun `progressionToCfiDocPath emits id assertion when element has id`() {
        // "First"=5, "Section"=7, total=12. Progression 5/12 lands at the start of #section.
        val html = "<html><body><p>First</p><h2 id=\"section\">Section</h2></body></html>"
        val path = progressionToCfiDocPath(5.0 / 12.0, html)
        assertNotNull(path)
        assertTrue("Expected [section] in $path", path!!.contains("[section]"))
    }

    @Test
    fun `round-trip with id assertions is self-consistent`() {
        val html = "<html><body><p>First</p><p id=\"second\">Second paragraph</p></body></html>"
        // "First"=5, "Second paragraph"=16, total=21
        val originalProg = 0.4
        val path = progressionToCfiDocPath(originalProg, html)!!
        val recovered = cfiDocPathToProgression(path, html)!!
        assertEquals("Round-trip failed for progression=$originalProg", originalProg, recovered, 1.0 / 21.0)
    }

    @Test
    fun `cfiDocPathToProgression uses ID anchor from outbound path for round-trip`() {
        // Outbound emits IDs; inbound uses them — verify consistency
        val html = "<html><body><p>Before</p><p id=\"anchor\">Anchor paragraph</p></body></html>"
        // "Before"=6, "Anchor paragraph"=16, total=22
        val outboundPath = progressionToCfiDocPath(0.5, html)!!
        val prog = cfiDocPathToProgression(outboundPath, html)!!
        assertEquals(0.5, prog, 1.0 / 22.0)
    }
}
