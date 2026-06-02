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
}
