package com.riffle.app.feature.source.websource

import com.riffle.core.data.websource.PositionTombstoneWriter
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
    fun `removeFromLibrary deletes item and marks position rows as tombstoned`() = runTest {
        val mutator = RecordingLibraryMutator()
        val tombstoned = mutableListOf<Pair<String, String>>()
        val writer = PositionTombstoneWriter { sourceId, itemId -> tombstoned += sourceId to itemId }

        removeFromLibrary(
            sourceId = "src-1",
            itemId = "item-42",
            libraryMutator = mutator,
            hideItem = writer::markDeleted,
        )

        assertTrue("deleteItem was not called", mutator.deleted.contains("src-1" to "item-42"))
        assertTrue("position tombstone was not written", tombstoned.contains("src-1" to "item-42"))
    }

    @Test
    fun `removeFromLibrary tombstones position row before deleting library row`() = runTest {
        // A concurrent sweep must never see the library row gone while the position row is
        // still live — that window would cause it to re-insert the item. Verify that hideItem
        // fires first by recording the order of operations.
        val order = mutableListOf<String>()
        val mutator = object : RecordingLibraryMutator() {
            override suspend fun deleteItem(sourceId: String, itemId: String) {
                order += "delete"
                super.deleteItem(sourceId, itemId)
            }
        }
        val writer = PositionTombstoneWriter { _, _ -> order += "tombstone" }

        removeFromLibrary("src-1", "item-42", mutator, writer::markDeleted)

        assertEquals(listOf("tombstone", "delete"), order)
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

private open class RecordingLibraryMutator : LibraryMutator {
    val deleted = mutableListOf<Pair<String, String>>()
    override suspend fun markItemOpened(itemId: String) = Unit
    override suspend fun updateReadingProgress(itemId: String, progress: Float) = Unit
    override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) = Unit
    override suspend fun deleteItem(sourceId: String, itemId: String) { deleted += sourceId to itemId }
}
