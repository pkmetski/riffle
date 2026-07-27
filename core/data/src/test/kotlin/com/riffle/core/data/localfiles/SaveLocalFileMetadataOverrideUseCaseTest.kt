package com.riffle.core.data.localfiles

import com.riffle.core.database.LocalFileMetadataOverrideDao
import com.riffle.core.database.LocalFileMetadataOverrideEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaveLocalFileMetadataOverrideUseCaseTest {

    private val sourceId = "src-1"

    @Test
    fun `saves all provided fields`() = runTest {
        val dao = InMemoryOverrideDao()
        val useCase = SaveLocalFileMetadataOverrideUseCase(dao)
        useCase(sourceId, "item-1", "My Title", "My Author", "My Series", 2.5)
        val result = dao.rows[sourceId to "item-1"]!!
        assertEquals("My Title", result.title)
        assertEquals("My Author", result.author)
        assertEquals("My Series", result.seriesName)
        assertEquals(2.5, result.seriesIndex)
    }

    @Test
    fun `blank title is stored as null`() = runTest {
        val dao = InMemoryOverrideDao()
        val useCase = SaveLocalFileMetadataOverrideUseCase(dao)
        useCase(sourceId, "item-1", "  ", "Author", null, null)
        val result = dao.rows[sourceId to "item-1"]!!
        assertNull(result.title)
    }

    @Test
    fun `blank author is stored as null`() = runTest {
        val dao = InMemoryOverrideDao()
        val useCase = SaveLocalFileMetadataOverrideUseCase(dao)
        useCase(sourceId, "item-1", "Title", "", null, null)
        val result = dao.rows[sourceId to "item-1"]!!
        assertNull(result.author)
    }

    @Test
    fun `null seriesIndex passes through`() = runTest {
        val dao = InMemoryOverrideDao()
        val useCase = SaveLocalFileMetadataOverrideUseCase(dao)
        useCase(sourceId, "item-1", "Title", "Author", "Series", null)
        val result = dao.rows[sourceId to "item-1"]!!
        assertNull(result.seriesIndex)
    }

    @Test
    fun `upsert overwrites existing override`() = runTest {
        val dao = InMemoryOverrideDao()
        dao.rows[sourceId to "item-1"] = LocalFileMetadataOverrideEntity(
            sourceId = sourceId, sourceItemId = "item-1",
            title = "Old", author = null, seriesName = null, seriesIndex = null,
        )
        val useCase = SaveLocalFileMetadataOverrideUseCase(dao)
        useCase(sourceId, "item-1", "New Title", "New Author", null, null)
        val result = dao.rows[sourceId to "item-1"]!!
        assertEquals("New Title", result.title)
        assertEquals("New Author", result.author)
    }

    // region helpers

    private class InMemoryOverrideDao : LocalFileMetadataOverrideDao {
        val rows = mutableMapOf<Pair<String, String>, LocalFileMetadataOverrideEntity>()
        override suspend fun upsert(entity: LocalFileMetadataOverrideEntity) {
            rows[entity.sourceId to entity.sourceItemId] = entity
        }
        override fun observe(sourceId: String, sourceItemId: String): Flow<LocalFileMetadataOverrideEntity?> =
            MutableStateFlow(rows[sourceId to sourceItemId])
        override suspend fun getForItems(sourceId: String, sourceItemIds: List<String>): List<LocalFileMetadataOverrideEntity> =
            rows.values.filter { it.sourceId == sourceId && it.sourceItemId in sourceItemIds }
        override suspend fun getForItem(sourceId: String, sourceItemId: String): LocalFileMetadataOverrideEntity? =
            rows[sourceId to sourceItemId]
        override suspend fun delete(sourceId: String, sourceItemId: String) {
            rows.remove(sourceId to sourceItemId)
        }
    }

    // endregion
}
