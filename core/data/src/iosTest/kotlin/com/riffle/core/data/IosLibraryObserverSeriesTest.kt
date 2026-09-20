package com.riffle.core.data

import com.riffle.core.database.CollectionDao
import com.riffle.core.database.CollectionEntity
import com.riffle.core.database.CollectionItemEntity
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.SeriesDao
import com.riffle.core.database.SeriesEntity
import com.riffle.core.database.SeriesItemEntity
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #1071 §17 — `IosLibraryObserverImpl.getSeriesIdForItem` was a hardcoded `null`, even though
 * `IosSeriesDao.findSeriesIdForItem` has always implemented the query and `seriesDao` was already
 * a constructor dependency. `LibraryItemDetailViewModel` (commonMain, so it runs on iOS) calls it
 * to turn an item's `seriesName` string into a tappable series id, and always got nothing back.
 *
 * Reverting the delegation to `= null` makes [delegatesToTheSeriesDao] fail with
 * `expected:<series-9> but was:<null>`.
 */
class IosLibraryObserverSeriesTest {

    private class RecordingSeriesDao(private val seriesId: String?) : SeriesDao {
        var lastQuery: Pair<String, String>? = null

        override suspend fun findSeriesIdForItem(sourceId: String, itemId: String): String? {
            lastQuery = sourceId to itemId
            return seriesId
        }

        override fun observeByLibraryId(libraryId: String): Flow<List<SeriesEntity>> = flowOf(emptyList())
        override fun observeItemsBySeriesId(sourceId: String, seriesId: String): Flow<List<LibraryItemEntity>> =
            flowOf(emptyList())
        override fun observeContinueSeriesItems(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> =
            flowOf(emptyList())
        override fun observeContinueSeriesAllSources(): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override suspend fun upsertAll(series: List<SeriesEntity>) = Unit
        override suspend fun upsertAllItems(items: List<SeriesItemEntity>) = Unit
        override suspend fun deleteByLibraryId(libraryId: String) = Unit
        override suspend fun deleteItemsByLibraryId(libraryId: String) = Unit
    }

    private object EmptyCollectionDao : CollectionDao {
        override fun observeByLibraryId(libraryId: String): Flow<List<CollectionEntity>> = flowOf(emptyList())
        override fun observeItemsByCollectionId(sourceId: String, collectionId: String): Flow<List<LibraryItemEntity>> =
            flowOf(emptyList())
        override suspend fun upsertAll(collections: List<CollectionEntity>) = Unit
        override suspend fun upsertAllItems(items: List<CollectionItemEntity>) = Unit
        override suspend fun deleteByLibraryId(libraryId: String) = Unit
        override suspend fun deleteItemsByLibraryId(libraryId: String) = Unit
    }

    private object NoSourcesRepository : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(emptyList())
        override suspend fun getActive(): Source? = null
        override suspend fun commit(
            pending: PendingSource,
            hiddenLibraryIds: Set<String>,
        ): CommitSourceResult = CommitSourceResult.Failure(IllegalStateException("not needed"))
        override suspend fun setActive(sourceId: String) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private fun observer(seriesDao: SeriesDao) = IosLibraryObserverImpl(
        libraryDao = ThrowingLibraryDao,
        sourceRepository = NoSourcesRepository,
        libraryItemDao = ThrowingLibraryItemDao,
        seriesDao = seriesDao,
        collectionDao = EmptyCollectionDao,
    )

    @Test
    fun delegatesToTheSeriesDao() = runTest {
        val dao = RecordingSeriesDao(seriesId = "series-9")

        val result = observer(dao).getSeriesIdForItem("src-1", "item-1")

        assertEquals("series-9", result)
        assertEquals("src-1" to "item-1", dao.lastQuery)
    }

    @Test
    fun returnsNullForAnItemThatIsInNoSeries() = runTest {
        val dao = RecordingSeriesDao(seriesId = null)

        assertNull(observer(dao).getSeriesIdForItem("src-1", "loner"))
        assertEquals("src-1" to "loner", dao.lastQuery)
    }
}
