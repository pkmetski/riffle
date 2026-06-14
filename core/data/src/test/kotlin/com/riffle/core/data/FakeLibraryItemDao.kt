package com.riffle.core.data

import com.riffle.core.database.LastOpenedAtRow
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LibraryItemMetadata
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

    // replaceAllForLibrary is intentionally inherited (uses the three methods below).

    override suspend fun insertOrIgnore(items: List<LibraryItemEntity>) {
        items.groupBy { it.libraryId }.forEach { (libraryId, newItems) ->
            val flow = roomData.getOrPut(libraryId) { MutableStateFlow(emptyList()) }
            val existingIds = flow.value.map { it.id }.toSet()
            val toInsert = newItems.filter { it.id !in existingIds }
            if (toInsert.isNotEmpty()) {
                upserted.addAll(toInsert)
                flow.value = flow.value + toInsert
            }
        }
    }

    override suspend fun updateMetadata(metadata: LibraryItemMetadata) {
        val flow = roomData[metadata.libraryId] ?: return
        flow.value = flow.value.map { existing ->
            if (existing.serverId == metadata.serverId && existing.id == metadata.id) {
                existing.copy(
                    libraryId = metadata.libraryId,
                    title = metadata.title,
                    author = metadata.author,
                    coverUrl = metadata.coverUrl,
                    ebookFileIno = metadata.ebookFileIno,
                    ebookFormat = metadata.ebookFormat,
                    hasAudio = metadata.hasAudio,
                    audioDurationSec = metadata.audioDurationSec,
                    description = metadata.description,
                    seriesName = metadata.seriesName,
                    publishedYear = metadata.publishedYear,
                    genres = metadata.genres,
                    publisher = metadata.publisher,
                    language = metadata.language,
                    lastOpenedAt = metadata.lastOpenedAt,
                    addedAt = metadata.addedAt,
                    isbn = metadata.isbn,
                    asin = metadata.asin,
                )
            } else existing
        }
    }

    override suspend fun deleteRemovedFromLibrary(serverId: String, libraryId: String, serverItemIds: List<String>) {
        val serverIdSet = serverItemIds.toSet()
        roomData[libraryId]?.value = roomData[libraryId]?.value
            ?.filter { it.serverId != serverId || it.id in serverIdSet }
            ?: emptyList()
    }

    override suspend fun getById(serverId: String, itemId: String): LibraryItemEntity? =
        roomData.values.flatMap { it.value }.firstOrNull { it.serverId == serverId && it.id == itemId }

    override fun observeById(serverId: String, itemId: String): Flow<LibraryItemEntity?> =
        MutableStateFlow(roomData.values.flatMap { it.value }.firstOrNull { it.serverId == serverId && it.id == itemId })

    override suspend fun findServerIdForItem(itemId: String): String? =
        roomData.values.flatMap { it.value }.firstOrNull { it.id == itemId }?.serverId

    override suspend fun deleteByLibraryId(libraryId: String) {
        roomData[libraryId]?.value = emptyList()
    }

    override suspend fun updateLastOpenedAt(serverId: String, itemId: String, timestamp: Long) {}

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

    override suspend fun updateReadingProgress(serverId: String, itemId: String, progress: Float) {}

    override suspend fun listMatchableByServerType(serverType: String): List<MatchableItemRow> = emptyList()
}
