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
    override fun observeByLibraryId(serverId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override fun observeByServer(serverId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override fun observeUngroupedByLibraryId(serverId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override fun observeInProgress(serverId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override fun observeFinished(serverId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override fun observeRecentlyAdded(serverId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override fun observeAllBooks(serverId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override suspend fun upsertAll(items: List<LibraryItemEntity>) = Unit
    override suspend fun insertOrIgnore(items: List<LibraryItemEntity>) = Unit
    override suspend fun updateMetadata(metadata: LibraryItemMetadata) = Unit
    override suspend fun getById(serverId: String, itemId: String): LibraryItemEntity? = null
    override fun observeById(serverId: String, itemId: String): Flow<LibraryItemEntity?> = flowOf(null)
    override suspend fun findServerIdForItem(itemId: String): String? = null
    override suspend fun deleteByLibraryId(serverId: String, libraryId: String) = Unit
    override suspend fun deleteRemovedFromLibrary(serverId: String, libraryId: String, serverItemIds: List<String>) = Unit
    override suspend fun updateLastOpenedAt(serverId: String, itemId: String, timestamp: Long) = Unit
    override suspend fun updateReadingProgress(serverId: String, itemId: String, progress: Float) = Unit
    override suspend fun updateFinishedAt(serverId: String, itemId: String, finishedAt: Long?) = Unit
    override suspend fun getLastOpenedAtMap(serverId: String, libraryId: String): List<LastOpenedAtRow> = emptyList()
    override suspend fun getReadingProgressMap(serverId: String, libraryId: String): List<ReadingProgressRow> = emptyList()
    override suspend fun listMatchableByServerType(serverType: String): List<MatchableItemRow> = emptyList()
}

/** Empty [LibraryDao] for tests that never resolve library names. */
internal object ThrowingLibraryDao : LibraryDao {
    override fun observeByServerId(serverId: String): Flow<List<LibraryEntity>> = flowOf(emptyList())
    override suspend fun libraryIdsForServer(serverId: String): List<String> = emptyList()
    override suspend fun getById(serverId: String, libraryId: String): LibraryEntity? = null
    override suspend fun upsertAll(libraries: List<LibraryEntity>) = Unit
    override suspend fun deleteByServerId(serverId: String) = Unit
    override suspend fun setUnsupported(serverId: String, libraryId: String, isUnsupported: Boolean) = Unit
}
