package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.SortKey
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.AudiobookPositionEntity
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LibraryItemMetadata
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebSourceLibraryItemMaterializerTest {

    private val chitankaSourceId = "chitanka-1"
    private val absSourceId = "abs-1"
    private val itemId = "book/12094-batman"
    private val audioItemId = "prikazki/bez-dom"

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
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            CommitSourceResult.Failure(RuntimeException())
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private fun positionDao(vararg ids: String, deleted: Boolean = false): ReadingPositionDao {
        val rows = ids.map { ReadingPositionEntity(chitankaSourceId, it, "", 100L, 100L, deleted = deleted) }
        return object : ReadingPositionDao by NoopReadingPositionDao {
            override suspend fun allForSource(sourceId: String) = rows
        }
    }

    private fun audioPositionDao(vararg ids: String, deleted: Boolean = false): AudiobookPositionDao {
        val rows = ids.map { AudiobookPositionEntity(chitankaSourceId, it, 894.0, 100L, 100L, deleted = deleted) }
        return object : AudiobookPositionDao by NoopAudiobookPositionDao {
            override suspend fun allForSource(sourceId: String) = rows
        }
    }

    private inner class RecordingLibraryItemDao(existingIds: List<String> = emptyList()) : LibraryItemDao by ThrowingLibraryItemDao {
        val existing = existingIds.map { LibraryItemEntity(chitankaSourceId, it, "books", it, "", null, 0f, addedAt = 0L) }
        val insertedItems = mutableListOf<LibraryItemEntity>()
        val progressUpdates = mutableListOf<Triple<String, String, Float>>()
        override fun observeBySource(sourceId: String) = MutableStateFlow(existing)
        override suspend fun insertOrIgnore(items: List<LibraryItemEntity>) {
            insertedItems += items
        }
        override suspend fun updateMetadata(metadata: LibraryItemMetadata) = Unit
        override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) {
            progressUpdates += Triple(sourceId, itemId, progress)
        }
    }

    private inner class RecordingCatalog(private val items: Map<String, CatalogItem> = emptyMap()) : Catalog {
        val queriedItemIds = mutableListOf<String>()
        override val sourceType get() = SourceType.CHITANKA
        override suspend fun listRoots(): List<CatalogRoot> = emptyList()
        override suspend fun browse(rootId: String, sort: SortKey, page: Int, pageSize: Int, facet: FacetSelection?): List<CatalogItem> = emptyList()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int): List<CatalogItem> = emptyList()
        override suspend fun getItem(itemId: String): CatalogItem? {
            queriedItemIds += itemId
            return items[itemId]
        }
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle = error("not needed")
        override suspend fun <T> withFileStream(itemId: String, format: BookFormat, handleHint: String?, block: suspend (com.riffle.core.catalog.CatalogFileStream) -> T): T = error("not needed")
        override suspend fun connectivityCheck(): CatalogHealth = CatalogHealth(isReachable = true)
    }

    private fun catalogRegistry(catalog: Catalog? = null): CatalogRegistry = object : CatalogRegistry {
        override suspend fun forActive(): Catalog? = catalog
        override suspend fun forSource(source: Source): Catalog? = catalog
        override suspend fun forSourceId(sourceId: String): Catalog? = catalog
    }

    private fun fakeRemoteFactory(ebookProgress: Float? = 0.3f, audioProgress: Float? = 0.3f): ProgressRemoteFactory =
        object : ProgressRemoteFactory {
            override suspend fun ebook(sourceId: String, itemId: String): ProgressRemote<String>? =
                ebookProgress?.let { prog ->
                    object : ProgressRemote<String> {
                        override suspend fun get() = RemoteProgress("cfi", 100L, prog, null)
                        override suspend fun patch(position: String): Long? = null
                    }
                }
            override suspend fun audio(sourceId: String, itemId: String): ProgressRemote<Double>? =
                audioProgress?.let { prog ->
                    object : ProgressRemote<Double> {
                        override suspend fun get() = RemoteProgress(894.0, 100L, prog, null)
                        override suspend fun patch(position: Double): Long? = null
                    }
                }
        }

    private fun makeMaterializer(
        readingPositionDao: ReadingPositionDao = positionDao(itemId),
        audiobookPositionDao: AudiobookPositionDao = audioPositionDao(),
        libraryItemDao: RecordingLibraryItemDao = RecordingLibraryItemDao(),
        sourceRepository: SourceRepository = sourceRepo(chitankaSource),
        catalogRegistry: CatalogRegistry = catalogRegistry(),
        remoteFactory: ProgressRemoteFactory = fakeRemoteFactory(),
        upserter: WebSourceLibraryItemUpserter = WebSourceLibraryItemUpserter(libraryItemDao),
    ) = WebSourceLibraryItemMaterializer(
        readingPositionDao, audiobookPositionDao, libraryItemDao, sourceRepository, catalogRegistry, remoteFactory, upserter,
    )

    @Test
    fun `run creates library item and sets readingProgress for missing web-source item`() = runTest {
        val dao = RecordingLibraryItemDao()
        val catalog = RecordingCatalog(mapOf(itemId to fakeCatalogItem))
        val registry = catalogRegistry(catalog)

        makeMaterializer(libraryItemDao = dao, catalogRegistry = registry, upserter = WebSourceLibraryItemUpserter(dao))
            .run(chitankaSourceId)

        assertEquals(listOf(itemId), catalog.queriedItemIds)
        assertTrue(dao.insertedItems.any { it.id == itemId })
        assertEquals(listOf(Triple(chitankaSourceId, itemId, 0.3f)), dao.progressUpdates)
    }

    @Test
    fun `run skips items that already have a library row`() = runTest {
        val catalog = RecordingCatalog()
        val registry = catalogRegistry(catalog)

        makeMaterializer(
            libraryItemDao = RecordingLibraryItemDao(listOf(itemId)),
            catalogRegistry = registry,
        ).run(chitankaSourceId)

        assertTrue(catalog.queriedItemIds.isEmpty())
    }

    @Test
    fun `run skips non-web sources`() = runTest {
        val catalog = RecordingCatalog()
        val dao = RecordingLibraryItemDao()
        val registry = catalogRegistry(catalog)

        makeMaterializer(
            sourceRepository = sourceRepo(absSource),
            libraryItemDao = dao,
            catalogRegistry = registry,
        ).run(absSourceId)

        assertTrue(catalog.queriedItemIds.isEmpty())
        assertTrue(dao.insertedItems.isEmpty())
    }

    @Test
    fun `run is resilient when catalog getItem fails`() = runTest {
        val throwingCatalog = object : Catalog by RecordingCatalog() {
            override suspend fun getItem(itemId: String): CatalogItem? = throw RuntimeException("network error")
        }
        val dao = RecordingLibraryItemDao()

        makeMaterializer(
            libraryItemDao = dao,
            catalogRegistry = catalogRegistry(throwingCatalog),
            upserter = WebSourceLibraryItemUpserter(dao),
        ).run(chitankaSourceId)

        assertTrue(dao.insertedItems.isEmpty())
    }

    @Test
    fun `run is resilient when remoteFactory returns null`() = runTest {
        val dao = RecordingLibraryItemDao()
        val catalog = RecordingCatalog(mapOf(itemId to fakeCatalogItem))

        makeMaterializer(
            libraryItemDao = dao,
            catalogRegistry = catalogRegistry(catalog),
            remoteFactory = fakeRemoteFactory(ebookProgress = null, audioProgress = null),
            upserter = WebSourceLibraryItemUpserter(dao),
        ).run(chitankaSourceId)

        assertTrue(dao.insertedItems.any { it.id == itemId })
        assertTrue(dao.progressUpdates.isEmpty())
    }

    @Test
    fun `run skips ebook item that the user explicitly removed`() = runTest {
        val catalog = RecordingCatalog()
        val dao = RecordingLibraryItemDao()

        makeMaterializer(
            readingPositionDao = positionDao(itemId, deleted = true),
            libraryItemDao = dao,
            catalogRegistry = catalogRegistry(catalog),
        ).run(chitankaSourceId)

        assertTrue(catalog.queriedItemIds.isEmpty())
        assertTrue(dao.insertedItems.isEmpty())
    }

    @Test
    fun `run skips audio item that the user explicitly removed`() = runTest {
        val catalog = RecordingCatalog()
        val dao = RecordingLibraryItemDao()

        makeMaterializer(
            readingPositionDao = positionDao(),
            audiobookPositionDao = audioPositionDao(audioItemId, deleted = true),
            libraryItemDao = dao,
            catalogRegistry = catalogRegistry(catalog),
        ).run(chitankaSourceId)

        assertTrue(catalog.queriedItemIds.isEmpty())
        assertTrue(dao.insertedItems.isEmpty())
    }

    @Test
    fun `run creates library item for missing Gramofonche audio item`() = runTest {
        val audioCatalogItem = CatalogItem(
            id = audioItemId,
            rootId = "audiobooks",
            title = "Без дом",
            author = "Author",
            coverUrl = null,
            ebookFormat = BookFormat.Audiobook,
            hasAudio = true,
            language = "bg",
        )
        val dao = RecordingLibraryItemDao()
        val catalog = RecordingCatalog(mapOf(audioItemId to audioCatalogItem))

        makeMaterializer(
            readingPositionDao = positionDao(),
            audiobookPositionDao = audioPositionDao(audioItemId),
            libraryItemDao = dao,
            catalogRegistry = catalogRegistry(catalog),
            upserter = WebSourceLibraryItemUpserter(dao),
        ).run(chitankaSourceId)

        assertEquals(listOf(audioItemId), catalog.queriedItemIds)
        assertTrue(dao.insertedItems.any { it.id == audioItemId })
        assertEquals(listOf(Triple(chitankaSourceId, audioItemId, 0.3f)), dao.progressUpdates)
    }
}

private object NoopReadingPositionDao : ReadingPositionDao {
    override suspend fun upsert(entity: ReadingPositionEntity) = Unit
    override suspend fun getByItemId(sourceId: String, itemId: String): ReadingPositionEntity? = null
    override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) = Unit
    override suspend fun acceptServerIfUnchanged(sourceId: String, itemId: String, position: String, serverStamp: Long, ifLocalUpdatedAt: Long, deleted: Boolean) = 0
    override suspend fun confirmPushedIfUnchanged(sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long) = 0
    override suspend fun confirmInSyncIfUnchanged(sourceId: String, itemId: String, ifLocalUpdatedAt: Long) = 0
    override suspend fun dirtyForSource(sourceId: String) = emptyList<ReadingPositionEntity>()
    override suspend fun sourcesWithDirtyRows() = emptyList<String>()
    override suspend fun allForSource(sourceId: String) = emptyList<ReadingPositionEntity>()
    override suspend fun markDeleted(sourceId: String, itemId: String, localUpdatedAt: Long) = Unit
    override suspend fun acceptServerDeletionIfUnchanged(sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long) = 0
}

private object NoopAudiobookPositionDao : AudiobookPositionDao {
    override suspend fun upsert(entity: AudiobookPositionEntity) = Unit
    override suspend fun getByItemId(sourceId: String, itemId: String): AudiobookPositionEntity? = null
    override suspend fun acceptServerIfUnchanged(sourceId: String, itemId: String, positionSec: Double, serverStamp: Long, ifLocalUpdatedAt: Long, deleted: Boolean) = 0
    override suspend fun confirmPushedIfUnchanged(sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long) = 0
    override suspend fun confirmInSyncIfUnchanged(sourceId: String, itemId: String, ifLocalUpdatedAt: Long) = 0
    override suspend fun dirtyForSource(sourceId: String) = emptyList<AudiobookPositionEntity>()
    override suspend fun sourcesWithDirtyRows() = emptyList<String>()
    override suspend fun allForSource(sourceId: String) = emptyList<AudiobookPositionEntity>()
    override suspend fun markDeleted(sourceId: String, itemId: String, localUpdatedAt: Long) = Unit
    override suspend fun acceptServerDeletionIfUnchanged(sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long) = 0
}
