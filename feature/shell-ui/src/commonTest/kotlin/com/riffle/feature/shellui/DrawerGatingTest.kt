package com.riffle.feature.shellui

import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.feature.navigation.LibraryNav
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DrawerGatingTest {

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
    fun drawerGesturesEnabledOnItemsScreen() {
        assertFalse(isReaderActiveForStack(listOf(LibraryNav.Items)))
    }

    @Test
    fun drawerGesturesDisabledWhenEpubReaderOnTop() {
        assertTrue(isReaderActiveForStack(listOf(LibraryNav.Items, LibraryNav.Reader(item))))
    }

    @Test
    fun drawerGesturesDisabledWhenPdfReaderOnTop() {
        assertTrue(isReaderActiveForStack(listOf(LibraryNav.Items, LibraryNav.PdfReader(item))))
    }

    @Test
    fun drawerGesturesDisabledWhenCbzReaderOnTop() {
        assertTrue(isReaderActiveForStack(listOf(LibraryNav.Items, LibraryNav.CbzReader(item))))
    }

    @Test
    fun drawerGesturesDisabledWhenElidedReaderOnTop() {
        assertTrue(isReaderActiveForStack(listOf(LibraryNav.Items, LibraryNav.ElidedReader("item-1", null))))
    }

    @Test
    fun drawerGesturesEnabledWhenAudiobookPlayerOnTop() {
        assertFalse(isReaderActiveForStack(listOf(LibraryNav.Items, LibraryNav.AudiobookPlayer(item))))
    }

    @Test
    fun drawerGesturesEnabledWhenDetailOnTop() {
        assertFalse(isReaderActiveForStack(listOf(LibraryNav.Items, LibraryNav.ItemDetail("item-1", null))))
    }

    @Test
    fun drawerGesturesEnabledOnEmptyStack() {
        assertFalse(isReaderActiveForStack(emptyList()))
    }
}
