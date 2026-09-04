package com.riffle.app.feature.source.websource

import com.riffle.core.domain.LibraryMutator
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `removeFromLibrary deletes item and clears freshness stamp`() = runTest {
        val mutator = RecordingLibraryMutator()
        val cleared = mutableListOf<Pair<String, String>>()

        removeFromLibrary(
            sourceId = "src-1",
            itemId = "item-42",
            libraryMutator = mutator,
            clearFreshness = { sourceId, itemId -> cleared += sourceId to itemId },
        )

        assertTrue("deleteItem was not called", mutator.deleted.contains("src-1" to "item-42"))
        assertTrue("freshness was not cleared", cleared.contains("src-1" to "item-42"))
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

private class RecordingLibraryMutator : LibraryMutator {
    val deleted = mutableListOf<Pair<String, String>>()
    override suspend fun markItemOpened(itemId: String) = Unit
    override suspend fun updateReadingProgress(itemId: String, progress: Float) = Unit
    override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) = Unit
    override suspend fun deleteItem(sourceId: String, itemId: String) { deleted += sourceId to itemId }
}
