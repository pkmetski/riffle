package com.riffle.core.data

import com.riffle.core.database.CollectionDao
import com.riffle.core.database.CollectionEntity
import com.riffle.core.database.CollectionItemEntity
import com.riffle.core.database.LastOpenedAtRow
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LibraryItemMetadata
import com.riffle.core.database.MatchableItemRow
import com.riffle.core.database.ReadingProgressRow
import com.riffle.core.database.SeriesDao
import com.riffle.core.database.SeriesEntity
import com.riffle.core.database.SeriesItemEntity
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression: IosLibraryObserverImpl did not override observeInProgressItemsAllSources,
 * observeContinueSeriesItemsAllSources, or observeLibraryItemsForSource — all three fell back to
 * the interface default (flowOf(emptyList())). The Riffle screen drives its In Progress, Continue
 * Series, and To Read sections from these three methods, so the screen was always empty on iOS.
 *
 * Reverting any of the three overrides makes the corresponding test fail (empty instead of the
 * expected items).
 */
class IosLibraryObserverRiffleMethodsTest {

    private val inProgressItem = libraryItemEntity("src-1", "item-in-progress", 0.3f)
    private val continueSeriesItem = libraryItemEntity("src-1", "item-continue", 0.0f)
    private val libraryItem = libraryItemEntity("src-2", "item-lib", 0.0f)

    private fun observer(
        inProgressItems: List<LibraryItemEntity> = emptyList(),
        continueSeriesItems: List<LibraryItemEntity> = emptyList(),
        itemsBySourceLibrary: Map<Pair<String, String>, List<LibraryItemEntity>> = emptyMap(),
    ) = IosLibraryObserverImpl(
        libraryDao = ThrowingLibraryDao,
        sourceRepository = NoSources,
        libraryItemDao = stubItemDao(inProgressItems, itemsBySourceLibrary),
        seriesDao = stubSeriesDao(continueSeriesItems),
        collectionDao = EmptyCollectionDao,
    )

    @Test
    fun observeInProgressItemsAllSourcesDelegatesToDao() = runTest {
        val result = observer(inProgressItems = listOf(inProgressItem))
            .observeInProgressItemsAllSources().first()
        assertEquals(listOf("item-in-progress"), result.map { it.id })
    }

    @Test
    fun observeContinueSeriesItemsAllSourcesDelegatesToDao() = runTest {
        val result = observer(continueSeriesItems = listOf(continueSeriesItem))
            .observeContinueSeriesItemsAllSources().first()
        assertEquals(listOf("item-continue"), result.map { it.id })
    }

    @Test
    fun observeLibraryItemsForSourceDelegatesToDao() = runTest {
        val result = observer(itemsBySourceLibrary = mapOf(("src-2" to "lib-2") to listOf(libraryItem)))
            .observeLibraryItemsForSource("src-2", "lib-2").first()
        assertEquals(listOf("item-lib"), result.map { it.id })
    }

    @Test
    fun observeLibraryItemsForSourceReturnsEmptyForUnknownLibrary() = runTest {
        val result = observer(itemsBySourceLibrary = emptyMap())
            .observeLibraryItemsForSource("src-1", "unknown-lib").first()
        assertEquals(emptyList(), result.map { it.id })
    }
}

private object NoSources : SourceRepository {
    override fun observeAll(): Flow<List<Source>> = flowOf(emptyList())
    override suspend fun getActive(): Source? = null
    override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
        CommitSourceResult.Failure(IllegalStateException())
    override suspend fun setActive(sourceId: String) = Unit
    override suspend fun remove(sourceId: String) = Unit
    override suspend fun getSourceVersion(sourceId: String): String? = null
}

private object EmptyCollectionDao : CollectionDao {
    override fun observeByLibraryId(libraryId: String): Flow<List<CollectionEntity>> = flowOf(emptyList())
    override fun observeItemsByCollectionId(sourceId: String, collectionId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override suspend fun upsertAll(collections: List<CollectionEntity>) = Unit
    override suspend fun upsertAllItems(items: List<CollectionItemEntity>) = Unit
    override suspend fun deleteByLibraryId(libraryId: String) = Unit
    override suspend fun deleteItemsByLibraryId(libraryId: String) = Unit
}

private fun stubItemDao(
    inProgressItems: List<LibraryItemEntity>,
    itemsBySourceLibrary: Map<Pair<String, String>, List<LibraryItemEntity>>,
): LibraryItemDao = object : LibraryItemDao {
    override fun observeByLibraryId(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> =
        flowOf(itemsBySourceLibrary[sourceId to libraryId] ?: emptyList())
    override fun observeInProgressAllSources(): Flow<List<LibraryItemEntity>> = flowOf(inProgressItems)
    override fun observeUngroupedByLibraryId(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override fun observeInProgress(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override fun observeFinished(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override fun observeRecentlyAdded(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override fun observeAllBooks(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override fun observeBySource(sourceId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override fun observeById(sourceId: String, itemId: String): Flow<LibraryItemEntity?> = flowOf(null)
    override suspend fun getById(sourceId: String, itemId: String): LibraryItemEntity? = null
    override suspend fun upsertAll(items: List<LibraryItemEntity>) = Unit
    override suspend fun insertOrIgnore(items: List<LibraryItemEntity>) = Unit
    override suspend fun updateMetadata(metadata: LibraryItemMetadata) = Unit
    override suspend fun listByLibraryId(sourceId: String, libraryId: String): List<LibraryItemEntity> = emptyList()
    override suspend fun listByIds(sourceId: String, itemIds: List<String>): List<LibraryItemEntity> = emptyList()
    override suspend fun findSourceIdForItem(itemId: String): String? = null
    override suspend fun deleteByLibraryId(sourceId: String, libraryId: String) = Unit
    override suspend fun deleteById(sourceId: String, itemId: String) = Unit
    override suspend fun deleteByIds(sourceId: String, itemIds: List<String>) = Unit
    override suspend fun idsForLibrary(sourceId: String, libraryId: String): List<String> = emptyList()
    override suspend fun updateLastOpenedAt(sourceId: String, itemId: String, timestamp: Long) = Unit
    override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) = Unit
    override suspend fun updateLibraryId(sourceId: String, itemId: String, libraryId: String) = Unit
    override suspend fun updateFinishedAt(sourceId: String, itemId: String, finishedAt: Long?) = Unit
    override suspend fun getLastOpenedAtMap(sourceId: String, libraryId: String): List<LastOpenedAtRow> = emptyList()
    override suspend fun getReadingProgressMap(sourceId: String, libraryId: String): List<ReadingProgressRow> = emptyList()
    override suspend fun listMatchableBySourceType(serverType: String): List<MatchableItemRow> = emptyList()
}

private fun stubSeriesDao(continueSeriesItems: List<LibraryItemEntity>): SeriesDao = object : SeriesDao {
    override fun observeContinueSeriesAllSources(): Flow<List<LibraryItemEntity>> = flowOf(continueSeriesItems)
    override fun observeByLibraryId(libraryId: String): Flow<List<SeriesEntity>> = flowOf(emptyList())
    override fun observeItemsBySeriesId(sourceId: String, seriesId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override fun observeContinueSeriesItems(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    override suspend fun findSeriesIdForItem(sourceId: String, itemId: String): String? = null
    override suspend fun upsertAll(series: List<SeriesEntity>) = Unit
    override suspend fun upsertAllItems(items: List<SeriesItemEntity>) = Unit
    override suspend fun deleteByLibraryId(libraryId: String) = Unit
    override suspend fun deleteItemsByLibraryId(libraryId: String) = Unit
}

private fun libraryItemEntity(sourceId: String, id: String, progress: Float) = LibraryItemEntity(
    sourceId = sourceId,
    id = id,
    libraryId = "lib-1",
    title = "Title $id",
    author = "Author",
    coverUrl = null,
    readingProgress = progress,
    ebookFormat = "epub",
    hasAudio = false,
    audioDurationSec = 0.0,
    addedAt = 0L,
)
