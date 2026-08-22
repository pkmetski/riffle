package com.riffle.core.data

import com.riffle.core.database.BookFormattingPreferencesDao
import com.riffle.core.database.BookFormattingPreferencesEntity
import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.FormattingScope
import com.riffle.core.models.ScreenDimensionBucket
import com.riffle.core.models.ScreenDimensionBucket.SizeClass
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookFormattingPreferencesStoreImplTest {

    private val testSource = Source(
        id = "src1",
        url = SourceUrl.parse("http://test")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "",
        serverType = ServerType.AUDIOBOOKSHELF,
    )

    private class FakeSourceRepository(private val source: Source?) : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = MutableStateFlow(listOfNotNull(source))
        override suspend fun getActive() = source
        override suspend fun getById(sourceId: String): Source? = source?.takeIf { it.id == sourceId }
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult = throw UnsupportedOperationException()
        override suspend fun setActive(sourceId: String) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private val capturedGetArgs = mutableListOf<Pair<String, String>>() // (scope, bucket)

    private inner class FakeDao : BookFormattingPreferencesDao {
        var entityToReturn: BookFormattingPreferencesEntity? = null
        val upserted = mutableListOf<BookFormattingPreferencesEntity>()
        val deleted = mutableListOf<Pair<String, String>>() // (scope, bucket)

        override suspend fun upsert(entity: BookFormattingPreferencesEntity) {
            upserted += entity
        }
        override suspend fun getByItemId(
            sourceId: String, itemId: String, scope: String, screenDimensionBucket: String,
        ): BookFormattingPreferencesEntity? {
            capturedGetArgs += scope to screenDimensionBucket
            return entityToReturn
        }
        override suspend fun deleteByItemId(
            sourceId: String, itemId: String, scope: String, screenDimensionBucket: String,
        ) {
            deleted += scope to screenDimensionBucket
        }
    }

    @Test
    fun load_passesEncodedDimensionToDao() = runTest {
        val dao = FakeDao()
        val store = BookFormattingPreferencesStoreImpl(dao, FakeSourceRepository(testSource))
        val bucket = ScreenDimensionBucket.of(SizeClass.Compact, SizeClass.Compact)

        store.load("book1", FormattingScope.FullBook, bucket)

        assertEquals("FullBook", capturedGetArgs.first().first)
        assertEquals(bucket.encode(), capturedGetArgs.first().second)
    }

    @Test
    fun load_returnsNull_whenNoRowFound() = runTest {
        val dao = FakeDao().also { it.entityToReturn = null }
        val store = BookFormattingPreferencesStoreImpl(dao, FakeSourceRepository(testSource))

        val result = store.load("book1", FormattingScope.FullBook, ScreenDimensionBucket.PhonePortrait)

        assertNull(result)
    }

    @Test
    fun save_includesEncodedDimensionInEntity() = runTest {
        val dao = FakeDao()
        val store = BookFormattingPreferencesStoreImpl(dao, FakeSourceRepository(testSource))
        val bucket = ScreenDimensionBucket.of(SizeClass.Medium, SizeClass.Expanded)
        val overrides = BookFormattingOverrides(theme = ReaderTheme.Dark)

        store.save("book1", FormattingScope.FullBook, bucket, overrides)

        assertEquals(1, dao.upserted.size)
        assertEquals(bucket.encode(), dao.upserted.first().screenDimensionBucket)
        assertEquals("Dark", dao.upserted.first().theme)
    }

    @Test
    fun clear_passesEncodedDimensionToDao() = runTest {
        val dao = FakeDao()
        val store = BookFormattingPreferencesStoreImpl(dao, FakeSourceRepository(testSource))
        val bucket = ScreenDimensionBucket.of(SizeClass.Expanded, SizeClass.Compact)

        store.clear("book1", FormattingScope.FullBook, bucket)

        assertEquals(1, dao.deleted.size)
        assertEquals(bucket.encode(), dao.deleted.first().second)
    }

    @Test
    fun load_returnsNull_whenNoActiveSource() = runTest {
        val dao = FakeDao()
        val store = BookFormattingPreferencesStoreImpl(dao, FakeSourceRepository(null))

        val result = store.load("book1", FormattingScope.FullBook, ScreenDimensionBucket.PhonePortrait)

        assertNull(result)
    }
}
