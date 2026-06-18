package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightColorTest {

    @Test
    fun `every colour round-trips through its token`() {
        HighlightColor.entries.forEach { color ->
            assertEquals(color, HighlightColor.fromToken(color.token))
        }
    }

    @Test
    fun `tokens are the lowercase enum names`() {
        assertEquals("yellow", HighlightColor.YELLOW.token)
        assertEquals("green", HighlightColor.GREEN.token)
        assertEquals("blue", HighlightColor.BLUE.token)
        assertEquals("pink", HighlightColor.PINK.token)
    }

    @Test
    fun `default is yellow`() {
        assertEquals(HighlightColor.YELLOW, HighlightColor.DEFAULT)
    }

    @Test
    fun `unknown or null token falls back to the default`() {
        assertEquals(HighlightColor.DEFAULT, HighlightColor.fromToken("purple"))
        assertEquals(HighlightColor.DEFAULT, HighlightColor.fromToken(null))
        assertEquals(HighlightColor.DEFAULT, HighlightColor.fromToken(""))
    }

    @Test
    fun `hues match the shared readaloud palette values`() {
        assertEquals(0xFFFBBF24.toInt(), HighlightColor.YELLOW.argb)
        assertEquals(0xFF34D399.toInt(), HighlightColor.GREEN.argb)
        assertEquals(0xFF38BDF8.toInt(), HighlightColor.BLUE.argb)
        assertEquals(0xFFFB7185.toInt(), HighlightColor.PINK.argb)
    }
}
