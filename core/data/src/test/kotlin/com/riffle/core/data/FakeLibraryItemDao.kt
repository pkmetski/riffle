package com.riffle.core.data

import com.riffle.core.database.LastOpenedAtRow
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.MatchableItemRow
import com.riffle.core.database.ReadingProgressRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

internal class FakeLibraryItemDao : LibraryItemDao {
    val upserted = mutableListOf<LibraryItemEntity>()
    private val roomData = mutableMapOf<String, MutableStateFlow<List<LibraryItemEntity>>>()

    fun itemsFor(libraryId: String): List<LibraryItemEntity> =
        roomData[libraryId]?.value ?: emptyList()

    override fun observeByLibraryId(libraryId: String): Flow<List<LibraryItemEntity>> =
        roomData.getOrPut(libraryId) { MutableStateFlow(emptyList()) }

    override fun observeUngroupedByLibraryId(libraryId: String): Flow<List<LibraryItemEntity>> =
        roomData.getOrPut(libraryId) { MutableStateFlow(emptyList()) }

    override fun observeInProgress(libraryId: String): Flow<List<LibraryItemEntity>> =
        MutableStateFlow(roomData[libraryId]?.value?.filter { it.readingProgress > 0f && it.readingProgress < 1f } ?: emptyList())

    override fun observeFinished(libraryId: String): Flow<List<LibraryItemEntity>> =
        MutableStateFlow(roomData[libraryId]?.value?.filter { it.readingProgress == 1f } ?: emptyList())

    override fun observeRecentlyAdded(libraryId: String): Flow<List<LibraryItemEntity>> =
        MutableStateFlow(roomData[libraryId]?.value?.sortedByDescending { it.addedAt } ?: emptyList())

    override fun observeAllBooks(libraryId: String): Flow<List<LibraryItemEntity>> =
        roomData.getOrPut(libraryId) { MutableStateFlow(emptyList()) }

    override suspend fun upsertAll(items: List<LibraryItemEntity>) {
        upserted.addAll(items)
        items.groupBy { it.libraryId }.forEach { (libraryId, newItems) ->
            val flow = roomData.getOrPut(libraryId) { MutableStateFlow(emptyList()) }
            val newIds = newItems.map { it.id }.toSet()
            flow.value = flow.value.filterNot { it.id in newIds } + newItems
        }
    }

    // replaceAllForLibrary is intentionally inherited (deleteByLibraryId + upsertAll @Transaction default).

    override suspend fun getById(itemId: String): LibraryItemEntity? =
        roomData.values.flatMap { it.value }.firstOrNull { it.id == itemId }

    override suspend fun deleteByLibraryId(libraryId: String) {
        roomData[libraryId]?.value = emptyList()
    }

    override suspend fun updateLastOpenedAt(itemId: String, timestamp: Long) {}

    override suspend fun getLastOpenedAtMap(libraryId: String): List<LastOpenedAtRow> =
        roomData[libraryId]?.value
            ?.filter { it.lastOpenedAt != null }
            ?.map { LastOpenedAtRow(it.id, it.lastOpenedAt!!) }
            ?: emptyList()

    override suspend fun getReadingProgressMap(libraryId: String): List<ReadingProgressRow> =
        roomData[libraryId]?.value
            ?.filter { it.readingProgress > 0f }
            ?.map { ReadingProgressRow(it.id, it.readingProgress) }
            ?: emptyList()

    override suspend fun updateReadingProgress(itemId: String, progress: Float) {}

    override suspend fun updateReadaloudMetadata(
        itemId: String,
        author: String?,
        description: String?,
        publishedYear: String?,
        publisher: String?,
        genres: String,
    ) {
        val entity = roomData.values.flatMap { it.value }.firstOrNull { it.id == itemId } ?: return
        val updated = entity.copy(
            author = author ?: entity.author,
            description = description,
            publishedYear = publishedYear,
            publisher = publisher,
            genres = genres,
        )
        roomData[entity.libraryId]?.let { flow ->
            flow.value = flow.value.map { if (it.id == itemId) updated else it }
        }
        upserted += updated
    }

    override suspend fun listMatchableByServerType(serverType: String): List<MatchableItemRow> = emptyList()
}
