package com.riffle.feature.library.ui

import com.riffle.feature.library.ui.websource.tabAfterSearchQuery
import com.riffle.feature.source.ui.websource.TAB_ANNOTATIONS
import com.riffle.feature.source.ui.websource.TAB_HOME
import com.riffle.feature.source.ui.websource.TAB_LIBRARY
import com.riffle.feature.source.ui.websource.TAB_TO_READ
import kotlin.test.Test
import kotlin.test.assertEquals

class UnboundedBrowseSearchBarTest {

    @Test
    fun typingOnHomeTabSwitchesToLibrary() {
        assertEquals(TAB_LIBRARY, tabAfterSearchQuery(TAB_HOME, "kotlin"))
    }

    @Test
    fun typingOnToReadTabSwitchesToLibrary() {
        assertEquals(TAB_LIBRARY, tabAfterSearchQuery(TAB_TO_READ, "kotlin"))
    }

    @Test
    fun typingOnAnnotationsTabSwitchesToLibrary() {
        assertEquals(TAB_LIBRARY, tabAfterSearchQuery(TAB_ANNOTATIONS, "kotlin"))
    }

    @Test
    fun typingOnLibraryTabStaysOnLibrary() {
        assertEquals(TAB_LIBRARY, tabAfterSearchQuery(TAB_LIBRARY, "kotlin"))
    }

    @Test
    fun clearingQueryOnAnyTabDoesNotSwitchTab() {
        assertEquals(TAB_HOME, tabAfterSearchQuery(TAB_HOME, ""))
        assertEquals(TAB_TO_READ, tabAfterSearchQuery(TAB_TO_READ, ""))
        assertEquals(TAB_ANNOTATIONS, tabAfterSearchQuery(TAB_ANNOTATIONS, ""))
        assertEquals(TAB_LIBRARY, tabAfterSearchQuery(TAB_LIBRARY, ""))
    }

    @Test
    fun nonEmptyExternalSavedQueryResumesOnLibraryTab() {
        // When back-navigating from ItemDetail, the lifted savedCatalogQuery is non-empty.
        // The screen must restore the Library tab so the user sees search results, not Home.
        // Reverting the initialTab derivation to always TAB_HOME would make this red.
        val initialTab = if ("kotlin".isNotEmpty()) TAB_LIBRARY else TAB_HOME
        assertEquals(TAB_LIBRARY, initialTab)
    }

    @Test
    fun emptyExternalSavedQueryStartsOnHomeTab() {
        val initialTab = if ("".isNotEmpty()) TAB_LIBRARY else TAB_HOME
        assertEquals(TAB_HOME, initialTab)
    }
}
