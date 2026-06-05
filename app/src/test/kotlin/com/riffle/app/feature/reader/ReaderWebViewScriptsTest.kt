package com.riffle.app.feature.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderWebViewScriptsTest {

    // scrollToColumnJs floors scrollLeft to the column the element starts in, so a tapped figure
    // cross-reference lands flush on the grid rather than a gutter inside its column.
    @Test
    fun `scrollToColumnJs floors to the element's column and quotes the id`() {
        val js = scrollToColumnJs("c04-fig-0001")
        assertTrue("looks up the target by id", js.contains("getElementById(\"c04-fig-0001\")"))
        assertTrue("measures the element position", js.contains("getBoundingClientRect"))
        assertTrue("reads the live column pitch", js.contains("window.innerWidth"))
        assertTrue("FLOORS to the column boundary", js.contains("Math.floor(abs/iw)*iw"))
    }

    // Dotted ids (O'Reilly-style "ftn.ch01fn01") must survive verbatim — JSONObject.quote keeps them
    // a plain string literal so getElementById (not a CSS selector) matches them.
    @Test
    fun `scrollToColumnJs preserves dotted ids verbatim`() {
        assertTrue(scrollToColumnJs("ftn.ch01fn01").contains("getElementById(\"ftn.ch01fn01\")"))
    }

    // SNAP_NEAREST_COLUMN_JS rounds the current scroll position to the nearest column — for after a
    // go()-based TOC/search jump. It must NOT depend on a specific element.
    @Test
    fun `SNAP_NEAREST_COLUMN_JS rounds the current scroll to the nearest column`() {
        val js = SNAP_NEAREST_COLUMN_JS
        assertTrue("rounds to the nearest column", js.contains("Math.round(se.scrollLeft/iw)*iw"))
        assertTrue("reads the live column pitch", js.contains("window.innerWidth"))
        assertTrue("operates on the scroll container", js.contains("document.scrollingElement"))
        assertFalse("must not target a specific element", js.contains("getElementById"))
    }
}
