package com.riffle.app.feature.source.websource

import com.riffle.core.data.websource.PositionTombstoneWriter
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.LibraryMutator
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    fun `to-read items filters unavailable items when offline`() = runTest {
        val available = item("available")
        val unavailable = item("unavailable")
        val connectivity = FakeConnectivityObserver(online = false)
        val offlineAvailability = FakeItemOfflineAvailability(setOf("available"))

        val result = webSourceToReadItemsOfflineFiltered(
            toReadItemIds = flowOf(setOf("available", "unavailable")),
            allBooks = flowOf(listOf(available, unavailable)),
            connectivity = connectivity,
            offlineAvailability = offlineAvailability,
        ).first()

        assertEquals(listOf(available), result)
    }

    @Test
    fun `to-read items shows all items when online`() = runTest {
        val available = item("available")
        val unavailable = item("unavailable")
        val connectivity = FakeConnectivityObserver(online = true)
        val offlineAvailability = FakeItemOfflineAvailability(emptySet())

        val result = webSourceToReadItemsOfflineFiltered(
            toReadItemIds = flowOf(setOf("available", "unavailable")),
            allBooks = flowOf(listOf(available, unavailable)),
            connectivity = connectivity,
            offlineAvailability = offlineAvailability,
        ).first()

        assertEquals(listOf(available, unavailable), result)
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

private fun webSourceToReadItemsOfflineFiltered(
    toReadItemIds: Flow<Set<String>>,
    allBooks: Flow<List<LibraryItem>>,
    connectivity: ConnectivityObserver,
    offlineAvailability: LibraryItemOfflineAvailability,
) = combine(
    webSourceToReadItems(toReadItemIds, allBooks),
    connectivity.isOnline.map { !it },
) { items, offline ->
    if (offline) items.filter { offlineAvailability.isAvailableOffline(it) } else items
}

private class FakeConnectivityObserver(online: Boolean) : ConnectivityObserver {
    override val isOnline: StateFlow<Boolean> = MutableStateFlow(online)
}

private class FakeItemOfflineAvailability(private val availableIds: Set<String>) : LibraryItemOfflineAvailability {
    override fun isAvailableOffline(item: LibraryItem): Boolean = item.id in availableIds
}

private open class RecordingLibraryMutator : LibraryMutator {
    val deleted = mutableListOf<Pair<String, String>>()
    override suspend fun markItemOpened(itemId: String) = Unit
    override suspend fun currentReadingProgress(itemId: String): Float? = null
    override suspend fun currentReadingProgress(sourceId: String, itemId: String): Float? = null
    override suspend fun updateReadingProgress(itemId: String, progress: Float) = Unit
    override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) = Unit
    override suspend fun deleteItem(sourceId: String, itemId: String) { deleted += sourceId to itemId }
}
