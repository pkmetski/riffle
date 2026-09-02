package com.riffle.feature.library

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlDecodeTest {

    @Test
    fun decodesPercentEncodedAscii() {
        assertEquals("hello world", "hello%20world".urlDecode())
    }

    @Test
    fun decodesPlustSignAsSpace() {
        assertEquals("hello world", "hello+world".urlDecode())
    }

    @Test
    fun decodesAmpersandAndSlash() {
        assertEquals("sci-fi & fantasy", "sci-fi+%26+fantasy".urlDecode())
    }

    @Test
    fun plainStringPassesThrough() {
        assertEquals("plaintext", "plaintext".urlDecode())
    }

    @Test
    fun emptyStringReturnsEmpty() {
        assertEquals("", "".urlDecode())
    }
}
