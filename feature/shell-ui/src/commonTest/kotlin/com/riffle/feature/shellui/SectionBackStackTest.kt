package com.riffle.feature.shellui

import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.feature.navigation.LibraryNav
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SectionBackStackTest {

    private val item = LibraryItem(
        id = "item-1",
        libraryId = "lib-1",
        title = "Test",
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
    )

    @Test
    fun libraryStackStartsWithItems() {
        val stack = listOf(LibraryNav.Items)
        assertEquals(LibraryNav.Items, stack.last())
    }

    @Test
    fun pushAddsToStack() {
        var stack = listOf<LibraryNav>(LibraryNav.Items)
        stack = stack + LibraryNav.ItemDetail("item-1", null)
        assertEquals(2, stack.size)
        assertEquals(LibraryNav.ItemDetail("item-1", null), stack.last())
    }

    @Test
    fun popRemovesTopAndFallsBackToItems() {
        var stack = listOf<LibraryNav>(LibraryNav.Items, LibraryNav.ItemDetail("item-1", null))
        stack = stack.dropLast(1).ifEmpty { listOf(LibraryNav.Items) }
        assertEquals(1, stack.size)
        assertEquals(LibraryNav.Items, stack.last())
    }

    @Test
    fun popOnSingleItemFallsBackToItems() {
        val stack = listOf(LibraryNav.Items)
        val result = stack.dropLast(1).ifEmpty { listOf(LibraryNav.Items) }
        assertEquals(listOf(LibraryNav.Items), result)
    }

    @Test
    fun backHandlerEnabledWhenStackHasMoreThanOneEntry() {
        val stack = listOf<LibraryNav>(LibraryNav.Items, LibraryNav.ItemDetail("item-1", null))
        assertTrue(stack.size > 1)
    }

    @Test
    fun backHandlerDisabledOnRootItems() {
        val stack = listOf<LibraryNav>(LibraryNav.Items)
        assertFalse(stack.size > 1)
    }

    @Test
    fun riffleStackStartsEmpty() {
        val stack = listOf<LibraryNav>()
        assertTrue(stack.isEmpty())
    }

    @Test
    fun rifflePopDoesNotUnderflow() {
        var stack = listOf<LibraryNav>(LibraryNav.ItemDetail("item-1", null))
        stack = stack.dropLast(1)
        assertTrue(stack.isEmpty())
    }

    @Test
    fun readerNavigationOnTopOfDetailIsDetected() {
        val stack = listOf<LibraryNav>(
            LibraryNav.Items,
            LibraryNav.ItemDetail("item-1", null),
            LibraryNav.Reader(item),
        )
        assertTrue(isReaderActiveForStack(stack))
    }

    @Test
    fun detailScreenIsNotReaderActive() {
        val stack = listOf<LibraryNav>(
            LibraryNav.Items,
            LibraryNav.ItemDetail("item-1", null),
        )
        assertFalse(isReaderActiveForStack(stack))
    }
}
