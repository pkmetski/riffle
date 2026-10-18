package com.riffle.feature.shellui

import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.feature.navigation.LibraryNav
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NowPlayingDedupeTest {

    private val itemA = LibraryItem(
        id = "item-a",
        libraryId = "lib-1",
        title = "Book A",
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        hasAudio = true,
    )

    private val itemB = LibraryItem(
        id = "item-b",
        libraryId = "lib-1",
        title = "Book B",
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        hasAudio = true,
    )

    @Test
    fun audiobookPlayerDuplicateIsDetected() {
        val stack = listOf<LibraryNav>(LibraryNav.Items, LibraryNav.AudiobookPlayer(itemA))
        val incoming = LibraryNav.AudiobookPlayer(itemA)
        assertTrue(isDuplicateNowPlayingNav(stack, incoming))
    }

    @Test
    fun audiobookPlayerDifferentItemIsNotDuplicate() {
        val stack = listOf<LibraryNav>(LibraryNav.Items, LibraryNav.AudiobookPlayer(itemA))
        val incoming = LibraryNav.AudiobookPlayer(itemB)
        assertFalse(isDuplicateNowPlayingNav(stack, incoming))
    }

    @Test
    fun readerDuplicateIsDetected() {
        val stack = listOf<LibraryNav>(LibraryNav.Items, LibraryNav.Reader(itemA))
        val incoming = LibraryNav.Reader(itemA)
        assertTrue(isDuplicateNowPlayingNav(stack, incoming))
    }

    @Test
    fun readerDifferentItemIsNotDuplicate() {
        val stack = listOf<LibraryNav>(LibraryNav.Items, LibraryNav.Reader(itemA))
        val incoming = LibraryNav.Reader(itemB)
        assertFalse(isDuplicateNowPlayingNav(stack, incoming))
    }

    @Test
    fun audiobookNotDuplicateWhenStackOnItems() {
        val stack = listOf<LibraryNav>(LibraryNav.Items)
        val incoming = LibraryNav.AudiobookPlayer(itemA)
        assertFalse(isDuplicateNowPlayingNav(stack, incoming))
    }

    @Test
    fun audiobookNotDuplicateWhenStackTopIsReader() {
        val stack = listOf<LibraryNav>(LibraryNav.Items, LibraryNav.Reader(itemA))
        val incoming = LibraryNav.AudiobookPlayer(itemA)
        assertFalse(isDuplicateNowPlayingNav(stack, incoming))
    }
}
