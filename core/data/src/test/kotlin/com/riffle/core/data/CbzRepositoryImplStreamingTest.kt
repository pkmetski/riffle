package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.CbzPageStreamCapability
import com.riffle.core.catalog.SortKey
import com.riffle.core.models.SourceType
import com.riffle.core.domain.CbzOpenResult
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Source
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CbzRepositoryImplStreamingTest {

    // --- Fakes ---

    private val streamingCatalog = object : Catalog, CbzPageStreamCapability {
        val imageBytes = byteArrayOf(1, 2, 3, 4)
        var lastItemId: String? = null
        var lastPageIndex: Int = -1

        override val sourceType: SourceType = SourceType.KOMGA
        override suspend fun listRoots(): List<CatalogRoot> = emptyList()
        override suspend fun browse(rootId: String, sort: SortKey, page: Int, pageSize: Int, facet: com.riffle.core.catalog.FacetSelection?): List<CatalogItem> = emptyList()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int): List<CatalogItem> = emptyList()
        override suspend fun getItem(itemId: String): CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle = throw UnsupportedOperationException()
        override suspend fun <T> withFileStream(
            itemId: String, format: BookFormat, fileIno: String?,
            block: suspend (CatalogFileStream) -> T,
        ): T = throw UnsupportedOperationException()
        override suspend fun connectivityCheck(): CatalogHealth = CatalogHealth(isReachable = true)
        override suspend fun fetchCbzPageImage(itemId: String, pageIndex: Int): ByteArray {
            lastItemId = itemId
            lastPageIndex = pageIndex
            return imageBytes
        }
        override suspend fun fetchCbzPageCount(itemId: String): Int = 20
    }

    private val basicCatalog = object : Catalog {
        override val sourceType: SourceType = SourceType.KOMGA
        override suspend fun listRoots(): List<CatalogRoot> = emptyList()
        override suspend fun browse(rootId: String, sort: SortKey, page: Int, pageSize: Int, facet: com.riffle.core.catalog.FacetSelection?): List<CatalogItem> = emptyList()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int): List<CatalogItem> = emptyList()
        override suspend fun getItem(itemId: String): CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle = throw UnsupportedOperationException()
        override suspend fun <T> withFileStream(
            itemId: String, format: BookFormat, fileIno: String?,
            block: suspend (CatalogFileStream) -> T,
        ): T = throw UnsupportedOperationException("no streaming support")
        override suspend fun connectivityCheck(): CatalogHealth = CatalogHealth(isReachable = true)
    }

    private fun registryFor(sourceId: String, catalog: Catalog?) = object : CatalogRegistry {
        override suspend fun forActive(): Catalog? = catalog
        override suspend fun forSource(source: Source): Catalog? = if (source.id == sourceId) catalog else null
        override suspend fun forSourceId(id: String): Catalog? = if (id == sourceId) catalog else null
    }

    private val emptyStore = object : LocalStore {
        override fun get(sourceId: String, itemId: String): File? = null
        override suspend fun save(sourceId: String, itemId: String, stream: InputStream): File = throw UnsupportedOperationException()
        override fun delete(sourceId: String, itemId: String) {}
        override fun deleteSource(sourceId: String) {}
        override fun clear() {}
        override fun listItems() = emptyList<com.riffle.core.domain.StoredItemRef>()
    }

    private val emptyPositionStore = object : ReadingPositionStore {
        override suspend fun save(sourceId: String, itemId: String, payload: String) {}
        override suspend fun load(sourceId: String, itemId: String): String? = null
        override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun acceptServer(sourceId: String, itemId: String, payload: String, serverStamp: Long) {}
        override suspend fun markSyncedAt(sourceId: String, itemId: String, stamp: Long) {}
        override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) {}
    }

    private val noActiveSource = object : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(emptyList())
        override suspend fun getActive(): Source? = null
        override suspend fun getById(sourceId: String): Source? = null
        override suspend fun commit(pending: com.riffle.core.domain.PendingSource, hiddenLibraryIds: Set<String>) = throw UnsupportedOperationException()
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private fun fakeItem(sourceId: String = "src1") = LibraryItem(
        id = "item1", libraryId = "lib1", title = "Comic", author = "Author",
        coverUrl = null, readingProgress = 0f, isCached = false, isDownloaded = false,
        ebookFormat = EbookFormat.Cbz, sourceId = sourceId,
    )

    // --- Tests ---

    @Test fun `supportsStreaming returns true when catalog implements CbzPageStreamCapability`() = runTest {
        val repo = CbzRepositoryImpl(
            registryFor("src1", streamingCatalog),
            emptyStore, emptyStore, emptyPositionStore, noActiveSource,
        )
        assertTrue(repo.supportsStreaming("src1"))
    }

    @Test fun `supportsStreaming returns false when catalog lacks capability`() = runTest {
        val repo = CbzRepositoryImpl(
            registryFor("src1", basicCatalog),
            emptyStore, emptyStore, emptyPositionStore, noActiveSource,
        )
        assertFalse(repo.supportsStreaming("src1"))
    }

    @Test fun `supportsStreaming returns false when no catalog for sourceId`() = runTest {
        val repo = CbzRepositoryImpl(
            registryFor("other", streamingCatalog),
            emptyStore, emptyStore, emptyPositionStore, noActiveSource,
        )
        assertFalse(repo.supportsStreaming("src1"))
    }

    @Test fun `openCbz returns Streaming when no local file and catalog supports streaming`() = runTest {
        val repo = CbzRepositoryImpl(
            registryFor("src1", streamingCatalog),
            emptyStore, emptyStore, emptyPositionStore, noActiveSource,
        )
        val result = repo.openCbz(fakeItem("src1"))
        assertTrue("Expected Streaming but got $result", result is CbzOpenResult.Streaming)
        assertEquals(20, (result as CbzOpenResult.Streaming).pageCount)
        assertNull(result.lastPosition)
    }

    @Test fun `openCbz returns NetworkError when no local file and no streaming support`() = runTest {
        val repo = CbzRepositoryImpl(
            registryFor("src1", basicCatalog),
            emptyStore, emptyStore, emptyPositionStore, noActiveSource,
        )
        val result = repo.openCbz(fakeItem("src1"))
        assertTrue("Expected NetworkError but got $result", result is CbzOpenResult.NetworkError)
    }

    @Test fun `fetchStreamingPageImage delegates to catalog capability`() = runTest {
        val repo = CbzRepositoryImpl(
            registryFor("src1", streamingCatalog),
            emptyStore, emptyStore, emptyPositionStore, noActiveSource,
        )
        val bytes = repo.fetchStreamingPageImage("src1", "item1", 3)
        assertArrayEquals(streamingCatalog.imageBytes, bytes)
        assertEquals("item1", streamingCatalog.lastItemId)
        assertEquals(3, streamingCatalog.lastPageIndex)
    }

    @Test fun `awaitCachedFile returns null when no catalog for sourceId`() = runTest {
        val repo = CbzRepositoryImpl(
            registryFor("other", streamingCatalog),
            emptyStore, emptyStore, emptyPositionStore, noActiveSource,
        )
        val result = repo.awaitCachedFile(fakeItem("src1"))
        assertNull(result)
    }
}
