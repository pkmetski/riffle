package com.riffle.core.data

import com.riffle.core.database.BookFormattingPreferencesDao
import com.riffle.core.database.BookFormattingPreferencesEntity
import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.models.ScreenDimensionBucket
import com.riffle.core.models.ScreenDimensionBucket.SizeClass
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookFormattingPreferencesStoreImplTest {

    private val capturedGetBuckets = mutableListOf<String>()
    private val capturedGetSourceIds = mutableListOf<String>()

    private inner class FakeDao : BookFormattingPreferencesDao {
        var entityToReturn: BookFormattingPreferencesEntity? = null
        val upserted = mutableListOf<BookFormattingPreferencesEntity>()
        val deletedBuckets = mutableListOf<String>()

        override suspend fun upsert(entity: BookFormattingPreferencesEntity) {
            upserted += entity
        }
        override suspend fun getByItemId(
            sourceId: String, itemId: String, screenDimensionBucket: String,
        ): BookFormattingPreferencesEntity? {
            capturedGetSourceIds += sourceId
            capturedGetBuckets += screenDimensionBucket
            return entityToReturn
        }
        override suspend fun deleteByItemId(
            sourceId: String, itemId: String, screenDimensionBucket: String,
        ) {
            deletedBuckets += screenDimensionBucket
        }
    }

    @Test
    fun load_passesEncodedDimensionToDao() = runTest {
        val dao = FakeDao()
        val store = BookFormattingPreferencesStoreImpl(dao)
        val bucket = ScreenDimensionBucket.of(SizeClass.Compact, SizeClass.Compact)

        store.load("src1", "book1", bucket)

        assertEquals(bucket.encode(), capturedGetBuckets.first())
    }

    @Test
    fun load_passesSourceIdToDao() = runTest {
        val dao = FakeDao()
        val store = BookFormattingPreferencesStoreImpl(dao)

        store.load("my-source", "book1", ScreenDimensionBucket.PhonePortrait)

        assertEquals("my-source", capturedGetSourceIds.first())
    }

    @Test
    fun load_returnsNull_whenNoRowFound() = runTest {
        val dao = FakeDao().also { it.entityToReturn = null }
        val store = BookFormattingPreferencesStoreImpl(dao)

        val result = store.load("src1", "book1", ScreenDimensionBucket.PhonePortrait)

        assertNull(result)
    }

    @Test
    fun save_includesEncodedDimensionInEntity() = runTest {
        val dao = FakeDao()
        val store = BookFormattingPreferencesStoreImpl(dao)
        val bucket = ScreenDimensionBucket.of(SizeClass.Medium, SizeClass.Expanded)
        val overrides = BookFormattingOverrides(theme = ReaderTheme.Dark)

        store.save("src1", "book1", bucket, overrides)

        assertEquals(1, dao.upserted.size)
        assertEquals(bucket.encode(), dao.upserted.first().screenDimensionBucket)
        assertEquals("Dark", dao.upserted.first().theme)
    }

    @Test
    fun save_includesSourceIdInEntity() = runTest {
        val dao = FakeDao()
        val store = BookFormattingPreferencesStoreImpl(dao)
        val overrides = BookFormattingOverrides(theme = ReaderTheme.Dark)

        store.save("my-source", "book1", ScreenDimensionBucket.PhonePortrait, overrides)

        assertEquals("my-source", dao.upserted.first().sourceId)
    }

    @Test
    fun clear_passesEncodedDimensionToDao() = runTest {
        val dao = FakeDao()
        val store = BookFormattingPreferencesStoreImpl(dao)
        val bucket = ScreenDimensionBucket.of(SizeClass.Expanded, SizeClass.Compact)

        store.clear("src1", "book1", bucket)

        assertEquals(1, dao.deletedBuckets.size)
        assertEquals(bucket.encode(), dao.deletedBuckets.first())
    }
}
