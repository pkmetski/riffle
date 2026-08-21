package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.ReadingPositionEntity
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.ProgressRemote
import com.riffle.core.domain.RemoteProgress
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.sync.ProgressRemoteFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WebSourceLibraryItemMaterializerTest {

    private val chitankaSourceId = "chitanka-1"
    private val absSourceId = "abs-1"
    private val itemId = "book/12094-batman"

    private val chitankaSource = Source(
        id = chitankaSourceId,
        url = SourceUrl.parse("https://chitanka.info/")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "",
        type = SourceType.CHITANKA,
    )
    private val absSource = Source(
        id = absSourceId,
        url = SourceUrl.parse("https://abs.example.com/")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "u",
        type = SourceType.ABS,
    )

    private val fakeCatalogItem = CatalogItem(
        id = itemId,
        rootId = "books",
        title = "Batman",
        author = "Author",
        coverUrl = null,
        ebookFormat = BookFormat.Epub,
        hasAudio = false,
        language = "bg",
    )

    private fun sourceRepo(source: Source) = object : SourceRepository {
        override fun observeAll() = MutableStateFlow(listOf(source))
        override suspend fun getActive() = source
        override suspend fun getById(sourceId: String) = source.takeIf { it.id == sourceId }
        override suspend fun commit(p: PendingSource, h: Set<String>): CommitSourceResult =
            CommitSourceResult.Failure(RuntimeException())
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private fun positionDao(vararg ids: String): ReadingPositionDao {
        val rows = ids.map { ReadingPositionEntity(chitankaSourceId, it, "", 100L, 100L) }
        return object : ReadingPositionDao by ThrowingReadingPositionDao {
            override suspend fun allForSource(s: String) = rows
        }
    }

    private fun libraryItemDao(vararg existingIds: String): LibraryItemDao {
        val existing = existingIds.map {
            LibraryItemEntity(chitankaSourceId, it, "books", it, "", null, 0f, addedAt = 0L)
        }
        val dao = mockk<LibraryItemDao>(relaxed = true)
        coEvery { dao.observeBySource(any()) } returns MutableStateFlow(existing)
        return dao
    }

    private fun fakeRemoteFactory(progress: Float?): ProgressRemoteFactory {
        val remote = if (progress != null) {
            mockk<ProgressRemote<String>>(relaxed = true).also {
                coEvery { it.get() } returns RemoteProgress("cfi", 100L, progress, null)
            }
        } else null
        return mockk<ProgressRemoteFactory>(relaxed = true).also {
            coEvery { it.ebook(any(), any()) } returns remote
        }
    }

    private fun makeMaterializer(
        readingPositionDao: ReadingPositionDao = positionDao(itemId),
        libraryItemDao: LibraryItemDao = libraryItemDao(),
        sourceRepository: SourceRepository = sourceRepo(chitankaSource),
        catalogRegistry: CatalogRegistry = mockk(relaxed = true),
        remoteFactory: ProgressRemoteFactory = fakeRemoteFactory(0.3f),
        upserter: WebSourceLibraryItemUpserter = mockk(relaxed = true),
    ) = WebSourceLibraryItemMaterializer(
        readingPositionDao, libraryItemDao, sourceRepository, catalogRegistry, remoteFactory, upserter,
    )

    @Test
    fun `run creates library item and sets readingProgress for missing web-source item`() = runTest {
        val dao = libraryItemDao()  // empty
        val catalog = mockk<Catalog>(relaxed = true)
        coEvery { catalog.getItem(itemId) } returns fakeCatalogItem
        val registry = mockk<CatalogRegistry>(relaxed = true)
        coEvery { registry.forSourceId(chitankaSourceId) } returns catalog
        val upserter = mockk<WebSourceLibraryItemUpserter>(relaxed = true)

        makeMaterializer(libraryItemDao = dao, catalogRegistry = registry, upserter = upserter)
            .run(chitankaSourceId)

        coVerify { catalog.getItem(itemId) }
        coVerify { upserter.upsert(chitankaSourceId, fakeCatalogItem) }
        coVerify { dao.updateReadingProgress(chitankaSourceId, itemId, 0.3f) }
    }

    @Test
    fun `run skips items that already have a library row`() = runTest {
        val catalog = mockk<Catalog>(relaxed = true)
        val registry = mockk<CatalogRegistry>(relaxed = true)
        coEvery { registry.forSourceId(chitankaSourceId) } returns catalog
        val upserter = mockk<WebSourceLibraryItemUpserter>(relaxed = true)

        makeMaterializer(
            libraryItemDao = libraryItemDao(itemId),  // row already exists
            catalogRegistry = registry,
            upserter = upserter,
        ).run(chitankaSourceId)

        coVerify(exactly = 0) { catalog.getItem(any()) }
        coVerify(exactly = 0) { upserter.upsert(any(), any()) }
    }

    @Test
    fun `run skips non-web sources`() = runTest {
        val registry = mockk<CatalogRegistry>(relaxed = true)
        val upserter = mockk<WebSourceLibraryItemUpserter>(relaxed = true)

        makeMaterializer(
            sourceRepository = sourceRepo(absSource),
            catalogRegistry = registry,
            upserter = upserter,
        ).run(absSourceId)

        coVerify(exactly = 0) { registry.forSourceId(any()) }
        coVerify(exactly = 0) { upserter.upsert(any(), any()) }
    }

    @Test
    fun `run is resilient when catalog getItem fails`() = runTest {
        val catalog = mockk<Catalog>(relaxed = true)
        coEvery { catalog.getItem(any()) } throws RuntimeException("network error")
        val registry = mockk<CatalogRegistry>(relaxed = true)
        coEvery { registry.forSourceId(chitankaSourceId) } returns catalog
        val upserter = mockk<WebSourceLibraryItemUpserter>(relaxed = true)

        // Should not throw
        makeMaterializer(catalogRegistry = registry, upserter = upserter)
            .run(chitankaSourceId)

        coVerify(exactly = 0) { upserter.upsert(any(), any()) }
    }

    @Test
    fun `run is resilient when remoteFactory returns null`() = runTest {
        val dao = libraryItemDao()
        val catalog = mockk<Catalog>(relaxed = true)
        coEvery { catalog.getItem(itemId) } returns fakeCatalogItem
        val registry = mockk<CatalogRegistry>(relaxed = true)
        coEvery { registry.forSourceId(chitankaSourceId) } returns catalog
        val upserter = mockk<WebSourceLibraryItemUpserter>(relaxed = true)

        // remoteFactory returns null (no remote configured)
        makeMaterializer(
            libraryItemDao = dao,
            catalogRegistry = registry,
            remoteFactory = fakeRemoteFactory(null),
            upserter = upserter,
        ).run(chitankaSourceId)

        // upsert still happened; readingProgress update was skipped
        coVerify { upserter.upsert(chitankaSourceId, fakeCatalogItem) }
        coVerify(exactly = 0) { dao.updateReadingProgress(any(), any(), any()) }
    }
}

private object ThrowingReadingPositionDao : ReadingPositionDao {
    override suspend fun upsert(e: com.riffle.core.database.ReadingPositionEntity) = Unit
    override suspend fun getByItemId(s: String, i: String) = null
    override suspend fun updateLocalTimestamp(s: String, i: String, m: Long) = Unit
    override suspend fun acceptServerIfUnchanged(s: String, i: String, p: String, ss: Long, ila: Long) = 0
    override suspend fun confirmPushedIfUnchanged(s: String, i: String, ss: Long, ila: Long) = 0
    override suspend fun confirmInSyncIfUnchanged(s: String, i: String, ila: Long) = 0
    override suspend fun dirtyForSource(s: String) = emptyList<com.riffle.core.database.ReadingPositionEntity>()
    override suspend fun sourcesWithDirtyRows() = emptyList<String>()
    override suspend fun allForSource(s: String) = emptyList<com.riffle.core.database.ReadingPositionEntity>()
}
