package com.riffle.shared.reader

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonStringUtilsTest {

    @Test
    fun plainStringUnchanged() {
        assertEquals("hello world", "hello world".jsonEscaped())
    }

    @Test
    fun backslashEscaped() {
        assertEquals("a\\\\b", "a\\b".jsonEscaped())
    }

    @Test
    fun doubleQuoteEscaped() {
        assertEquals("say \\\"hi\\\"", """say "hi"""".jsonEscaped())
    }

    @Test
    fun newlineEscaped() {
        assertEquals("line1\\nline2", "line1\nline2".jsonEscaped())
    }

    @Test
    fun carriageReturnEscaped() {
        assertEquals("line1\\rline2", "line1\rline2".jsonEscaped())
    }

    @Test
    fun allSpecialCharsCombined() {
        // A title that mixes all four escapable characters — representative of a real book title
        // coming through the TOC/search bridge.
        val input = "Title\\ with \"quotes\"\r\nand newline"
        val expected = "Title\\\\ with \\\"quotes\\\"\\r\\nand newline"
        assertEquals(expected, input.jsonEscaped())
    }

    @Test
    fun emptyStringUnchanged() {
        assertEquals("", "".jsonEscaped())
    }

    @Test
    fun urlLikeHrefUnchanged() {
        // Typical EPUB hrefs have no special chars — escaping must not corrupt them.
        val href = "Text/chapter-01.xhtml#para1"
        assertEquals(href, href.jsonEscaped())
    }
}
