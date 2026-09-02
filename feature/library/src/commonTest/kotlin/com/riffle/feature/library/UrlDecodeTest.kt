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

    @Test
    fun decodesMultiByteUtf8Sequence() {
        // 'é' = UTF-8 bytes 0xC3 0xA9; URLEncoder produces "%C3%A9"
        assertEquals("Café Stories", "Caf%C3%A9+Stories".urlDecode())
    }

    @Test
    fun decodesThreeByteUtf8Sequence() {
        // '€' = UTF-8 bytes 0xE2 0x82 0xAC; URLEncoder produces "%E2%82%AC"
        assertEquals("12€", "12%E2%82%AC".urlDecode())
    }
}
