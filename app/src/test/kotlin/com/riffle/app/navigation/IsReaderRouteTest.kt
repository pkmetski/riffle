package com.riffle.app.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsReaderRouteTest {

    @Test
    fun `epub reader route is recognized`() {
        assertTrue(isReaderRoute("epub_reader/some-item-id"))
    }

    @Test
    fun `pdf reader route is recognized`() {
        assertTrue(isReaderRoute("pdf_reader/some-item-id"))
    }

    @Test
    fun `null route is not a reader route`() {
        assertFalse(isReaderRoute(null))
    }

    @Test
    fun `home route is not a reader route`() {
        assertFalse(isReaderRoute("home"))
    }

    @Test
    fun `library items route is not a reader route`() {
        assertFalse(isReaderRoute("library_items/lib-1/My%20Library"))
    }

    @Test
    fun `library item detail route is not a reader route`() {
        assertFalse(isReaderRoute("library_item_detail/item-id"))
    }
}
