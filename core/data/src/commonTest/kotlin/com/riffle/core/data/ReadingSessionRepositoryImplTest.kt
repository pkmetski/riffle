package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogProgress
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.catalog.SortKey
import com.riffle.core.common.Clock
import com.riffle.core.database.LastOpenedAtRow
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LibraryItemMetadata
import com.riffle.core.database.MatchableItemRow
import com.riffle.core.database.ReadingProgressRow
import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.ReadaloudResumePosition
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.ProgressSyncCycleResult
import com.riffle.core.models.SessionPayload
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.models.SyncSessionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the platform-shared [ReadingSessionRepositoryImpl] (commonMain) with in-memory fakes.
 * Android parity: ProgressSyncCycleTest (core/data/src/androidHostTest) covers Room-backed
 * integration. These tests cover the pure sync-cycle logic.
 */
class ReadingSessionRepositoryImplTest {

    private val activeSource = Source(
        id = "src-1",
        url = SourceUrl.parse("http://abs.test")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "user",
        type = SourceType.ABS,
    )

    // MARK: - Fakes

    private class FakeClock(var nowMs: Long = 1_000L) : Clock {
        override fun nowMs() = nowMs
        override fun nowNs() = nowMs * 1_000_000L
    }

    private class FakeSourceRepository(private val source: Source?) : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = emptyFlow()
        override suspend fun getActive(): Source? = source
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            error("not needed in test")
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private class FakeReadingPositionStore : ReadingPositionStore {
        val saved: MutableMap<Pair<String, String>, String> = mutableMapOf()
        private val localUpdated: MutableMap<Pair<String, String>, Long> = mutableMapOf()
        private val lastSynced: MutableMap<Pair<String, String>, Long> = mutableMapOf()

        override suspend fun save(sourceId: String, itemId: String, payload: String) {
            saved[sourceId to itemId] = payload
        }
        override suspend fun load(sourceId: String, itemId: String): String? =
            saved[sourceId to itemId]
        override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long =
            localUpdated[sourceId to itemId] ?: 0L
        override suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long =
            lastSynced[sourceId to itemId] ?: 0L
        override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) {
            localUpdated[sourceId to itemId] = millis
        }
        override suspend fun acceptServer(sourceId: String, itemId: String, payload: String, serverStamp: Long) {
            saved[sourceId to itemId] = payload
            localUpdated[sourceId to itemId] = serverStamp
            lastSynced[sourceId to itemId] = serverStamp
        }
        override suspend fun markSyncedAt(sourceId: String, itemId: String, stamp: Long) {
            val current = localUpdated[sourceId to itemId] ?: 0L
            localUpdated[sourceId to itemId] = maxOf(current, stamp)
            lastSynced[sourceId to itemId] = stamp
        }
    }

    private class FakeAudiobookPositionStore : AudiobookPositionStore {
        val saved: MutableMap<Pair<String, String>, Double> = mutableMapOf()
        override suspend fun save(sourceId: String, itemId: String, payload: Double) { saved[sourceId to itemId] = payload }
        override suspend fun load(sourceId: String, itemId: String): Double? = saved[sourceId to itemId]
        override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) {}
        override suspend fun acceptServer(sourceId: String, itemId: String, payload: Double, serverStamp: Long) {}
        override suspend fun markSyncedAt(sourceId: String, itemId: String, stamp: Long) {}
    }

    private class FakeReadaloudResumeStore : ReadaloudResumeStore {
        var cleared: Boolean = false
        override suspend fun save(sourceId: String, itemId: String, position: ReadaloudResumePosition) {}
        override suspend fun load(sourceId: String, itemId: String): ReadaloudResumePosition? = null
        override suspend fun clear(sourceId: String, itemId: String) { cleared = true }
    }

    /** Implements both Catalog and ProgressPeerCapability so the impl's `catalog as? ProgressPeerCapability` succeeds. */
    private class RecordingProgressPeer(
        private val pullResult: CatalogProgress? = null,
    ) : Catalog, ProgressPeerCapability {
        val pushed: MutableList<Triple<String, String, Float>> = mutableListOf()

        // Catalog
        override val sourceType: SourceType = SourceType.ABS
        override suspend fun listRoots(): List<CatalogRoot> = emptyList()
        override suspend fun browse(rootId: String, sort: SortKey, page: Int, pageSize: Int, facet: FacetSelection?): List<CatalogItem> = emptyList()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int): List<CatalogItem> = emptyList()
        override suspend fun getItem(itemId: String): CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle = error("not needed in test")
        override suspend fun <T> withFileStream(itemId: String, format: BookFormat, handleHint: String?, block: suspend (CatalogFileStream) -> T): T = error("not needed in test")
        override suspend fun connectivityCheck(): CatalogHealth = error("not needed in test")

        // ProgressPeerCapability
        override suspend fun pushEbookProgress(
            itemId: String,
            location: String,
            progress: Float,
            isFinished: Boolean?,
            lastUpdateEpochMs: Long,
        ): Long? {
            pushed += Triple(itemId, location, progress)
            return lastUpdateEpochMs + 1
        }
        override suspend fun pullProgress(itemId: String): CatalogProgress? = pullResult
        override suspend fun pullAllProgress(): List<CatalogProgress> = listOfNotNull(pullResult)
    }

    private class FakeCatalogRegistry(private val peer: RecordingProgressPeer?) : CatalogRegistry {
        override suspend fun forActive(): Catalog? = peer
        override suspend fun forSource(source: Source): Catalog? = peer
        override suspend fun forSourceId(sourceId: String): Catalog? = peer
    }

    private class FakeLibraryItemDao : LibraryItemDao {
        val finishedAtMap: MutableMap<String, Long?> = mutableMapOf()
        override suspend fun updateFinishedAt(sourceId: String, itemId: String, finishedAt: Long?) {
            finishedAtMap[itemId] = finishedAt
        }
        override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) {}
        override suspend fun updateLastOpenedAt(sourceId: String, itemId: String, timestamp: Long) {}
        // Stub all other abstract DAO methods
        override fun observeByLibraryId(sourceId: String, libraryId: String) = emptyFlow<List<LibraryItemEntity>>()
        override suspend fun listByLibraryId(sourceId: String, libraryId: String): List<LibraryItemEntity> = emptyList()
        override fun observeBySource(sourceId: String) = emptyFlow<List<LibraryItemEntity>>()
        override fun observeUngroupedByLibraryId(sourceId: String, libraryId: String) = emptyFlow<List<LibraryItemEntity>>()
        override suspend fun upsertAll(items: List<LibraryItemEntity>) {}
        override suspend fun insertOrIgnore(items: List<LibraryItemEntity>) {}
        override suspend fun updateMetadata(metadata: LibraryItemMetadata) {}
        override suspend fun deleteByIds(sourceId: String, itemIds: List<String>) {}
        override suspend fun idsForLibrary(sourceId: String, libraryId: String): List<String> = emptyList()
        override suspend fun getById(sourceId: String, itemId: String): LibraryItemEntity? = null
        override suspend fun listByIds(sourceId: String, itemIds: List<String>): List<LibraryItemEntity> = emptyList()
        override fun observeById(sourceId: String, itemId: String) = emptyFlow<LibraryItemEntity?>()
        override suspend fun findSourceIdForItem(itemId: String): String? = null
        override suspend fun deleteByLibraryId(sourceId: String, libraryId: String) {}
        override suspend fun deleteById(sourceId: String, itemId: String) {}
        override fun observeInProgress(sourceId: String, libraryId: String) = emptyFlow<List<LibraryItemEntity>>()
        override fun observeInProgressAllSources() = emptyFlow<List<LibraryItemEntity>>()
        override fun observeFinished(sourceId: String, libraryId: String) = emptyFlow<List<LibraryItemEntity>>()
        override fun observeRecentlyAdded(sourceId: String, libraryId: String) = emptyFlow<List<LibraryItemEntity>>()
        override fun observeAllBooks(sourceId: String, libraryId: String) = emptyFlow<List<LibraryItemEntity>>()
        override suspend fun updateLibraryId(sourceId: String, itemId: String, libraryId: String) {}
        override suspend fun getLastOpenedAtMap(sourceId: String, libraryId: String): List<LastOpenedAtRow> = emptyList()
        override suspend fun getReadingProgressMap(sourceId: String, libraryId: String): List<ReadingProgressRow> = emptyList()
        override suspend fun listMatchableBySourceType(serverType: String): List<MatchableItemRow> = emptyList()
    }

    private fun makeRepo(
        peer: RecordingProgressPeer? = null,
        positionStore: FakeReadingPositionStore = FakeReadingPositionStore(),
        audiobookStore: FakeAudiobookPositionStore = FakeAudiobookPositionStore(),
        readaloudResumeStore: FakeReadaloudResumeStore = FakeReadaloudResumeStore(),
        itemDao: FakeLibraryItemDao = FakeLibraryItemDao(),
        clock: FakeClock = FakeClock(),
        source: Source? = activeSource,
    ) = ReadingSessionRepositoryImpl(
        catalogRegistry = FakeCatalogRegistry(peer),
        sourceRepository = FakeSourceRepository(source),
        positionStore = positionStore,
        audiobookPositionStore = audiobookStore,
        readaloudResumeStore = readaloudResumeStore,
        libraryItemDao = itemDao,
        clock = clock,
    )

    // MARK: - syncProgress

    @Test
    fun `syncProgress pushes to peer and returns Success`() = runTest {
        val peer = RecordingProgressPeer()
        val repo = makeRepo(peer = peer)
        val result = repo.syncProgress("item-1", SessionPayload("locator", 0.5f))
        assertTrue(result is SyncSessionResult.Success)
        assertEquals(1, peer.pushed.size)
        assertEquals("item-1", peer.pushed[0].first)
    }

    @Test
    fun `syncProgress returns NetworkError when no source is active`() = runTest {
        val repo = makeRepo(source = null)
        val result = repo.syncProgress("item-1", SessionPayload("locator", 0.5f))
        assertTrue(result is SyncSessionResult.NetworkError)
    }

    // MARK: - runSyncCycle

    @Test
    fun `runSyncCycle returns InSync when local and server are in sync`() = runTest {
        // localUpdatedAt == lastSyncedAt == 0, serverLastUpdate == 0 → neither side is dirty
        val peer = RecordingProgressPeer(pullResult = CatalogProgress(itemId = "item-1", ebookLocation = "loc", ebookProgress = 0.5f, lastUpdate = 0L))
        val repo = makeRepo(peer = peer)
        val result = repo.runSyncCycle("item-1", SessionPayload("loc", 0.5f))
        assertEquals(ProgressSyncCycleResult.InSync, result)
    }

    @Test
    fun `runSyncCycle returns Offline when no source is configured`() = runTest {
        val repo = makeRepo(source = null)
        val result = repo.runSyncCycle("item-1", SessionPayload("loc", 0.5f))
        assertEquals(ProgressSyncCycleResult.Offline, result)
    }

    @Test
    fun `runSyncCycle returns Offline when no peer capability`() = runTest {
        val repo = makeRepo(peer = null)
        val result = repo.runSyncCycle("item-1", SessionPayload("loc", 0.5f))
        assertEquals(ProgressSyncCycleResult.Offline, result)
    }

    @Test
    fun `runSyncCycle returns LocalWins when local is ahead of server`() = runTest {
        val posStore = FakeReadingPositionStore().also {
            it.save("src-1", "item-1", "local-loc")
            it.updateLocalTimestamp("src-1", "item-1", 500L)
            // lastSyncedAt stays 0 → localDirty = true (500 > 0), serverLastUpdate = 0 < localUpdatedAt 500
        }
        val peer = RecordingProgressPeer(pullResult = CatalogProgress(itemId = "item-1", ebookLocation = "server-loc", lastUpdate = 0L))
        val repo = makeRepo(peer = peer, positionStore = posStore, clock = FakeClock(nowMs = 1000L))
        val result = repo.runSyncCycle("item-1", SessionPayload("local-loc", 0.3f))
        assertEquals(ProgressSyncCycleResult.LocalWins, result)
        assertTrue(peer.pushed.isNotEmpty(), "LocalWins must push local progress to server")
    }

    @Test
    fun `runSyncCycle returns ServerWins when server is ahead of local`() = runTest {
        val peer = RecordingProgressPeer(
            pullResult = CatalogProgress(itemId = "item-1", ebookLocation = "server-loc", ebookProgress = 0.8f, lastUpdate = 900L),
        )
        // lastSyncedAt=0, localUpdatedAt=0 → serverAdvanced (900>0), localNotDirty → ServerWins
        val result = makeRepo(peer = peer).runSyncCycle("item-1", SessionPayload("local-loc", 0.1f))
        assertTrue(result is ProgressSyncCycleResult.ServerWins)
        assertEquals("server-loc", (result as ProgressSyncCycleResult.ServerWins).serverProgress.ebookLocation)
    }

    // MARK: - markFinished

    @Test
    fun `markFinished true stamps the item as finished and pushes progress 1`() = runTest {
        val peer = RecordingProgressPeer()
        val posStore = FakeReadingPositionStore().also { it.save("src-1", "item-1", "epubcfi") }
        val itemDao = FakeLibraryItemDao()
        val repo = makeRepo(peer = peer, positionStore = posStore, itemDao = itemDao)

        repo.markFinished("item-1", true)

        assertTrue(peer.pushed.isNotEmpty(), "markFinished(true) must push to ABS peer")
        assertEquals(1.0f, peer.pushed.last().third, "progress must be 1.0 when marking finished")
        assertTrue(itemDao.finishedAtMap.containsKey("item-1"))
        assertTrue(itemDao.finishedAtMap["item-1"] != null, "finishedAt must be non-null when marked finished")
    }

    @Test
    fun `markFinished false clears local stores and pushes progress 0`() = runTest {
        val peer = RecordingProgressPeer()
        val posStore = FakeReadingPositionStore().also { it.save("src-1", "item-1", "epubcfi") }
        val audioStore = FakeAudiobookPositionStore().also { it.save("src-1", "item-1", 120.0) }
        val resumeStore = FakeReadaloudResumeStore()
        val itemDao = FakeLibraryItemDao()
        val repo = makeRepo(peer = peer, positionStore = posStore, audiobookStore = audioStore, readaloudResumeStore = resumeStore, itemDao = itemDao)

        repo.markFinished("item-1", false)

        val savedLocator = posStore.saved["src-1" to "item-1"]
        assertTrue(savedLocator == null || savedLocator.isEmpty(), "ebook locator must be cleared on unread")
        val savedAudio = audioStore.saved["src-1" to "item-1"]
        assertTrue(savedAudio == null || savedAudio == 0.0, "audiobook position must be reset on unread")
        assertTrue(resumeStore.cleared, "readaloud resume store must be cleared on unread")
        assertTrue(peer.pushed.isNotEmpty(), "markFinished(false) must push to ABS peer")
        assertEquals(0.0f, peer.pushed.last().third, "progress must be 0.0 when marking unread")
        assertTrue(itemDao.finishedAtMap.containsKey("item-1"))
        assertNull(itemDao.finishedAtMap["item-1"], "finishedAt must be null when marking unread")
    }
}
