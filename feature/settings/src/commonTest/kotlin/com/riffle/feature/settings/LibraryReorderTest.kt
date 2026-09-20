package com.riffle.feature.settings

import com.riffle.core.models.Library
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `idsWithSwap` moved from `app` into `feature:settings` so the iOS settings screen can drive
 * `SettingsViewModel.setLibraryOrder`, which had no caller at all before (#1071 §15.6). The
 * drawer and the settings list already read the stored order on both platforms.
 */
class LibraryReorderTest {

    @Test
    fun movingARowUpSwapsItWithItsPredecessor() {
        assertEquals(listOf("b", "a", "c"), items("a", "b", "c").idsWithSwap(1, 0))
    }

    @Test
    fun movingARowDownSwapsItWithItsSuccessor() {
        assertEquals(listOf("a", "c", "b"), items("a", "b", "c").idsWithSwap(1, 2))
    }

    @Test
    fun theFullOrderIsReturnedNotJustTheMovedPair() {
        assertEquals(listOf("a", "b", "d", "c", "e"), items("a", "b", "c", "d", "e").idsWithSwap(2, 3))
    }

    @Test
    fun movingPastEitherEndLeavesTheOrderUnchanged() {
        assertEquals(listOf("a", "b"), items("a", "b").idsWithSwap(0, -1))
        assertEquals(listOf("a", "b"), items("a", "b").idsWithSwap(1, 2))
    }

    private fun items(vararg ids: String): List<LibraryUiItem> = ids.map { id ->
        LibraryUiItem(
            library = Library(id = id, name = id.uppercase(), mediaType = "book", isUnsupported = false),
            isVisible = true,
            switchEnabled = true,
        )
    }
}
