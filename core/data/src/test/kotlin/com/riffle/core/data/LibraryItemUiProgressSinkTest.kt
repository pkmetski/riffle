package com.riffle.core.data

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LibraryItemUiProgressSinkTest {

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

    private val fakeLibraryItemEntity = LibraryItemEntity(
        sourceId = chitankaSourceId,
        id = itemId,
        libraryId = "books",
        title = "Batman",
        author = "Author",
        coverUrl = null,
        readingProgress = 0.3f,
        addedAt = 0L,
    )

    private fun sourceRepo(vararg sources: Source) = object : SourceRepository {
        override fun observeAll() = MutableStateFlow(sources.toList())
        override suspend fun getActive() = sources.firstOrNull()
        override suspend fun getById(sourceId: String) = sources.find { it.id == sourceId }
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            CommitSourceResult.Failure(RuntimeException())
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private fun makeSink(
        libraryItemDao: LibraryItemDao = mockk(relaxed = true),
        sourceRepository: SourceRepository = sourceRepo(chitankaSource),
        catalogRegistry: CatalogRegistry = mockk(relaxed = true),
        upserter: WebSourceLibraryItemUpserter = mockk(relaxed = true),
    ) = LibraryItemUiProgressSink(libraryItemDao, sourceRepository, catalogRegistry, upserter)

    @Test
    fun `apply updates readingProgress and finishedAt when row already exists`() = runTest {
        val dao = mockk<LibraryItemDao>(relaxed = true)
        coEvery { dao.getById(chitankaSourceId, itemId) } returns fakeLibraryItemEntity
        val catalog = mockk<CatalogRegistry>(relaxed = true)
        val upserter = mockk<WebSourceLibraryItemUpserter>(relaxed = true)

        makeSink(libraryItemDao = dao, catalogRegistry = catalog, upserter = upserter)
            .apply(chitankaSourceId, itemId, 0.5f, null)

        coVerify { dao.updateReadingProgress(chitankaSourceId, itemId, 0.5f) }
        coVerify { dao.updateFinishedAt(chitankaSourceId, itemId, null) }
        coVerify(exactly = 0) { catalog.forSourceId(any()) }
        coVerify(exactly = 0) { upserter.upsert(any(), any()) }
    }

    @Test
    fun `apply materializes library item for web source when row does not exist`() = runTest {
        val dao = mockk<LibraryItemDao>(relaxed = true)
        coEvery { dao.getById(chitankaSourceId, itemId) } returns null
        val catalog = mockk<Catalog>(relaxed = true)
        coEvery { catalog.getItem(itemId) } returns fakeCatalogItem
        val registry = mockk<CatalogRegistry>(relaxed = true)
        coEvery { registry.forSourceId(chitankaSourceId) } returns catalog
        val upserter = mockk<WebSourceLibraryItemUpserter>(relaxed = true)

        makeSink(libraryItemDao = dao, catalogRegistry = registry, upserter = upserter)
            .apply(chitankaSourceId, itemId, 0.3f, null)

        coVerify { catalog.getItem(itemId) }
        coVerify { upserter.upsert(chitankaSourceId, fakeCatalogItem) }
        coVerify { dao.updateReadingProgress(chitankaSourceId, itemId, 0.3f) }
    }

    @Test
    fun `apply skips materialization for server source when row does not exist`() = runTest {
        val dao = mockk<LibraryItemDao>(relaxed = true)
        coEvery { dao.getById(absSourceId, itemId) } returns null
        val registry = mockk<CatalogRegistry>(relaxed = true)
        val upserter = mockk<WebSourceLibraryItemUpserter>(relaxed = true)

        makeSink(
            libraryItemDao = dao,
            sourceRepository = sourceRepo(absSource),
            catalogRegistry = registry,
            upserter = upserter,
        ).apply(absSourceId, itemId, 0.3f, null)

        coVerify(exactly = 0) { registry.forSourceId(any()) }
        coVerify(exactly = 0) { upserter.upsert(any(), any()) }
        coVerify { dao.updateReadingProgress(absSourceId, itemId, 0.3f) }
    }

    @Test
    fun `apply still updates progress when catalog getItem returns null`() = runTest {
        val dao = mockk<LibraryItemDao>(relaxed = true)
        coEvery { dao.getById(chitankaSourceId, itemId) } returns null
        val catalog = mockk<Catalog>(relaxed = true)
        coEvery { catalog.getItem(itemId) } returns null
        val registry = mockk<CatalogRegistry>(relaxed = true)
        coEvery { registry.forSourceId(chitankaSourceId) } returns catalog

        makeSink(libraryItemDao = dao, catalogRegistry = registry)
            .apply(chitankaSourceId, itemId, 0.3f, null)

        coVerify { dao.updateReadingProgress(chitankaSourceId, itemId, 0.3f) }
    }

    @Test
    fun `apply still updates progress when catalog throws`() = runTest {
        val dao = mockk<LibraryItemDao>(relaxed = true)
        coEvery { dao.getById(chitankaSourceId, itemId) } returns null
        val catalog = mockk<Catalog>(relaxed = true)
        coEvery { catalog.getItem(any()) } throws RuntimeException("network error")
        val registry = mockk<CatalogRegistry>(relaxed = true)
        coEvery { registry.forSourceId(chitankaSourceId) } returns catalog

        makeSink(libraryItemDao = dao, catalogRegistry = registry)
            .apply(chitankaSourceId, itemId, 0.3f, null)

        coVerify { dao.updateReadingProgress(chitankaSourceId, itemId, 0.3f) }
    }
}
