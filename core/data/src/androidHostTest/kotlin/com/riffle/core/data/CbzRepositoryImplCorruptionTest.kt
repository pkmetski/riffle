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
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.SortKey
import com.riffle.core.domain.CbzOpenResult
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests that [JvmCbzRepositoryImpl] correctly handles corrupt/truncated CBZ archives. Pins the
 * corruption-detection behaviour from #1101 (truncated downloads silently failed to open, leaving
 * the reader in a broken state) on the new okio-backed implementation.
 *
 * Uses real temporary directories rather than FakeFileSystem because [JvmCbzRepositoryImpl.openLocalArchive]
 * reads archives via java.io.File, which only sees the real filesystem.
 */
class CbzRepositoryImplCorruptionTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private inner class TempFileStore : com.riffle.core.common.FileStore {
        override fun resolve(namespace: String, relativePath: String): String {
            val base = File(tmpDir.root, namespace)
            base.mkdirs()
            return if (relativePath.isEmpty()) base.absolutePath else File(base, relativePath).absolutePath
        }
        override fun exists(namespace: String, relativePath: String): Boolean =
            File(resolve(namespace, relativePath)).exists()
    }

    private fun truncatedZipBytes(): ByteArray =
        byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(100) { it.toByte() }

    private fun validZipBytes(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("page.jpg"))
            zos.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    private val streamingCatalog = object : Catalog, CbzPageStreamCapability {
        override val sourceType: SourceType = SourceType.KOMGA
        override suspend fun listRoots(): List<CatalogRoot> = emptyList()
        override suspend fun browse(rootId: String, sort: SortKey, page: Int, pageSize: Int, facet: FacetSelection?): List<CatalogItem> = emptyList()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int): List<CatalogItem> = emptyList()
        override suspend fun getItem(itemId: String): CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle = error("not used")
        override suspend fun <T> withFileStream(
            itemId: String, format: BookFormat, handleHint: String?,
            block: suspend (CatalogFileStream) -> T,
        ): T = error("not used")
        override suspend fun connectivityCheck(): CatalogHealth = CatalogHealth(isReachable = true)
        override suspend fun fetchCbzPageImage(itemId: String, pageIndex: Int, maxWidth: Int?): ByteArray = byteArrayOf()
        override suspend fun fetchCbzPageCount(itemId: String): Int = 10
    }

    private fun registryFor(catalog: Catalog?) = object : CatalogRegistry {
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

    private fun fakeItem(sourceId: String = "src1") = LibraryItem(
        id = "item1", libraryId = "lib1", title = "Comic", author = "Author",
        coverUrl = null, readingProgress = 0f, isCached = false, isDownloaded = false,
        ebookFormat = EbookFormat.Cbz, sourceId = sourceId,
    )

    private fun makeRepo(
        fileStore: com.riffle.core.common.FileStore = TempFileStore(),
        catalog: Catalog? = streamingCatalog,
    ) = JvmCbzRepositoryImpl(
        fileStore = fileStore,
        catalogRegistry = registryFor(catalog),
        positionStore = noPosition,
        fileSystem = FileSystem.SYSTEM,
        dispatchers = com.riffle.core.domain.DefaultDispatcherProvider,
    )

    private fun writeFileToStore(store: com.riffle.core.common.FileStore, namespace: String, bytes: ByteArray): String {
        val path = store.resolve(namespace, "src1/item1.cbz")
        File(path).also { it.parentFile?.mkdirs() }.writeBytes(bytes)
        return path
    }

    @Test
    fun `openCbz falls back to Streaming when cached file is truncated`() = runTest {
        val store = TempFileStore()
        writeFileToStore(store, NS_CBZ_CACHE, truncatedZipBytes())
        val repo = makeRepo(store)

        val result = repo.openCbz(fakeItem())
        assertTrue("expected Streaming but got $result", result is CbzOpenResult.Streaming)
    }

    @Test
    fun `openCbz deletes corrupt cached file so next open can re-cache`() = runTest {
        val store = TempFileStore()
        val path = writeFileToStore(store, NS_CBZ_CACHE, truncatedZipBytes())
        makeRepo(store).openCbz(fakeItem())

        assertTrue("corrupt cache file must be deleted", !File(path).exists())
    }

    @Test
    fun `openCbz deletes corrupt downloaded file so streaming path is unblocked`() = runTest {
        val store = TempFileStore()
        val path = writeFileToStore(store, NS_CBZ_DOWNLOADS, truncatedZipBytes())
        makeRepo(store).openCbz(fakeItem())

        assertTrue("corrupt downloads file must be deleted", !File(path).exists())
    }

    @Test
    fun `openCbz returns Success for a valid cached file`() = runTest {
        val store = TempFileStore()
        writeFileToStore(store, NS_CBZ_CACHE, validZipBytes())
        val result = makeRepo(store).openCbz(fakeItem())

        assertTrue("expected Success but got $result", result is CbzOpenResult.Success)
    }

    @Test
    fun `awaitCachedSource returns null and leaves no file when download stream errors`() = runTest {
        val store = TempFileStore()
        val failingCatalog = object : Catalog by streamingCatalog {
            override suspend fun <T> withFileStream(
                itemId: String, format: BookFormat, handleHint: String?,
                block: suspend (CatalogFileStream) -> T,
            ): T = throw RuntimeException("network cut")
        }
        val result = makeRepo(store, failingCatalog).awaitCachedSource(fakeItem())

        assertNull("should return null on failure", result)
        val cachePath = store.resolve(NS_CBZ_CACHE, "src1/item1.cbz")
        assertTrue("partial cache file must not exist after failure", !File(cachePath).exists())
    }
}
