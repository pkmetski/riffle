package com.riffle.core.data

import com.riffle.core.domain.DefaultDispatcherProvider

import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.ReadingPositionEntity
import com.riffle.core.models.EbookFormat
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.SourceUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * Integration test: CFI persistence round-trip.
 *
 * Verifies that a freshly constructed repository instance picks up the reading position
 * saved by a previous instance — simulating what happens after an app restart.
 *
 * Uses a shared in-memory DAO (acting as the persistent Room database), a real
 * LocalStoreImpl, and a real MockWebServer.
 */
class EpubPositionIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var source: MockWebServer
    private lateinit var cacheStore: LocalStoreImpl
    private lateinit var sharedPositionDao: InMemoryReadingPositionDao

    private val epubBytes = "PK fake epub content".toByteArray()

    @Before
    fun setUp() {
        source = MockWebServer()
        source.start()
        cacheStore = LocalStoreImpl(tmp.newFolder("cache"), ".epub", com.riffle.core.domain.DefaultDispatcherProvider)
        sharedPositionDao = InMemoryReadingPositionDao()
    }

    @After
    fun tearDown() {
        source.shutdown()
    }

    private fun buildRepo(): EpubRepositoryImpl {
        val sourceRepo = object : SourceRepository {
            val activeServer = Source(
                id = "source-1",
                url = SourceUrl.parse(source.url("/").toString().trimEnd('/'))!!,
                isActive = true,
                insecureConnectionAllowed = false,
                username = "",
            )
            override fun observeAll(): Flow<List<Source>> = flowOf(listOf(activeServer))
            override suspend fun getActive(): Source = activeServer
            override suspend fun getById(sourceId: String): Source? = activeServer.takeIf { it.id == sourceId }
            override suspend fun commit(pending: com.riffle.core.domain.PendingSource, hiddenLibraryIds: Set<String>) =
                throw UnsupportedOperationException()
            override suspend fun setActive(sourceId: String) = Unit
            override suspend fun remove(sourceId: String) = Unit
            override suspend fun getSourceVersion(sourceId: String): String? = null
        }
        return EpubRepositoryImpl(
            catalogRegistry = TestCatalogRegistry(sourceRepo, mapOf("source-1" to "test-token")),
            cacheStore = cacheStore,
            downloadsStore = LocalStoreImpl(tmp.newFolder("downloads-${System.nanoTime()}"), ".epub", com.riffle.core.domain.DefaultDispatcherProvider),
            positionStore = ReadingPositionStoreImpl(sharedPositionDao, com.riffle.core.domain.TestClock(System.currentTimeMillis())),
            sourceRepository = sourceRepo,
        )
    }

    private fun item() = LibraryItem(
        id = "item-1",
        libraryId = "lib-1",
        title = "Test Book",
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        ebookFileIno = "ino-42",
        sourceId = "source-1",
    )

    @Test
    fun `reading position survives repository restart`() = runTest {
        source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))
        val repo1 = buildRepo()

        // First session: open book then save position
        repo1.openEpub(item())
        repo1.saveReadingPosition("item-1", "epubcfi(/6/4[chap01]!/4/2[body01]/1:0)")

        // Simulate restart: new repository instance backed by the same persistent DAO
        val repo2 = buildRepo()
        val result = repo2.openEpub(item()) as EpubOpenResult.Success

        assertEquals("epubcfi(/6/4[chap01]!/4/2[body01]/1:0)", result.lastPosition)
        // Cache hit — no second network request
        assertEquals(1, source.requestCount)
    }

    @Test
    fun `fresh book has no saved position after repository restart`() = runTest {
        source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))
        val repo1 = buildRepo()
        repo1.openEpub(item())
        // No saveReadingPosition call — simulates never having read the book

        val repo2 = buildRepo()
        val result = repo2.openEpub(item()) as EpubOpenResult.Success

        assertNull(result.lastPosition)
    }

    @Test
    fun `overwritten position is restored after repository restart`() = runTest {
        source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))
        val repo1 = buildRepo()
        repo1.openEpub(item())
        repo1.saveReadingPosition("item-1", "epubcfi(/6/2!/4/1:10)")
        repo1.saveReadingPosition("item-1", "epubcfi(/6/8!/4/1:99)")

        val repo2 = buildRepo()
        val result = repo2.openEpub(item()) as EpubOpenResult.Success

        assertEquals("epubcfi(/6/8!/4/1:99)", result.lastPosition)
    }

    private class InMemoryReadingPositionDao : ReadingPositionDao {
        private val entities = mutableMapOf<Pair<String, String>, ReadingPositionEntity>()
        override suspend fun upsert(entity: ReadingPositionEntity) {
            entities[entity.sourceId to entity.itemId] = entity
        }
        override suspend fun getByItemId(sourceId: String, itemId: String): ReadingPositionEntity? =
            entities[sourceId to itemId]
        override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) {
            entities[sourceId to itemId]?.let { entities[sourceId to itemId] = it.copy(localUpdatedAt = millis) }
        }
        override suspend fun acceptServerIfUnchanged(
            sourceId: String, itemId: String, position: String, serverStamp: Long, ifLocalUpdatedAt: Long,
        ): Int {
            val e = entities[sourceId to itemId] ?: return 0
            if (e.localUpdatedAt != ifLocalUpdatedAt) return 0
            entities[sourceId to itemId] = e.copy(cfi = position, localUpdatedAt = serverStamp, lastSyncedAt = serverStamp)
            return 1
        }
        override suspend fun confirmPushedIfUnchanged(
            sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long,
        ): Int {
            val e = entities[sourceId to itemId] ?: return 0
            if (e.localUpdatedAt != ifLocalUpdatedAt) return 0
            entities[sourceId to itemId] = e.copy(localUpdatedAt = serverStamp, lastSyncedAt = serverStamp)
            return 1
        }
        override suspend fun confirmInSyncIfUnchanged(sourceId: String, itemId: String, ifLocalUpdatedAt: Long): Int {
            val e = entities[sourceId to itemId] ?: return 0
            if (e.localUpdatedAt != ifLocalUpdatedAt) return 0
            entities[sourceId to itemId] = e.copy(lastSyncedAt = e.localUpdatedAt)
            return 1
        }
        override suspend fun dirtyForSource(sourceId: String) =
            entities.values.filter { it.sourceId == sourceId && it.localUpdatedAt > it.lastSyncedAt }
        override suspend fun sourcesWithDirtyRows() =
            entities.values.filter { it.localUpdatedAt > it.lastSyncedAt }.map { it.sourceId }.distinct()
    }
}
