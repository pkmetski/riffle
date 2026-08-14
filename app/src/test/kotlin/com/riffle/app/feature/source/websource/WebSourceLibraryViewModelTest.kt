package com.riffle.app.feature.source.websource

import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WebSourceLibraryViewModelTest {

    @Test
    fun `to-read items joins saved ids with acquired library rows`() = runTest {
        val first = item("first")
        val second = item("second")
        val third = item("third")

        val result = webSourceToReadItems(
            toReadItemIds = flowOf(setOf("third", "first", "missing")),
            allBooks = flowOf(listOf(first, second, third)),
        ).first()

        assertEquals(listOf(first, third), result)
    }

    private fun item(id: String) = LibraryItem(
        id = id,
        libraryId = "books",
        title = id,
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
    )
}
