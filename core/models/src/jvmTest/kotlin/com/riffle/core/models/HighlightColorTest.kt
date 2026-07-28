package com.riffle.core.models

import org.junit.Assert.assertEquals
import org.junit.Test
import com.riffle.core.models.HighlightColor

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
        assertEquals("red", HighlightColor.RED.token)
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
    fun `legacy pink token falls back to default (no alias, no migration)`() {
        // The fourth swatch used to be "pink" (rose-400) — locally-stored rows written by that
        // build, and inbound sync from clients still on it, both take the standard unknown-name
        // fallback to YELLOW. Same policy as the readaloud pref on legacy "PINK"/"PURPLE".
        assertEquals(HighlightColor.DEFAULT, HighlightColor.fromToken("pink"))
    }

    @Test
    fun `argb bakes in the single palette alpha (0x80) plus the hue`() {
        // `argb` is the final rendered ARGB — used verbatim by settings swatches and reader
        // decorations. Any change here must be a deliberate palette redesign; a drift to full
        // opacity would paint solid bars over body text.
        assertEquals(0x80FBBF24.toInt(), HighlightColor.YELLOW.argb)
        assertEquals(0x8034D399.toInt(), HighlightColor.GREEN.argb)
        assertEquals(0x8038BDF8.toInt(), HighlightColor.BLUE.argb)
        assertEquals(0x80EF4444.toInt(), HighlightColor.RED.argb)
    }
}
