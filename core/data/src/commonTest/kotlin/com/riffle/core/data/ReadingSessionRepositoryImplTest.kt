package com.riffle.core.data

import com.riffle.core.catalog.AudiobookProgressPeerCapability
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
        val syncedAt: MutableMap<Pair<String, String>, Long> = mutableMapOf()
        override suspend fun save(sourceId: String, itemId: String, payload: Double) { saved[sourceId to itemId] = payload }
        override suspend fun load(sourceId: String, itemId: String): Double? = saved[sourceId to itemId]
        override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) {}
        override suspend fun acceptServer(sourceId: String, itemId: String, payload: Double, serverStamp: Long) {}
        override suspend fun markSyncedAt(sourceId: String, itemId: String, stamp: Long) { syncedAt[sourceId to itemId] = stamp }
    }

    private class FakeReadaloudResumeStore : ReadaloudResumeStore {
        var cleared: Boolean = false
        override suspend fun save(sourceId: String, itemId: String, position: ReadaloudResumePosition) {}
        override suspend fun load(sourceId: String, itemId: String): ReadaloudResumePosition? = null
        override suspend fun clear(sourceId: String, itemId: String) { cleared = true }
    }

    /** Implements both Catalog and ProgressPeerCapability so the impl's `catalog as? ProgressPeerCapability` succeeds. */
    private open class RecordingProgressPeer(
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

    /** Like [RecordingProgressPeer] but also implements [AudiobookProgressPeerCapability]. */
    private class RecordingAudioProgressPeer(pullResult: CatalogProgress? = null) :
        RecordingProgressPeer(pullResult), AudiobookProgressPeerCapability {
        val audioPushed: MutableList<Pair<String, Double>> = mutableListOf()
        var audioPushStamp: Long = 2_000L

        override suspend fun pushAudiobookProgress(
            itemId: String,
            currentTimeSec: Double,
            durationSec: Double,
            isFinished: Boolean?,
            lastUpdateEpochMs: Long,
        ): Long {
            audioPushed += itemId to currentTimeSec
            return audioPushStamp
        }
    }

    private class FakeCatalogRegistry(private val peer: Catalog?) : CatalogRegistry {
        override suspend fun forActive(): Catalog? = peer
        override suspend fun forSource(source: Source): Catalog? = peer
        override suspend fun forSourceId(sourceId: String): Catalog? = peer
    }

    private class FakeLibraryItemDao : LibraryItemDao {
        /** Seed per-item state for getById lookups. Key = itemId. */
        val items: MutableMap<String, LibraryItemEntity> = mutableMapOf()
        val finishedAtMap: MutableMap<String, Long?> = mutableMapOf()
        val readingProgressMap: MutableMap<String, Float> = mutableMapOf()
        override suspend fun updateFinishedAt(sourceId: String, itemId: String, finishedAt: Long?) {
            finishedAtMap[itemId] = finishedAt
        }
        override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) {
            readingProgressMap[itemId] = progress
        }
        override suspend fun updateReadingProgressStamped(sourceId: String, itemId: String, progress: Float, updatedAt: Long) {
            readingProgressMap[itemId] = progress
        }
        override suspend fun updateReadingProgressFromServer(sourceId: String, itemId: String, progress: Float, serverUpdatedAt: Long) {
            readingProgressMap[itemId] = progress
        }
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
        override suspend fun getById(sourceId: String, itemId: String): LibraryItemEntity? = items[itemId]
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

    private fun fakeItem(itemId: String, readingProgress: Float, finishedAt: Long? = null) =
        LibraryItemEntity(
            sourceId = activeSource.id,
            id = itemId,
            libraryId = "lib-1",
            title = "Test Book",
            author = "Author",
            coverUrl = null,
            readingProgress = readingProgress,
            addedAt = 0L,
            finishedAt = finishedAt,
        )

    private fun makeRepo(
        peer: Catalog? = null,
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
    fun markReadStampsItemAsFinishedPushesProgress1AndClearsAllLocalStores() = runTest {
        val peer = RecordingProgressPeer()
        val posStore = FakeReadingPositionStore().also { it.save("src-1", "item-1", "epubcfi") }
        val audioStore = FakeAudiobookPositionStore().also { it.save("src-1", "item-1", 120.0) }
        val resumeStore = FakeReadaloudResumeStore()
        val itemDao = FakeLibraryItemDao()
        val repo = makeRepo(peer = peer, positionStore = posStore, audiobookStore = audioStore, readaloudResumeStore = resumeStore, itemDao = itemDao)

        repo.markFinished("item-1", true)

        assertTrue(peer.pushed.isNotEmpty(), "markFinished(true) must push to ABS peer")
        assertEquals(1.0f, peer.pushed.last().third, "progress must be 1.0 when marking finished")
        assertTrue(itemDao.finishedAtMap.containsKey("item-1"))
        assertTrue(itemDao.finishedAtMap["item-1"] != null, "finishedAt must be non-null when marked finished")
        // DB must be immediately updated so the library grid shows 100% before the next pullAllProgress.
        assertEquals(1.0f, itemDao.readingProgressMap["item-1"], "readingProgress must be written to 1.0 immediately")
        // All local position stores must be cleared so the reader opens from the beginning and the
        // detail screen does not show a stale "current" position that contradicts the finished state.
        val savedLocator = posStore.saved["src-1" to "item-1"]
        assertTrue(savedLocator == null || savedLocator.isEmpty(), "ebook locator must be cleared on mark-as-read")
        val savedAudio = audioStore.saved["src-1" to "item-1"]
        assertTrue(savedAudio == null || savedAudio == 0.0, "audiobook position must be reset on mark-as-read")
        assertTrue(resumeStore.cleared, "readaloud resume store must be cleared on mark-as-read")
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
        // DB must be immediately updated to 0 so the library grid removes the book from "In Progress"
        // before the next pullAllProgress. Without this, the grid stays at the old value until the
        // sweeper makes the row clean AND the post-loop has run.
        assertEquals(0.0f, itemDao.readingProgressMap["item-1"], "readingProgress must be written to 0.0 immediately")
    }

    @Test
    fun markUnreadPushesAudioCurrentTimeZeroImmediatelyAndMarksRowClean() = runTest {
        // Regression: the sweeper's reconciler previously called confirmInSync (not LocalPushed)
        // for the audio row because the ebook PATCH timestamp equalled the local-save timestamp.
        // The audio row became clean without ever pushing currentTime=0 to ABS. A subsequent
        // refreshItemProgress then re-adopted ABS's stale non-zero position and wrote it back to
        // the DB, restoring the cancelled progress. The fix: markFinished(false) now pushes
        // currentTime=0 via AudiobookProgressPeerCapability immediately and calls markSyncedAt.
        val peer = RecordingAudioProgressPeer()
        val audioStore = FakeAudiobookPositionStore().also { it.save("src-1", "item-1", 27000.0) }
        val itemDao = FakeLibraryItemDao()
        val repo = makeRepo(peer = peer, audiobookStore = audioStore, itemDao = itemDao)

        repo.markFinished("item-1", false)

        assertEquals(1, peer.audioPushed.size, "audio push must fire once on mark-as-unread")
        assertEquals("item-1", peer.audioPushed[0].first)
        assertEquals(0.0, peer.audioPushed[0].second, "audio push must send currentTime=0")
        // The audio row must be marked clean so the sweeper does not stumble into confirmInSync
        // without pushing, which would leave ABS with the stale position.
        assertTrue(audioStore.syncedAt.containsKey("src-1" to "item-1"), "audio row must be marked synced after push")
    }

    @Test
    fun markReadDoesNotPushSeparateAudioCurrentTimeZero() = runTest {
        // For mark-as-read the ebook PATCH with isFinished=true already resets the whole record
        // on ABS. Pushing a separate currentTime=0 would cause a spurious audio reset on top.
        val peer = RecordingAudioProgressPeer()
        val repo = makeRepo(peer = peer)

        repo.markFinished("item-1", true)

        assertTrue(peer.audioPushed.isEmpty(), "mark-as-read must not push a separate audio 0")
    }

    @Test
    fun markReadMarksAudioRowCleanSoTheSweepDoesNotUnFinishTheBook() = runTest {
        // Regression: markFinished(true) resets the local audio position to 0.0, which marks the
        // audio row dirty (localUpdatedAt > lastSyncedAt) whenever the prior position was non-zero.
        // The ebook PATCH with isFinished=true already reset the audio half of the shared server
        // record, so the local audio row must be marked clean at that stamp. Without this, the
        // durable sweep sees a dirty currentTime=0 row and pushes it with isFinished=false, un-
        // finishing the very book the user just marked read.
        val peer = RecordingAudioProgressPeer()
        val audioStore = FakeAudiobookPositionStore().also { it.save("src-1", "item-1", 27000.0) }
        val repo = makeRepo(peer = peer, audiobookStore = audioStore)

        repo.markFinished("item-1", true)

        assertTrue(
            audioStore.syncedAt.containsKey("src-1" to "item-1"),
            "mark-as-read must mark the audio row clean so the sweep does not re-push currentTime=0",
        )
        assertTrue(peer.audioPushed.isEmpty(), "mark-as-read still must not push a separate audio 0")
    }

    // MARK: - Finished-book browse regression (#new)

    @Test
    fun runSyncCycleDoesNotPushCoverPositionOfFinishedBook() = runTest {
        // Regression: opening a marked-as-read book pushed ebookProgress~0 from the cover, and
        // pullAllProgress (isFinished derived from ebookProgress) then dropped it out of Completed.
        val posStore = FakeReadingPositionStore().also {
            it.save("src-1", "item-1", "locator-cover")
            it.updateLocalTimestamp("src-1", "item-1", 3_000L)
            it.markSyncedAt("src-1", "item-1", 1_000L)
        }
        val peer = RecordingProgressPeer(
            pullResult = CatalogProgress(itemId = "item-1", ebookLocation = "", ebookProgress = 1.0f, lastUpdate = 1_000L),
        )
        val itemDao = FakeLibraryItemDao().also {
            it.items["item-1"] = fakeItem("item-1", readingProgress = 1.0f, finishedAt = 500L)
        }
        val result = makeRepo(peer = peer, positionStore = posStore, itemDao = itemDao, clock = FakeClock(2_000L))
            .runSyncCycle("item-1", SessionPayload("locator-cover", 0.004f))

        assertEquals(ProgressSyncCycleResult.InSync, result, "a cover-only visit must not be pushed as LocalWins")
        assertTrue(peer.pushed.isEmpty(), "a cover-only visit must not push ~0 progress for a finished book")
    }

    @Test
    fun runSyncCycleUnfinishesBookWhenReadingPastTheCover() = runTest {
        // Regression: reading chapter 6 of a marked-as-read book left both progress bars at 100%.
        val posStore = FakeReadingPositionStore().also {
            it.save("src-1", "item-1", "locator-ch06")
            it.updateLocalTimestamp("src-1", "item-1", 3_000L)
            it.markSyncedAt("src-1", "item-1", 1_000L)
        }
        val peer = RecordingProgressPeer(
            pullResult = CatalogProgress(itemId = "item-1", ebookLocation = "", ebookProgress = 1.0f, lastUpdate = 1_000L),
        )
        val itemDao = FakeLibraryItemDao().also {
            it.items["item-1"] = fakeItem("item-1", readingProgress = 1.0f, finishedAt = 500L)
        }
        val result = makeRepo(peer = peer, positionStore = posStore, itemDao = itemDao, clock = FakeClock(2_000L))
            .runSyncCycle("item-1", SessionPayload("locator-ch06", 0.4f))

        assertEquals(ProgressSyncCycleResult.LocalWins, result)
        assertEquals(1, peer.pushed.size, "real reading of a finished book must push its fraction")
        assertEquals(0.4f, peer.pushed.single().third)
    }
    @Test
    fun runSyncCycleWithholdsServerJumpForFinishedServerRecordWithoutLocation() = runTest {
        // Regression: mark-as-read leaves ABS with location="" + ebookProgress=1.0. Surfacing that
        // as ServerWins made the EPUB reader fall back to locateProgression(1.0) and open a freshly
        // marked-read book at the back cover ("Progress 99%").
        val posStore = FakeReadingPositionStore().also {
            it.save("src-1", "item-1", "")
            it.updateLocalTimestamp("src-1", "item-1", 3_000L)
            it.markSyncedAt("src-1", "item-1", 1_000L)
        }
        val peer = RecordingProgressPeer(
            pullResult = CatalogProgress(itemId = "item-1", ebookLocation = null, ebookProgress = 1.0f, lastUpdate = 5_000L),
        )
        val result = makeRepo(peer = peer, positionStore = posStore, clock = FakeClock(6_000L))
            .runSyncCycle("item-1", SessionPayload("", 0f))

        assertEquals(ProgressSyncCycleResult.InSync, result, "a finished server record must not be surfaced as a ServerWins jump")
        assertEquals(5_000L, posStore.loadLastSyncedAt("src-1", "item-1"), "the server stamp must still be adopted so the row is clean")
        assertTrue(peer.pushed.isEmpty(), "nothing must be pushed")
    }

    @Test
    fun runSyncCycleSurfacesServerWinsForFinishedServerRecordCarryingLocator() = runTest {
        // A finished record that still carries a locator was read there on another device —
        // that is a real position, unlike the location-less mark-as-read reset above.
        val posStore = FakeReadingPositionStore().also {
            it.save("src-1", "item-1", "")
            it.updateLocalTimestamp("src-1", "item-1", 3_000L)
            it.markSyncedAt("src-1", "item-1", 1_000L)
        }
        val peer = RecordingProgressPeer(
            pullResult = CatalogProgress(itemId = "item-1", ebookLocation = "locator-last-page", ebookProgress = 1.0f, lastUpdate = 5_000L),
        )
        val result = makeRepo(peer = peer, positionStore = posStore, clock = FakeClock(6_000L))
            .runSyncCycle("item-1", SessionPayload("", 0f))

        assertTrue(result is ProgressSyncCycleResult.ServerWins)
        assertEquals("locator-last-page", posStore.load("src-1", "item-1"))
    }
    @Test
    fun runSyncCycleStillSurfacesServerWinsForUnfinishedServerRecord() = runTest {
        val posStore = FakeReadingPositionStore().also {
            it.save("src-1", "item-1", "old")
            it.updateLocalTimestamp("src-1", "item-1", 3_000L)
            it.markSyncedAt("src-1", "item-1", 1_000L)
        }
        val peer = RecordingProgressPeer(
            pullResult = CatalogProgress(itemId = "item-1", ebookLocation = "locator-ch05", ebookProgress = 0.42f, lastUpdate = 5_000L),
        )
        val result = makeRepo(peer = peer, positionStore = posStore, clock = FakeClock(6_000L))
            .runSyncCycle("item-1", SessionPayload("old", 0.1f))

        assertTrue(result is ProgressSyncCycleResult.ServerWins, "an unfinished newer server record must still jump the reader")
    }

    @Test
    fun syncProgressDoesNotPushCoverPositionOfFinishedBookButPushesRealReading() = runTest {
        val peer = RecordingProgressPeer()
        val itemDao = FakeLibraryItemDao().also {
            it.items["item-1"] = fakeItem("item-1", readingProgress = 1.0f, finishedAt = 500L)
        }
        val repo = makeRepo(peer = peer, itemDao = itemDao)

        assertTrue(repo.syncProgress("item-1", SessionPayload("locator-cover", 0.0f)) is SyncSessionResult.Success)
        assertTrue(peer.pushed.isEmpty(), "cover-only visit must not push ~0 progress for a finished book")

        assertTrue(repo.syncProgress("item-1", SessionPayload("locator-ch06", 0.4f)) is SyncSessionResult.Success)
        assertEquals(0.4f, peer.pushed.single().third, "reading past the cover must push the real fraction")
    }
}
