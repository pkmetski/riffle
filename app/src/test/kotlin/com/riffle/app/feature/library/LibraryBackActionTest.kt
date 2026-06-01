package com.riffle.app.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryBackActionTest {

    @Test
    fun `non-empty search query yields ClearSearch regardless of tab`() {
        assertEquals(LibraryBackAction.ClearSearch, libraryBackAction(searchQuery = "dune", selectedTab = 0))
        assertEquals(LibraryBackAction.ClearSearch, libraryBackAction(searchQuery = "dune", selectedTab = 3))
    }

    @Test
    fun `empty query with non-Home tab yields ResetToHomeTab`() {
        assertEquals(LibraryBackAction.ResetToHomeTab, libraryBackAction(searchQuery = "", selectedTab = 1))
        assertEquals(LibraryBackAction.ResetToHomeTab, libraryBackAction(searchQuery = "", selectedTab = 4))
    }

    @Test
    fun `empty query with Home tab yields Exit`() {
        assertEquals(LibraryBackAction.Exit, libraryBackAction(searchQuery = "", selectedTab = 0))
    }
}
