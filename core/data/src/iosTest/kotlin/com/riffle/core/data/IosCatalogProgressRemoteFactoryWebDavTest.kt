package com.riffle.core.data

import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.common.Clock
import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.AudiobookPositionEntity
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.ReadingPositionEntity
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.EbookCfiTranslatorFactory
import com.riffle.core.domain.IosDispatcherProvider
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SyncNamespace
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.sources.webdav.WebDavProgressRemoteFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * iOS counterpart to the CatalogProgressRemoteFactory WebDAV branch tests in androidHostTest.
 * Exercises [IosCatalogProgressRemoteFactory]'s ADR-0063 web-source fallback directly via the
 * iOS code path.
 */
class IosCatalogProgressRemoteFactoryWebDavTest {

    private val clock = object : Clock {
        override fun nowMs() = 1800L
        override fun nowNs() = 0L
    }

    private fun buildFactory(
        sourceType: SourceType = SourceType.CHITANKA,
        sourceId: String = "source-1",
        webDavBaseUrl: String? = "https://dav.example.com/riffle/",
    ): IosCatalogProgressRemoteFactory {
        val fakeCatalogRegistry = object : CatalogRegistry {
            override suspend fun forActive() = null
            override suspend fun forSource(source: Source) = null
            override suspend fun forSourceId(sourceId: String) = null
        }
        val fakeSource = Source(
            id = sourceId,
            url = SourceUrl.parse("https://source.example.com")!!,
            isActive = true,
            insecureConnectionAllowed = false,
            username = "u",
            type = sourceType,
        )
        val fakeDaoItem = LibraryItemEntity(
            sourceId = sourceId, id = "item-1", libraryId = "lib",
            title = "T", author = "A", coverUrl = null, readingProgress = 0.3f, addedAt = 0L,
        )
        val fakeLibraryItemDao = object : LibraryItemDao {
            override suspend fun getById(sourceId: String, itemId: String) = fakeDaoItem
            override fun observeByLibraryId(sourceId: String, libraryId: String) = flowOf(emptyList<LibraryItemEntity>())
            override fun observeUngroupedByLibraryId(sourceId: String, libraryId: String) = flowOf(emptyList<LibraryItemEntity>())
            override fun observeInProgress(sourceId: String, libraryId: String) = flowOf(emptyList<LibraryItemEntity>())
            override fun observeFinished(sourceId: String, libraryId: String) = flowOf(emptyList<LibraryItemEntity>())
            override fun observeRecentlyAdded(sourceId: String, libraryId: String) = flowOf(emptyList<LibraryItemEntity>())
            override fun observeAllBooks(sourceId: String, libraryId: String) = flowOf(emptyList<LibraryItemEntity>())
            override fun observeBySource(sourceId: String) = flowOf(emptyList<LibraryItemEntity>())
            override fun observeById(sourceId: String, itemId: String): Flow<LibraryItemEntity?> = flowOf(null)
            override suspend fun upsertAll(items: List<LibraryItemEntity>) = Unit
            override suspend fun insertOrIgnore(items: List<LibraryItemEntity>) = Unit
            override suspend fun updateMetadata(metadata: com.riffle.core.database.LibraryItemMetadata) = Unit
            override suspend fun listByLibraryId(sourceId: String, libraryId: String) = emptyList<LibraryItemEntity>()
            override suspend fun listByIds(sourceId: String, itemIds: List<String>) = emptyList<LibraryItemEntity>()
            override suspend fun findSourceIdForItem(itemId: String): String? = null
            override suspend fun deleteByLibraryId(sourceId: String, libraryId: String) = Unit
            override suspend fun deleteById(sourceId: String, itemId: String) = Unit
            override suspend fun deleteByIds(sourceId: String, itemIds: List<String>) = Unit
            override suspend fun idsForLibrary(sourceId: String, libraryId: String) = emptyList<String>()
            override suspend fun updateLastOpenedAt(sourceId: String, itemId: String, timestamp: Long) = Unit
            override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) = Unit
            override suspend fun updateReadingProgressStamped(sourceId: String, itemId: String, progress: Float, updatedAt: Long) = Unit
            override suspend fun updateReadingProgressFromServer(sourceId: String, itemId: String, progress: Float, serverUpdatedAt: Long) = Unit
            override suspend fun updateLibraryId(sourceId: String, itemId: String, libraryId: String) = Unit
            override suspend fun updateFinishedAt(sourceId: String, itemId: String, finishedAt: Long?) = Unit
            override suspend fun getLastOpenedAtMap(sourceId: String, libraryId: String) = emptyList<com.riffle.core.database.LastOpenedAtRow>()
            override suspend fun getReadingProgressMap(sourceId: String, libraryId: String) = emptyList<com.riffle.core.database.ReadingProgressRow>()
            override suspend fun listMatchableBySourceType(serverType: String) = emptyList<com.riffle.core.database.MatchableItemRow>()
            override fun observeInProgressAllSources() = flowOf(emptyList<LibraryItemEntity>())
            override fun observeAllSources() = flowOf(emptyList<LibraryItemEntity>())
        }
        val fakeTranslatorFactory = object : EbookCfiTranslatorFactory {
            override fun forItem(sourceId: String, itemId: String) = null
        }
        val fakeConfigStore = object : AnnotationSyncConfigStore {
            private val flow = MutableStateFlow(
                webDavBaseUrl?.let { AnnotationSyncConfig(it, "user", "pass") }
            )
            override fun observe(): StateFlow<AnnotationSyncConfig?> = flow
            override suspend fun save(config: AnnotationSyncConfig) {
                flow.value = config
            }
            override suspend fun clear() {
                flow.value = null
            }
        }
        val fakeSourceRepository = object : SourceRepository {
            override fun observeAll(): Flow<List<Source>> = flowOf(listOf(fakeSource))
            override suspend fun getById(sourceId: String) = if (sourceId == fakeSource.id) fakeSource else null
            override suspend fun getActive(): Source? = fakeSource
            override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>) = error("not used")
            override suspend fun setActive(sourceId: String) = Unit
            override suspend fun remove(sourceId: String) = Unit
            override suspend fun getSourceVersion(sourceId: String): String? = null
            override suspend fun ensureSyncNamespace(sourceId: String): SyncNamespace = error("not used")
        }
        val fakeReadingPositionDao = object : ReadingPositionDao {
            override suspend fun getByItemId(sourceId: String, itemId: String): ReadingPositionEntity? = null
            override suspend fun upsert(entity: ReadingPositionEntity) = Unit
            override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) = Unit
            override suspend fun acceptServerIfUnchanged(
                sourceId: String, itemId: String, position: String,
                serverStamp: Long, ifLocalUpdatedAt: Long, deleted: Boolean,
            ) = 0
            override suspend fun confirmPushedIfUnchanged(sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long) = 0
            override suspend fun confirmInSyncIfUnchanged(sourceId: String, itemId: String, ifLocalUpdatedAt: Long) = 0
            override suspend fun dirtyForSource(sourceId: String) = emptyList<ReadingPositionEntity>()
            override suspend fun sourcesWithDirtyRows() = emptyList<String>()
            override suspend fun markDeleted(sourceId: String, itemId: String, localUpdatedAt: Long) = Unit
            override suspend fun acceptServerDeletionIfUnchanged(sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long) = 0
            override suspend fun allForSource(sourceId: String) = emptyList<ReadingPositionEntity>()
        }
        val fakeAudiobookPositionDao = object : AudiobookPositionDao {
            override suspend fun getByItemId(sourceId: String, itemId: String): AudiobookPositionEntity? = null
            override suspend fun upsert(entity: AudiobookPositionEntity) = Unit
            override suspend fun acceptServerIfUnchanged(
                sourceId: String, itemId: String, positionSec: Double,
                serverStamp: Long, ifLocalUpdatedAt: Long, deleted: Boolean,
            ) = 0
            override suspend fun confirmPushedIfUnchanged(sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long) = 0
            override suspend fun confirmInSyncIfUnchanged(sourceId: String, itemId: String, ifLocalUpdatedAt: Long) = 0
            override suspend fun dirtyForSource(sourceId: String) = emptyList<AudiobookPositionEntity>()
            override suspend fun sourcesWithDirtyRows() = emptyList<String>()
            override suspend fun markDeleted(sourceId: String, itemId: String, localUpdatedAt: Long) = Unit
            override suspend fun acceptServerDeletionIfUnchanged(sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long) = 0
            override suspend fun allForSource(sourceId: String) = emptyList<AudiobookPositionEntity>()
        }
        val mockEngine = MockEngine { respond("", HttpStatusCode.OK) }
        val fakeWebDavFactory = WebDavProgressRemoteFactory(
            httpClient = HttpClient(mockEngine),
            dispatchers = IosDispatcherProvider,
        )
        return IosCatalogProgressRemoteFactory(
            catalogRegistry = fakeCatalogRegistry,
            libraryItemDao = fakeLibraryItemDao,
            translatorFactory = fakeTranslatorFactory,
            clock = clock,
            annotationSyncConfigStore = fakeConfigStore,
            webDavProgressRemoteFactory = fakeWebDavFactory,
            sourceRepository = fakeSourceRepository,
            readingPositionDao = fakeReadingPositionDao,
            audiobookPositionDao = fakeAudiobookPositionDao,
        )
    }

    @Test
    fun ebookReturnsNonNullWebDavRemoteForIsWebSourceWhenWebDavConfigured() = runTest {
        val factory = buildFactory(sourceType = SourceType.CHITANKA)
        assertNotNull(factory.ebook("source-1", "item-1"))
    }

    @Test
    fun ebookReturnsNonNullForGutenbergSourceWhenWebDavConfigured() = runTest {
        val factory = buildFactory(sourceType = SourceType.GUTENBERG)
        assertNotNull(factory.ebook("source-1", "item-1"))
    }

    @Test
    fun ebookReturnsNullForIsWebSourceWhenWebDavNotConfigured() = runTest {
        val factory = buildFactory(sourceType = SourceType.CHITANKA, webDavBaseUrl = null)
        assertNull(factory.ebook("source-1", "item-1"))
    }

    @Test
    fun ebookReturnsNullForLocalFilesSourceEvenWhenWebDavConfigured() = runTest {
        val factory = buildFactory(sourceType = SourceType.LOCAL_FILES)
        assertNull(factory.ebook("source-1", "item-1"))
    }

    @Test
    fun audioReturnsNonNullWebDavRemoteForIsWebSourceWhenWebDavConfigured() = runTest {
        val factory = buildFactory(sourceType = SourceType.CHITANKA)
        assertNotNull(factory.audio("source-1", "item-1"))
    }

    @Test
    fun audioReturnsNullForIsWebSourceWhenWebDavNotConfigured() = runTest {
        val factory = buildFactory(sourceType = SourceType.CHITANKA, webDavBaseUrl = null)
        assertNull(factory.audio("source-1", "item-1"))
    }
}
