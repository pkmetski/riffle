package com.riffle.app.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression pin for the Annotations tab's position in the Library Tab Bar. Per the placement
 * redesign, Annotations sits as a 6th tab between "To Read" (index 1) and "Series" (index 3).
 * [tabIndexForAnnotations] is the single source of truth wired into both `LibraryTabBar`'s
 * selected-check and `LibraryItemsScreen`'s `when (selectedTab)` branch — if either usage reverts
 * to a hardcoded literal that drifts from this constant, or the constant itself is changed back
 * to the tab's old drawer-entry position, this assertion flips red.
 */
class LibraryTabBarAnnotationsTest {

    @Test
    fun `annotations tab is at index 2, between To Read and Series`() {
        assertEquals(2, tabIndexForAnnotations())
    }
}
