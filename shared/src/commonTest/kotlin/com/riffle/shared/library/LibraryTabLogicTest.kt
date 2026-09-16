package com.riffle.shared.library

import com.riffle.feature.library.LibraryTabVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryTabLogicTest {

    private val allVisible = LibraryTabVisibility(
        toRead = true,
        series = true,
        collections = true,
        annotations = true,
        playlists = true,
    )

    private val noneVisible = LibraryTabVisibility(
        toRead = false,
        series = false,
        collections = false,
        annotations = false,
        playlists = false,
    )

    // --- tabIndexFor* ---

    @Test fun tabIndexForAnnotationsIs2() {
        assertEquals(2, tabIndexForAnnotations())
    }

    @Test fun tabIndexForPlaylistsIs6() {
        assertEquals(6, tabIndexForPlaylists())
    }

    // --- isTabVisible ---

    @Test fun homeTabAlwaysVisible() {
        assertTrue(isTabVisible(0, noneVisible))
    }

    @Test fun allBooksTabAlwaysVisible() {
        assertTrue(isTabVisible(5, noneVisible))
    }

    @Test fun toReadTabVisibleWhenFlagTrue() {
        assertTrue(isTabVisible(1, allVisible))
    }

    @Test fun toReadTabHiddenWhenFlagFalse() {
        assertFalse(isTabVisible(1, noneVisible))
    }

    @Test fun annotationsTabVisibleWhenFlagTrue() {
        assertTrue(isTabVisible(tabIndexForAnnotations(), allVisible))
    }

    @Test fun annotationsTabHiddenWhenFlagFalse() {
        assertFalse(isTabVisible(tabIndexForAnnotations(), noneVisible))
    }

    @Test fun seriesTabVisibleWhenFlagTrue() {
        assertTrue(isTabVisible(3, allVisible))
    }

    @Test fun seriesTabHiddenWhenFlagFalse() {
        assertFalse(isTabVisible(3, noneVisible))
    }

    @Test fun collectionsTabVisibleWhenFlagTrue() {
        assertTrue(isTabVisible(4, allVisible))
    }

    @Test fun collectionsTabHiddenWhenFlagFalse() {
        assertFalse(isTabVisible(4, noneVisible))
    }

    @Test fun playlistsTabVisibleWhenFlagTrue() {
        assertTrue(isTabVisible(tabIndexForPlaylists(), allVisible))
    }

    @Test fun playlistsTabHiddenWhenFlagFalse() {
        assertFalse(isTabVisible(tabIndexForPlaylists(), noneVisible))
    }

    // --- shouldClampSelectedTab ---

    @Test fun doesNotClampWhileSearching() {
        // selectedTab=3 (series) hidden, but searchQuery non-empty → no clamp
        assertFalse(shouldClampSelectedTab("kotlin", noneVisible, 3))
    }

    @Test fun doesNotClampWhenVisibilityNull() {
        assertFalse(shouldClampSelectedTab("", null, 3))
    }

    @Test fun clampsWhenTabBecomesHidden() {
        assertTrue(shouldClampSelectedTab("", noneVisible, 1)) // toRead=false
    }

    @Test fun doesNotClampForHomeTab() {
        assertFalse(shouldClampSelectedTab("", noneVisible, 0))
    }

    @Test fun doesNotClampForAllBooksTab() {
        assertFalse(shouldClampSelectedTab("", noneVisible, 5))
    }

    @Test fun doesNotClampWhenTabStillVisible() {
        assertFalse(shouldClampSelectedTab("", allVisible, 1))
    }
}
