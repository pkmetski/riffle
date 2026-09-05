package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import io.ktor.utils.io.ByteReadChannel
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.CbzPageStreamCapability
import com.riffle.core.catalog.FacetSelection
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CbzRepositoryImplCorruptionTest {

    @get:Rule val tmp = TemporaryFolder()

    // --- File helpers ---

    /** A file with valid ZIP magic bytes but no EOCD — simulates a truncated download. */
    private fun truncatedZip(): File = tmp.newFile("corrupt.cbz").also { f ->
        f.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(100) { it.toByte() })
    }

    /** A minimal valid ZIP (one dummy entry). */
    private fun validZip(): File = tmp.newFile("valid.cbz").also { f ->
        ZipOutputStream(f.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("page.jpg"))
            zos.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) // JPEG magic
            zos.closeEntry()
        }
    }

    // --- Fakes ---

    private fun storeFor(file: File?): TrackingStore = TrackingStore(file)

    inner class TrackingStore(private val file: File?) : LocalStore {
        var deleted = false
        override fun get(sourceId: String, itemId: String): File? = file
        override suspend fun save(sourceId: String, itemId: String, stream: InputStream): File =
            throw UnsupportedOperationException()
        override fun delete(sourceId: String, itemId: String) { deleted = true }
        override fun deleteSource(sourceId: String) {}
        override fun clear() {}
        override fun listItems() = emptyList<com.riffle.core.domain.StoredItemRef>()
    }

    private val emptyStore = storeFor(null)

    private val streamingCatalog = object : Catalog, CbzPageStreamCapability {
        override val sourceType: SourceType = SourceType.KOMGA
        override suspend fun listRoots(): List<CatalogRoot> = emptyList()
        override suspend fun browse(rootId: String, sort: SortKey, page: Int, pageSize: Int, facet: FacetSelection?): List<CatalogItem> = emptyList()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int): List<CatalogItem> = emptyList()
        override suspend fun getItem(itemId: String): CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle = throw UnsupportedOperationException()
        override suspend fun <T> withFileStream(itemId: String, format: BookFormat, handleHint: String?, block: suspend (CatalogFileStream) -> T): T = throw UnsupportedOperationException()
        override suspend fun connectivityCheck(): CatalogHealth = CatalogHealth(isReachable = true)
        override suspend fun fetchCbzPageImage(itemId: String, pageIndex: Int, maxWidth: Int?): ByteArray = byteArrayOf()
        override suspend fun fetchCbzPageCount(itemId: String): Int = 10
    }

    private fun registryFor(catalog: Catalog) = object : CatalogRegistry {
        override suspend fun forActive(): Catalog? = catalog
        override suspend fun forSource(source: Source): Catalog? = catalog
        override suspend fun forSourceId(sourceId: String): Catalog? = catalog
    }

    private val noPosition = object : ReadingPositionStore {
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

    @Test fun `openCbz falls back to Streaming when cached file is truncated`() = runTest {
        val corruptCache = storeFor(truncatedZip())
        val repo = CbzRepositoryImpl(
            registryFor(streamingCatalog), corruptCache, emptyStore, noPosition, noActiveSource,
        )
        val result = repo.openCbz(fakeItem())
        assertTrue("expected Streaming but got $result", result is CbzOpenResult.Streaming)
    }

    @Test fun `openCbz deletes corrupt cached file so next open can re-cache`() = runTest {
        val corruptCache = storeFor(truncatedZip())
        val repo = CbzRepositoryImpl(
            registryFor(streamingCatalog), corruptCache, emptyStore, noPosition, noActiveSource,
        )
        repo.openCbz(fakeItem())
        assertTrue("corrupt cache file must be deleted", corruptCache.deleted)
    }

    @Test fun `openCbz deletes corrupt downloaded file so streaming path is unblocked`() = runTest {
        val corruptDownload = storeFor(truncatedZip())
        val repo = CbzRepositoryImpl(
            registryFor(streamingCatalog), emptyStore, corruptDownload, noPosition, noActiveSource,
        )
        repo.openCbz(fakeItem())
        assertTrue("corrupt downloads file must be deleted", corruptDownload.deleted)
    }

    @Test fun `openCbz returns Success for a valid cached file`() = runTest {
        val goodCache = storeFor(validZip())
        val repo = CbzRepositoryImpl(
            registryFor(streamingCatalog), goodCache, emptyStore, noPosition, noActiveSource,
        )
        val result = repo.openCbz(fakeItem())
        assertTrue("expected Success but got $result", result is CbzOpenResult.Success)
    }

    @Test fun `awaitCachedFile deletes partial file on download failure`() = runTest {
        var deleted = false
        val failingCacheStore = object : LocalStore {
            override fun get(sourceId: String, itemId: String): File? = null
            override suspend fun save(sourceId: String, itemId: String, stream: InputStream): File =
                throw RuntimeException("network cut")
            override fun delete(sourceId: String, itemId: String) { deleted = true }
            override fun deleteSource(sourceId: String) {}
            override fun clear() {}
            override fun listItems() = emptyList<com.riffle.core.domain.StoredItemRef>()
        }
        val failingCatalog = object : Catalog, CbzPageStreamCapability by streamingCatalog {
            override val sourceType: SourceType = SourceType.KOMGA
            override suspend fun listRoots(): List<CatalogRoot> = emptyList()
            override suspend fun browse(rootId: String, sort: SortKey, page: Int, pageSize: Int, facet: FacetSelection?): List<CatalogItem> = emptyList()
            override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int): List<CatalogItem> = emptyList()
            override suspend fun getItem(itemId: String): CatalogItem? = null
            override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle = throw UnsupportedOperationException()
            override suspend fun <T> withFileStream(
                itemId: String, format: BookFormat, handleHint: String?,
                block: suspend (CatalogFileStream) -> T,
            ): T = block(object : CatalogFileStream {
                override val contentLength: Long get() = 10L
                override val channel: ByteReadChannel = ByteReadChannel(ByteArray(10))
            })
            override suspend fun connectivityCheck(): CatalogHealth = CatalogHealth(isReachable = true)
        }
        val repo = CbzRepositoryImpl(
            registryFor(failingCatalog), failingCacheStore, emptyStore, noPosition, noActiveSource,
        )
        val result = repo.awaitCachedFile(fakeItem())
        assertNull("should return null on failure", result)
        assertTrue("partial cache file must be deleted on failure", deleted)
    }
}
