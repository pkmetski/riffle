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

    private fun scoped(sourceId: String, libraryId: String): List<LibraryItemEntity> =
        roomData[libraryId]?.value?.filter { it.sourceId == sourceId }.orEmpty()

    override fun observeByLibraryId(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> =
        MutableStateFlow(scoped(sourceId, libraryId))

    override fun observeUngroupedByLibraryId(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> =
        MutableStateFlow(scoped(sourceId, libraryId))

    override fun observeInProgress(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> =
        MutableStateFlow(scoped(sourceId, libraryId).filter { it.readingProgress > 0f && it.readingProgress < 1f })

    override fun observeFinished(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> =
        MutableStateFlow(scoped(sourceId, libraryId).filter { it.readingProgress == 1f })

    override fun observeRecentlyAdded(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> =
        MutableStateFlow(
            scoped(sourceId, libraryId)
                .filter { it.addedAt > 0L }
                .sortedByDescending { it.addedAt },
        )

    override fun observeAllBooks(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> =
        MutableStateFlow(scoped(sourceId, libraryId))

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
            if (existing.sourceId == metadata.sourceId && existing.id == metadata.id) {
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

    override suspend fun idsForLibrary(sourceId: String, libraryId: String): List<String> =
        roomData[libraryId]?.value?.filter { it.sourceId == sourceId }?.map { it.id }.orEmpty()

    override suspend fun deleteByIds(sourceId: String, itemIds: List<String>) {
        val idSet = itemIds.toHashSet()
        roomData.forEach { (_, flow) ->
            flow.value = flow.value.filterNot { it.sourceId == sourceId && it.id in idSet }
        }
    }

    override suspend fun getById(sourceId: String, itemId: String): LibraryItemEntity? =
        roomData.values.flatMap { it.value }.firstOrNull { it.sourceId == sourceId && it.id == itemId }

    override suspend fun listByLibraryId(sourceId: String, libraryId: String): List<LibraryItemEntity> =
        scoped(sourceId, libraryId)

    override suspend fun listByIds(sourceId: String, itemIds: List<String>): List<LibraryItemEntity> {
        val idSet = itemIds.toHashSet()
        return roomData.values.flatMap { it.value }
            .filter { it.sourceId == sourceId && it.id in idSet }
    }

    override fun observeById(sourceId: String, itemId: String): Flow<LibraryItemEntity?> =
        MutableStateFlow(roomData.values.flatMap { it.value }.firstOrNull { it.sourceId == sourceId && it.id == itemId })

    override suspend fun findSourceIdForItem(itemId: String): String? =
        roomData.values.flatMap { it.value }.firstOrNull { it.id == itemId }?.sourceId

    override suspend fun deleteByLibraryId(sourceId: String, libraryId: String) {
        val current = roomData[libraryId]?.value ?: return
        roomData[libraryId]!!.value = current.filterNot { it.sourceId == sourceId }
    }

    override suspend fun deleteById(sourceId: String, itemId: String) {
        roomData.forEach { (_, flow) ->
            flow.value = flow.value.filterNot { it.sourceId == sourceId && it.id == itemId }
        }
    }

    override suspend fun updateLastOpenedAt(sourceId: String, itemId: String, timestamp: Long) {
        roomData.forEach { (_, flow) ->
            flow.value = flow.value.map { existing ->
                if (existing.sourceId == sourceId && existing.id == itemId) {
                    existing.copy(
                        lastOpenedAt = timestamp,
                        addedAt = if (existing.addedAt == 0L) timestamp else existing.addedAt,
                    )
                } else existing
            }
        }
    }

    override suspend fun getLastOpenedAtMap(sourceId: String, libraryId: String): List<LastOpenedAtRow> =
        scoped(sourceId, libraryId)
            .filter { it.lastOpenedAt != null }
            .map { LastOpenedAtRow(it.id, it.lastOpenedAt!!) }

    override suspend fun getReadingProgressMap(sourceId: String, libraryId: String): List<ReadingProgressRow> =
        scoped(sourceId, libraryId)
            .filter { it.readingProgress > 0f }
            .map { ReadingProgressRow(it.id, it.readingProgress) }

    override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) {}
    override suspend fun updateLibraryId(sourceId: String, itemId: String, libraryId: String) {
        val entry = roomData.entries.firstOrNull { e ->
            e.value.value.any { it.sourceId == sourceId && it.id == itemId }
        } ?: return
        entry.value.value = entry.value.value.map {
            if (it.sourceId == sourceId && it.id == itemId) it.copy(libraryId = libraryId) else it
        }
    }

    override suspend fun updateFinishedAt(sourceId: String, itemId: String, finishedAt: Long?) {}

    override suspend fun listMatchableBySourceType(serverType: String): List<MatchableItemRow> = emptyList()

    override fun observeBySource(sourceId: String): Flow<List<LibraryItemEntity>> =
        MutableStateFlow(roomData.values.flatMap { it.value }.filter { it.sourceId == sourceId })

    /** Test helper: inject items for a given source into the fake, keyed by their libraryId. */
    fun emit(sourceId: String, items: List<LibraryItemEntity>) {
        items.groupBy { it.libraryId }.forEach { (libraryId, group) ->
            val flow = roomData.getOrPut(libraryId) { MutableStateFlow(emptyList()) }
            val ids = group.map { it.id }.toSet()
            flow.value = flow.value.filterNot { it.sourceId == sourceId && it.id in ids } + group
        }
    }
}
