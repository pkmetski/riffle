package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubTextCharsTest {

    @Test
    fun `counts only non-blank text characters, ignoring markup and whitespace`() {
        val html = "<html><body><p>Hello</p>\n  <p><b>there</b>.</p></body></html>"

        // "Hello" = 5, "there" = 5, "." = 1 → 11
        assertEquals(11L, EpubTextChars.countReadableChars(html))
    }

    @Test
    fun `progression of an element id is its readable-character offset over the total`() {
        val html = "<html><body><p>AAAA</p><p id=\"x\">BBBB</p></body></html>"

        // "AAAA" before #x = 4 chars, total = 8 → 0.5
        assertEquals(0.5, EpubTextChars.progressionOfElementId(html, "x")!!, 0.0001)
    }

    @Test
    fun `progression is null for an id that is not present`() {
        val html = "<html><body><p>AAAA</p></body></html>"

        assertNull(EpubTextChars.progressionOfElementId(html, "missing"))
    }

    @Test
    fun `batch progressions resolve many ids in one parse and omit absent ones`() {
        val html = "<html><body><p id=\"a\">AAAA</p><p id=\"b\">BBBB</p><p id=\"c\">CCCC</p></body></html>"

        val result = EpubTextChars.progressionsOfElementIds(html, setOf("a", "b", "c", "missing"))

        // a before=0/12, b before=4/12, c before=8/12; "missing" omitted.
        assertEquals(3, result.size)
        assertEquals(0.0, result.getValue("a"), 0.0001)
        assertEquals(4.0 / 12.0, result.getValue("b"), 0.0001)
        assertEquals(8.0 / 12.0, result.getValue("c"), 0.0001)
    }

    @Test
    fun `batch progressions agree with the single-id progression`() {
        val html = "<html><body><p>AAAA</p><p id=\"x\">BBBB</p></body></html>"

        val batch = EpubTextChars.progressionsOfElementIds(html, setOf("x")).getValue("x")
        val single = EpubTextChars.progressionOfElementId(html, "x")!!

        assertEquals(single, batch, 0.0001)
    }
}
