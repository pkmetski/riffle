package com.riffle.core.data

import com.riffle.core.catalog.AudiobookProgressPeerCapability
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.BookmarksCapability
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogBookmark
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogProgress
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.SortKey
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.sync.RemoteBookmark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSyncSourceResolverTest {

    private fun resolver(catalog: Catalog?, sourceType: SourceType = SourceType.ABS): CatalogSyncSourceResolver =
        CatalogSyncSourceResolver(
            registry = FixedCatalogRegistry(catalog),
            sourceRepository = FixedSourceRepository(sourceType),
        )

    @Test
    fun `maps catalog capabilities and bookmark operations to the shared sync port`() = runTest {
        val catalog = SyncCatalog()
        val resolver = resolver(catalog)

        val source = resolver.resolve("source")
        assertNotNull(source)

        assertTrue(source!!.supportsEbookProgress)
        assertTrue(source.supportsAudiobookProgress)
        val bookmarks = source.bookmarks
        assertNotNull(bookmarks)
        val bookmarkRemote = bookmarks!!
        assertEquals(
            listOf(RemoteBookmark("item", 42, "Chapter", 123L)),
            bookmarkRemote.listAll(),
        )

        bookmarkRemote.create("item", 10, "Created")
        bookmarkRemote.rename("item", 10, "Renamed")
        bookmarkRemote.delete("item", 10)

        assertEquals(
            listOf(
                "create:item:10:Created",
                "rename:item:10:Renamed",
                "delete:item:10",
            ),
            catalog.bookmarkCalls,
        )
    }

    // ADR 0063: web sources have no ProgressPeerCapability but must still report
    // supportsEbookProgress=true so ProgressSweep includes their dirty rows.
    // supportsAudiobookProgress=true for the same reason — Chitanka serves Gramofonche audiobooks.
    @Test
    fun `web source with no ProgressPeerCapability has supportsEbookProgress and supportsAudiobookProgress`() = runTest {
        val catalog = WebOnlyCatalog()
        val source = resolver(catalog, SourceType.CHITANKA).resolve("source")
        assertNotNull(source)
        assertTrue(source!!.supportsEbookProgress)
        assertTrue(source.supportsAudiobookProgress)
    }

    @Test
    fun `non-web source with no ProgressPeerCapability has supportsEbookProgress=false`() = runTest {
        val catalog = WebOnlyCatalog()
        val source = resolver(catalog, SourceType.LOCAL_FILES).resolve("source")
        assertNotNull(source)
        assertFalse(source!!.supportsEbookProgress)
    }

    private class FixedCatalogRegistry(
        private val catalog: Catalog?,
    ) : CatalogRegistry {
        override suspend fun forActive(): Catalog? = catalog
        override suspend fun forSource(source: Source): Catalog? = catalog
        override suspend fun forSourceId(sourceId: String): Catalog? = catalog
    }

    private class FixedSourceRepository(private val type: SourceType) : SourceRepository {
        private val fakeSource = Source(
            id = "source",
            url = SourceUrl.parse("https://source.example.com")!!,
            isActive = true,
            insecureConnectionAllowed = false,
            username = "u",
            type = type,
        )
        override fun observeAll(): Flow<List<Source>> = flowOf(listOf(fakeSource))
        override suspend fun getById(sourceId: String): Source? = fakeSource.takeIf { sourceId == fakeSource.id }
        override suspend fun getActive(): Source? = fakeSource
        override suspend fun commit(pending: com.riffle.core.domain.PendingSource, hiddenLibraryIds: Set<String>): com.riffle.core.domain.CommitSourceResult = error("not used")
        override suspend fun setActive(sourceId: String) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    /** Catalog that has neither ProgressPeerCapability nor AudiobookProgressPeerCapability. */
    private class WebOnlyCatalog : Catalog {
        override val sourceType = SourceType.CHITANKA
        override suspend fun listRoots() = emptyList<CatalogRoot>()
        override suspend fun browse(rootId: String, sort: SortKey, page: Int, pageSize: Int, facet: FacetSelection?) = emptyList<CatalogItem>()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int) = emptyList<CatalogItem>()
        override suspend fun getItem(itemId: String): CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle = error("not used")
        override suspend fun <T> withFileStream(itemId: String, format: BookFormat, handleHint: String?, block: suspend (CatalogFileStream) -> T): T = error("not used")
        override suspend fun connectivityCheck() = CatalogHealth(isReachable = true)
    }

    private class SyncCatalog : Catalog, AudiobookProgressPeerCapability, BookmarksCapability {
        override val sourceType = SourceType.ABS
        val bookmarkCalls = mutableListOf<String>()

        override suspend fun listRoots() = emptyList<CatalogRoot>()

        override suspend fun browse(
            rootId: String,
            sort: SortKey,
            page: Int,
            pageSize: Int,
            facet: FacetSelection?,
        ) = emptyList<CatalogItem>()

        override suspend fun search(
            rootId: String,
            query: String,
            page: Int,
            pageSize: Int,
        ) = emptyList<CatalogItem>()

        override suspend fun getItem(itemId: String): CatalogItem? = null

        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle =
            error("not used")

        override suspend fun <T> withFileStream(
            itemId: String,
            format: BookFormat,
            handleHint: String?,
            block: suspend (CatalogFileStream) -> T,
        ): T = error("not used")

        override suspend fun connectivityCheck() = CatalogHealth(isReachable = true)

        override suspend fun pushEbookProgress(
            itemId: String,
            location: String,
            progress: Float,
            isFinished: Boolean?,
            lastUpdateEpochMs: Long,
        ): Long? = null

        override suspend fun pushAudiobookProgress(
            itemId: String,
            currentTimeSec: Double,
            durationSec: Double,
            isFinished: Boolean?,
            lastUpdateEpochMs: Long,
        ): Long? = null

        override suspend fun pullProgress(itemId: String): CatalogProgress? = null

        override suspend fun pullAllProgress(): List<CatalogProgress> = emptyList()

        override suspend fun listAllBookmarks() =
            listOf(CatalogBookmark("item", 42, "Chapter", 123L))

        override suspend fun createBookmark(
            itemId: String,
            timeSec: Int,
            title: String,
        ): CatalogBookmark {
            bookmarkCalls += "create:$itemId:$timeSec:$title"
            return CatalogBookmark(itemId, timeSec, title, 0L)
        }

        override suspend fun deleteBookmark(itemId: String, timeSec: Int) {
            bookmarkCalls += "delete:$itemId:$timeSec"
        }

        override suspend fun renameBookmark(
            itemId: String,
            timeSec: Int,
            newTitle: String,
        ): CatalogBookmark {
            bookmarkCalls += "rename:$itemId:$timeSec:$newTitle"
            return CatalogBookmark(itemId, timeSec, newTitle, 0L)
        }
    }
}
