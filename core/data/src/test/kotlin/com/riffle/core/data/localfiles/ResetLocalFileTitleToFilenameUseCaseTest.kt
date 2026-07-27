package com.riffle.core.data.localfiles

import com.riffle.core.database.LocalFileMetadataOverrideDao
import com.riffle.core.database.LocalFileMetadataOverrideEntity
import com.riffle.core.database.LocalFilesFileDao
import com.riffle.core.database.LocalFilesFileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResetLocalFileTitleToFilenameUseCaseTest {

    private val sourceId = "src-1"

    @Test
    fun `strips extension and uses displayName when set`() = runTest {
        val fileDao = fakeFileDao(displayName = "My Book.epub")
        val overrideDao = InMemoryOverrideDao()
        val useCase = ResetLocalFileTitleToFilenameUseCase(fileDao, overrideDao)
        useCase(sourceId, "item-1")
        assertEquals("My Book", overrideDao.rows[sourceId to "item-1"]?.title)
    }

    @Test
    fun `falls back to uri filename when displayName is blank`() = runTest {
        val fileDao = fakeFileDao(
            displayName = "",
            originalUri = "content://com.android.externalstorage.documents/document/primary:Books/Great%20Work.epub",
        )
        val overrideDao = InMemoryOverrideDao()
        val useCase = ResetLocalFileTitleToFilenameUseCase(fileDao, overrideDao)
        useCase(sourceId, "item-1")
        assertEquals("Great Work", overrideDao.rows[sourceId to "item-1"]?.title)
    }

    @Test
    fun `preserves author and series overrides but clears coverUrl`() = runTest {
        val fileDao = fakeFileDao(displayName = "New Name.epub")
        val overrideDao = InMemoryOverrideDao()
        overrideDao.rows[sourceId to "item-1"] = LocalFileMetadataOverrideEntity(
            sourceId = sourceId,
            sourceItemId = "item-1",
            title = "Old Title",
            author = "Existing Author",
            seriesName = "Existing Series",
            seriesIndex = 3.0,
            coverUrl = "file:///data/data/com.riffle/files/local_covers/src-1_item-1.jpg",
        )
        val useCase = ResetLocalFileTitleToFilenameUseCase(fileDao, overrideDao)
        useCase(sourceId, "item-1")
        val result = overrideDao.rows[sourceId to "item-1"]!!
        assertEquals("New Name", result.title)
        assertEquals("Existing Author", result.author)
        assertEquals("Existing Series", result.seriesName)
        assertEquals(3.0, result.seriesIndex)
        assertNull(result.coverUrl)
    }

    @Test
    fun `no-ops when file not found`() = runTest {
        val fileDao = object : LocalFilesFileDao {
            override suspend fun upsert(entity: LocalFilesFileEntity) {}
            override suspend fun findById(sourceId: String, sourceItemId: String): LocalFilesFileEntity? = null
            override suspend fun forSource(sourceId: String): List<LocalFilesFileEntity> = emptyList()
            override suspend fun touchLastSeen(sourceId: String, sourceItemId: String, seenAt: Long) {}
            override suspend fun updateDisplayName(sourceId: String, sourceItemId: String, displayName: String) {}
            override suspend fun delete(sourceId: String, sourceItemId: String) {}
        }
        val overrideDao = InMemoryOverrideDao()
        val useCase = ResetLocalFileTitleToFilenameUseCase(fileDao, overrideDao)
        useCase(sourceId, "missing-item")
        assertNull(overrideDao.rows[sourceId to "missing-item"])
    }

    @Test
    fun `ExternalStorageProvider colon-encoded path extracts leaf after colon`() = runTest {
        val fileDao = fakeFileDao(
            displayName = "",
            originalUri = "content://com.android.externalstorage.documents/document/primary:Subdir/Deep%20Novel.epub",
        )
        val overrideDao = InMemoryOverrideDao()
        val useCase = ResetLocalFileTitleToFilenameUseCase(fileDao, overrideDao)
        useCase(sourceId, "item-1")
        assertEquals("Deep Novel", overrideDao.rows[sourceId to "item-1"]?.title)
    }

    // region helpers

    private fun fakeFileDao(
        displayName: String = "",
        originalUri: String = "content://authority/document/primary:test.epub",
    ): LocalFilesFileDao = object : LocalFilesFileDao {
        private val entity = LocalFilesFileEntity(
            sourceId = sourceId,
            sourceItemId = "item-1",
            originalUri = originalUri,
            copiedPath = "",
            coverPath = null,
            format = "EPUB",
            sizeBytes = 100L,
            mtimeEpochMs = 0L,
            lastSeenAtEpochMs = 0L,
            displayName = displayName,
        )
        override suspend fun upsert(entity: LocalFilesFileEntity) {}
        override suspend fun findById(sourceId: String, sourceItemId: String): LocalFilesFileEntity? =
            if (sourceItemId == "item-1") entity else null
        override suspend fun forSource(sourceId: String): List<LocalFilesFileEntity> = listOf(entity)
        override suspend fun touchLastSeen(sourceId: String, sourceItemId: String, seenAt: Long) {}
        override suspend fun updateDisplayName(sourceId: String, sourceItemId: String, displayName: String) {}
        override suspend fun delete(sourceId: String, sourceItemId: String) {}
    }

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
