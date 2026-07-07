package com.riffle.core.data

import com.riffle.core.database.LastOpenedAtRow
import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LibraryItemMetadata
import com.riffle.core.database.MatchableItemRow
import com.riffle.core.database.ReadingProgressRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Empty [LibraryItemDao] for tests that never touch metadata resolution. */
internal object ThrowingLibraryItemDao : LibraryItemDao {
    override fun observeByLibraryId(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override fun observeUngroupedByLibraryId(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override fun observeInProgress(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override fun observeFinished(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override fun observeRecentlyAdded(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override fun observeAllBooks(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override suspend fun upsertAll(items: List<LibraryItemEntity>) = Unit
    override suspend fun insertOrIgnore(items: List<LibraryItemEntity>) = Unit
    override suspend fun updateMetadata(metadata: LibraryItemMetadata) = Unit
    override suspend fun getById(sourceId: String, itemId: String): LibraryItemEntity? = null
    override fun observeById(sourceId: String, itemId: String): Flow<LibraryItemEntity?> = flowOf(null)
    override suspend fun findSourceIdForItem(itemId: String): String? = null
    override suspend fun deleteByLibraryId(sourceId: String, libraryId: String) = Unit
    override suspend fun deleteRemovedFromLibrary(sourceId: String, libraryId: String, serverItemIds: List<String>) = Unit
    override suspend fun updateLastOpenedAt(sourceId: String, itemId: String, timestamp: Long) = Unit
    override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) = Unit
    override suspend fun updateFinishedAt(sourceId: String, itemId: String, finishedAt: Long?) = Unit
    override suspend fun getLastOpenedAtMap(sourceId: String, libraryId: String): List<LastOpenedAtRow> = emptyList()
    override suspend fun getReadingProgressMap(sourceId: String, libraryId: String): List<ReadingProgressRow> = emptyList()
    override suspend fun listMatchableBySourceType(serverType: String): List<MatchableItemRow> = emptyList()
    override fun observeBySource(sourceId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
}

/** Empty [LibraryDao] for tests that never resolve library names. */
internal object ThrowingLibraryDao : LibraryDao {
    override fun observeBySourceId(sourceId: String): Flow<List<LibraryEntity>> = flowOf(emptyList())
    override suspend fun libraryIdsForSource(sourceId: String): List<String> = emptyList()
    override suspend fun getById(sourceId: String, libraryId: String): LibraryEntity? = null
    override suspend fun upsertAll(libraries: List<LibraryEntity>) = Unit
    override suspend fun deleteBySourceId(sourceId: String) = Unit
    override suspend fun setUnsupported(sourceId: String, libraryId: String, isUnsupported: Boolean) = Unit
}
