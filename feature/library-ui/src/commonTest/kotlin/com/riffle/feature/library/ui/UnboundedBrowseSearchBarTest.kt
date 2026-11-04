package com.riffle.feature.library.ui

import com.riffle.feature.library.ui.websource.unboundedBrowseHeaderQueryFor
import com.riffle.feature.source.ui.websource.TAB_ANNOTATIONS
import com.riffle.feature.source.ui.websource.TAB_HOME
import com.riffle.feature.source.ui.websource.TAB_LIBRARY
import com.riffle.feature.source.ui.websource.TAB_TO_READ
import kotlin.test.Test
import kotlin.test.assertEquals

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
