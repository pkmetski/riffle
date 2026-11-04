package com.riffle.feature.library.ui

import com.riffle.feature.library.ui.websource.unboundedBrowseHeaderQueryFor
import com.riffle.feature.source.ui.websource.TAB_ANNOTATIONS
import com.riffle.feature.source.ui.websource.TAB_HOME
import com.riffle.feature.source.ui.websource.TAB_LIBRARY
import com.riffle.feature.source.ui.websource.TAB_TO_READ
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for the missing search bar on non-Library tabs in [UnboundedBrowseScreen].
 *
 * Before the fix the topBar only showed SourceBrowseHeader when selectedLocalTab == TAB_LIBRARY;
 * the other tabs showed a plain TopAppBar with no search field. These tests pin that every tab
 * uses the correct query source: Library tab → catalog query, all others → library search query.
 */
class UnboundedBrowseSearchBarTest {

    @Test
    fun homeTabUsesLibrarySearchQuery() {
        assertEquals(
            "library",
            unboundedBrowseHeaderQueryFor(TAB_HOME, catalogQuery = "catalog", libraryQuery = "library"),
        )
    }

    @Test
    fun toReadTabUsesLibrarySearchQuery() {
        assertEquals(
            "library",
            unboundedBrowseHeaderQueryFor(TAB_TO_READ, catalogQuery = "catalog", libraryQuery = "library"),
        )
    }

    @Test
    fun annotationsTabUsesLibrarySearchQuery() {
        assertEquals(
            "library",
            unboundedBrowseHeaderQueryFor(TAB_ANNOTATIONS, catalogQuery = "catalog", libraryQuery = "library"),
        )
    }

    @Test
    fun libraryTabUsesCatalogQuery() {
        assertEquals(
            "catalog",
            unboundedBrowseHeaderQueryFor(TAB_LIBRARY, catalogQuery = "catalog", libraryQuery = "library"),
        )
    }
}
