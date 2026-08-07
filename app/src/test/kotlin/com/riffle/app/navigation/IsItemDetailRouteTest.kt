package com.riffle.app.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsItemDetailRouteTest {

    @Test
    fun `library item detail route is recognized`() {
        assertTrue(isItemDetailRoute("library_item_detail/item-id-123"))
    }

    @Test
    fun `null route is not an item detail route`() {
        assertFalse(isItemDetailRoute(null))
    }

    @Test
    fun `home route is not an item detail route`() {
        assertFalse(isItemDetailRoute("home"))
    }

    @Test
    fun `library items route is not an item detail route`() {
        assertFalse(isItemDetailRoute("library_items/lib-1/My%20Library"))
    }

    @Test
    fun `epub reader route is not an item detail route`() {
        assertFalse(isItemDetailRoute("epub_reader/item-id"))
    }
}
