package com.riffle.shared

import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NowPlayingNavDedupeTest {

    @Test
    fun audiobookPlayer_sameItemId_alreadyAtDestination() {
        val item = fakeItem("book1")
        val stack = listOf<LibraryNav>(LibraryNav.Items, LibraryNav.AudiobookPlayer(item))
        assertTrue(isAlreadyAtPlayerDestination(stack, LibraryNav.AudiobookPlayer(item)))
    }

    @Test
    fun audiobookPlayer_differentItemId_notAlreadyAtDestination() {
        val item1 = fakeItem("book1")
        val item2 = fakeItem("book2")
        val stack = listOf<LibraryNav>(LibraryNav.Items, LibraryNav.AudiobookPlayer(item1))
        assertFalse(isAlreadyAtPlayerDestination(stack, LibraryNav.AudiobookPlayer(item2)))
    }

    @Test
    fun audiobookPlayer_whenTopIsDetailScreen_notAlreadyAtDestination() {
        val item = fakeItem("book1")
        val stack = listOf<LibraryNav>(LibraryNav.Items, LibraryNav.ItemDetail("book1", null))
        assertFalse(isAlreadyAtPlayerDestination(stack, LibraryNav.AudiobookPlayer(item)))
    }

    @Test
    fun emptyStack_notAlreadyAtDestination() {
        val item = fakeItem("book1")
        assertFalse(isAlreadyAtPlayerDestination(emptyList(), LibraryNav.AudiobookPlayer(item)))
    }

    @Test
    fun reader_sameItemId_alreadyAtDestination() {
        val item = fakeItem("book1")
        val stack = listOf<LibraryNav>(LibraryNav.Items, LibraryNav.Reader(item))
        assertTrue(isAlreadyAtPlayerDestination(stack, LibraryNav.Reader(item)))
    }

    @Test
    fun reader_differentItemId_notAlreadyAtDestination() {
        val item1 = fakeItem("book1")
        val item2 = fakeItem("book2")
        val stack = listOf<LibraryNav>(LibraryNav.Items, LibraryNav.Reader(item1))
        assertFalse(isAlreadyAtPlayerDestination(stack, LibraryNav.Reader(item2)))
    }

    @Test
    fun audiobookPlayer_withPlaylistContext_deduplicatedSoContextPreserved() {
        // When the top already has playlist context and an incoming (context-stripped) request
        // arrives, isAlreadyAtPlayerDestination returns true — the caller skips the push and
        // the existing entry's playlist id / libraryId survive unchanged.
        val item = fakeItem("book1")
        val withPlaylist = LibraryNav.AudiobookPlayer(item, playlistId = "pl1", playlistLibraryId = "lib1")
        val stack = listOf<LibraryNav>(LibraryNav.Items, withPlaylist)
        val incoming = LibraryNav.AudiobookPlayer(item)
        assertTrue(isAlreadyAtPlayerDestination(stack, incoming))
    }

    @Test
    fun audiobookPlayer_readerAtTop_notDeduplicated() {
        val item = fakeItem("book1")
        val stack = listOf<LibraryNav>(LibraryNav.Items, LibraryNav.Reader(item))
        assertFalse(isAlreadyAtPlayerDestination(stack, LibraryNav.AudiobookPlayer(item)))
    }

    private fun fakeItem(id: String) = LibraryItem(
        id = id,
        libraryId = "lib",
        title = "Title",
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Unsupported,
        hasAudio = true,
    )
}
