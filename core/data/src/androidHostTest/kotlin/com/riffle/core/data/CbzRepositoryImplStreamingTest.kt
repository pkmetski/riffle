package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.CbzPageStreamCapability
import com.riffle.core.catalog.SortKey
import com.riffle.core.common.FileStore
import com.riffle.core.domain.CbzDownloadResult
import com.riffle.core.domain.CbzOpenResult
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CbzRepositoryImplStreamingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val streamingCatalog = object : Catalog, CbzPageStreamCapability {
        val imageBytes = byteArrayOf(1, 2, 3, 4)
        var lastItemId: String? = null
        var lastPageIndex: Int = -1
        var lastMaxWidth: Int? = null

        override val sourceType: SourceType = SourceType.KOMGA
        override suspend fun listRoots(): List<CatalogRoot> = emptyList()
        override suspend fun browse(rootId: String, sort: SortKey, page: Int, pageSize: Int, facet: com.riffle.core.catalog.FacetSelection?):
            List<CatalogItem> = emptyList()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int): List<CatalogItem> = emptyList()
        override suspend fun getItem(itemId: String): CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle = error("not used")
        override suspend fun <T> withFileStream(itemId: String, format: BookFormat, handleHint: String?, block: suspend (CatalogFileStream) -> T): T =
            error("not used")
        override suspend fun connectivityCheck(): CatalogHealth = CatalogHealth(isReachable = true)
        override suspend fun fetchCbzPageImage(itemId: String, pageIndex: Int, maxWidth: Int?): ByteArray {
            lastItemId = itemId
            lastPageIndex = pageIndex
            lastMaxWidth = maxWidth
            return imageBytes
        }
        override suspend fun fetchCbzPageCount(itemId: String): Int = 20
    }

    private val basicCatalog = object : Catalog {
        override val sourceType: SourceType = SourceType.KOMGA
        override suspend fun listRoots(): List<CatalogRoot> = emptyList()
        override suspend fun browse(rootId: String, sort: SortKey, page: Int, pageSize: Int, facet: com.riffle.core.catalog.FacetSelection?):
            List<CatalogItem> = emptyList()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int): List<CatalogItem> = emptyList()
        override suspend fun getItem(itemId: String): CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle = error("not used")
        override suspend fun <T> withFileStream(itemId: String, format: BookFormat, handleHint: String?, block: suspend (CatalogFileStream) -> T): T =
            error("no streaming support")
        override suspend fun connectivityCheck(): CatalogHealth = CatalogHealth(isReachable = true)
    }

    private fun registryFor(matchingSourceId: String, catalog: Catalog?) = object : CatalogRegistry {
        override suspend fun forActive(): Catalog? = catalog
        override suspend fun forSource(source: Source): Catalog? = if (source.id == matchingSourceId) catalog else null
        override suspend fun forSourceId(sourceId: String): Catalog? = if (sourceId == matchingSourceId) catalog else null
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

    private fun fakeItem(sourceId: String = "src1") = LibraryItem(
        id = "item1", libraryId = "lib1", title = "Comic", author = "Author",
        coverUrl = null, readingProgress = 0f, isCached = false, isDownloaded = false,
        ebookFormat = EbookFormat.Cbz, sourceId = sourceId,
    )

    private fun makeFileStore(cacheDir: File, downloadsDir: File): FileStore =
        AndroidCbzFileStore(downloadsDir = downloadsDir, cacheDir = cacheDir)

    private fun makeRepo(
        fileStore: FileStore,
        catalog: Catalog?,
        sourceId: String = "src1",
    ) = JvmCbzRepositoryImpl(
        fileStore = fileStore,
        catalogRegistry = registryFor(sourceId, catalog),
        positionStore = emptyPositionStore,
        dispatchers = com.riffle.core.domain.DefaultDispatcherProvider,
    )

    @Test fun `removeDownload deletes file from downloads and cache dirs`() = runTest {
        val cacheDir = tmp.newFolder("cache")
        val downloadsDir = tmp.newFolder("downloads")
        val store = makeFileStore(cacheDir, downloadsDir)
        val cacheFile = File(store.resolve(NS_CBZ_CACHE, "src1/item1.cbz")).also {
            it.parentFile.mkdirs()
            it.writeText("x")
        }
        val dlFile = File(store.resolve(NS_CBZ_DOWNLOADS, "src1/item1.cbz")).also {
            it.parentFile.mkdirs()
            it.writeText("x")
        }
        val repo = makeRepo(store, null)

        repo.removeDownload("src1", "item1")

        assertFalse(cacheFile.exists())
        assertFalse(dlFile.exists())
        assertFalse(repo.isDownloaded("src1", "item1"))
        assertFalse(repo.isCached("src1", "item1"))
    }

    @Test fun `downloadCbz promotes cached file to downloads without fetching from catalog`() = runTest {
        val cacheDir = tmp.newFolder("cache")
        val downloadsDir = tmp.newFolder("downloads")
        val store = makeFileStore(cacheDir, downloadsDir)
        val cacheFile = File(store.resolve(NS_CBZ_CACHE, "src1/item1.cbz")).also {
            it.parentFile.mkdirs()
            it.writeText("cached")
        }
        val repo = makeRepo(store, basicCatalog)

        val result = repo.downloadCbz(fakeItem("src1"))

        assertEquals(CbzDownloadResult.Success, result)
        assertTrue(repo.isDownloaded("src1", "item1"))
        assertFalse(cacheFile.exists())
    }

    @Test fun `supportsStreaming returns true when catalog implements CbzPageStreamCapability`() = runTest {
        val store = makeFileStore(tmp.newFolder("cache"), tmp.newFolder("dl"))
        assertTrue(makeRepo(store, streamingCatalog).supportsStreaming("src1"))
    }

    @Test fun `supportsStreaming returns false when catalog lacks capability`() = runTest {
        val store = makeFileStore(tmp.newFolder("cache"), tmp.newFolder("dl"))
        assertFalse(makeRepo(store, basicCatalog).supportsStreaming("src1"))
    }

    @Test fun `supportsStreaming returns false when no catalog for sourceId`() = runTest {
        val store = makeFileStore(tmp.newFolder("cache"), tmp.newFolder("dl"))
        assertFalse(makeRepo(store, streamingCatalog, "other").supportsStreaming("src1"))
    }

    @Test fun `openCbz returns Streaming when no local file and catalog supports streaming`() = runTest {
        val store = makeFileStore(tmp.newFolder("cache"), tmp.newFolder("dl"))
        val result = makeRepo(store, streamingCatalog).openCbz(fakeItem("src1"))
        assertTrue("Expected Streaming but got $result", result is CbzOpenResult.Streaming)
        assertEquals(20, (result as CbzOpenResult.Streaming).pageCount)
        assertNull(result.lastPosition)
    }

    @Test fun `openCbz returns NetworkError when no local file and no streaming support`() = runTest {
        val store = makeFileStore(tmp.newFolder("cache"), tmp.newFolder("dl"))
        val result = makeRepo(store, basicCatalog).openCbz(fakeItem("src1"))
        assertTrue("Expected NetworkError but got $result", result is CbzOpenResult.NetworkError)
    }

    @Test fun `fetchStreamingPageImage delegates to catalog capability`() = runTest {
        val store = makeFileStore(tmp.newFolder("cache"), tmp.newFolder("dl"))
        val bytes = makeRepo(store, streamingCatalog).fetchStreamingPageImage("src1", "item1", 3)
        assertArrayEquals(streamingCatalog.imageBytes, bytes)
        assertEquals("item1", streamingCatalog.lastItemId)
        assertEquals(3, streamingCatalog.lastPageIndex)
    }

    @Test fun `awaitCachedSource returns null when no catalog for sourceId`() = runTest {
        val store = makeFileStore(tmp.newFolder("cache"), tmp.newFolder("dl"))
        val result = makeRepo(store, streamingCatalog, "other").awaitCachedSource(fakeItem("src1"))
        assertNull(result)
    }
}
