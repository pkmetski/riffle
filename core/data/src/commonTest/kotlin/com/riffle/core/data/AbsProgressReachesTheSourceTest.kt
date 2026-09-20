package com.riffle.core.data

import com.riffle.core.catalog.CatalogFactory
import com.riffle.core.catalog.DefaultCatalogRegistry
import com.riffle.core.catalog.abs.AbsCommonCatalogFactory
import com.riffle.core.common.Clock
import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.ReadaloudResumePosition
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.ProgressSyncCycleResult
import com.riffle.core.models.SessionPayload
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.NetworkServerProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end regression for #1071 §P0.1 — **reading and listening progress never left the device
 * on iOS**.
 *
 * Every progress write is routed through `CatalogRegistry`:
 * `ReadingSessionRepositoryImpl.runSyncCycle` does `catalogRegistry.forSource(source) ?: return
 * Offline`, and `AudiobookRepositoryImpl.saveProgress` does `catalogRegistry.forSourceId(sourceId)
 * ?: return`. iOS registered a factory for Komga only, so both silently no-opped for every
 * Audiobookshelf source while pull-side resume kept working — sync looked healthy while being
 * one-directional.
 *
 * This test wires the *real* `DefaultCatalogRegistry` over the *real* `AbsCommonCatalogFactory`
 * (the factory iOS now registers) and asserts a PATCH actually reaches `AbsSessionApi`. The
 * paired `…noPushWhenNoAbsFactoryIsRegistered` cases pin the mechanism: with the pre-fix,
 * Komga-only factory map, nothing is sent. Existing suites pass in both worlds because they inject
 * a hand-written peer straight into the repository, bypassing the registry entirely.
 */
class AbsProgressReachesTheSourceTest {

    private val absSource = Source(
        id = "src-abs",
        url = SourceUrl.parse("http://abs.test")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "user",
        type = SourceType.ABS,
    )

    @Test
    fun readerPositionSavePatchesTheAbsSourceWhenTheAbsFactoryIsRegistered() = runTest {
        val session = RecordingSessionApi()
        val positions = FakePositionStore().apply {
            // A dirty local row is what makes the cycle choose LocalWins and push.
            updateLocalTimestamp(absSource.id, ITEM, 5_000L)
        }
        val repository = readingSessionRepository(session, positions, absFactoryRegistered = true)

        val result = repository.runSyncCycle(
            ITEM,
            SessionPayload(ebookLocation = "epubcfi(/6/14!/4/2)", ebookProgress = 0.37f),
        )

        assertEquals(ProgressSyncCycleResult.LocalWins, result)
        assertEquals(1, session.ebookPushes.size, "the reader's position never reached the ABS source")
        val (itemId, payload) = session.ebookPushes.single()
        assertEquals(ITEM, itemId)
        assertEquals("epubcfi(/6/14!/4/2)", payload.ebookLocation)
        assertEquals(0.37f, payload.ebookProgress)
    }

    @Test
    fun readerPositionSaveSendsNothingWhenNoAbsFactoryIsRegistered() = runTest {
        val session = RecordingSessionApi()
        val positions = FakePositionStore().apply { updateLocalTimestamp(absSource.id, ITEM, 5_000L) }
        val repository = readingSessionRepository(session, positions, absFactoryRegistered = false)

        val result = repository.runSyncCycle(
            ITEM,
            SessionPayload(ebookLocation = "epubcfi(/6/14!/4/2)", ebookProgress = 0.37f),
        )

        assertEquals(ProgressSyncCycleResult.Offline, result)
        assertTrue(session.ebookPushes.isEmpty())
    }

    @Test
    fun audiobookPositionSavePatchesTheAbsSourceWhenTheAbsFactoryIsRegistered() = runTest {
        val session = RecordingSessionApi()
        audiobookRepository(session, absFactoryRegistered = true)
            .saveProgress(absSource.id, ITEM, positionSec = 912.0, durationSec = 4_000.0)

        assertEquals(1, session.audiobookPushes.size, "the player's position never reached the ABS source")
        val (itemId, payload) = session.audiobookPushes.single()
        assertEquals(ITEM, itemId)
        assertEquals(912.0, payload.currentTime)
        assertEquals(4_000.0, payload.duration)
    }

    @Test
    fun audiobookPositionSaveSendsNothingWhenNoAbsFactoryIsRegistered() = runTest {
        val session = RecordingSessionApi()
        audiobookRepository(session, absFactoryRegistered = false)
            .saveProgress(absSource.id, ITEM, positionSec = 912.0, durationSec = 4_000.0)

        assertTrue(session.audiobookPushes.isEmpty())
    }

    // MARK: - Wiring

    private fun registry(session: AbsSessionApi, absFactoryRegistered: Boolean) = DefaultCatalogRegistry(
        factories = if (absFactoryRegistered) {
            mapOf<SourceType, CatalogFactory>(
                SourceType.ABS to AbsCommonCatalogFactory(
                    libraryApi = EmptyLibraryApi,
                    sessionApi = session,
                    serverInfoApi = NullServerInfoApi,
                    tokenStorage = FakeTokenStorage,
                    deviceIdStore = FakeDeviceIdStore,
                    clock = FixedClock,
                ),
            )
        } else {
            emptyMap()
        },
        sourceRepository = FakeSourceRepository(absSource),
    )

    private fun readingSessionRepository(
        session: AbsSessionApi,
        positions: FakePositionStore,
        absFactoryRegistered: Boolean,
    ) = ReadingSessionRepositoryImpl(
        catalogRegistry = registry(session, absFactoryRegistered),
        sourceRepository = FakeSourceRepository(absSource),
        positionStore = positions,
        audiobookPositionStore = NoopAudiobookPositionStore,
        readaloudResumeStore = NoopReadaloudResumeStore,
        libraryItemDao = FakeLibraryItemDao(),
        clock = FixedClock,
    )

    private fun audiobookRepository(session: AbsSessionApi, absFactoryRegistered: Boolean) =
        AudiobookRepositoryImpl(registry(session, absFactoryRegistered), FixedClock)

    // MARK: - Fakes

    private class RecordingSessionApi : AbsSessionApi {
        val ebookPushes = mutableListOf<Pair<String, NetworkEbookProgressPayload>>()
        val audiobookPushes = mutableListOf<Pair<String, NetworkAudiobookProgressPayload>>()

        override suspend fun syncEbookProgress(
            baseUrl: String,
            libraryItemId: String,
            payload: NetworkEbookProgressPayload,
            token: String,
            insecureAllowed: Boolean,
        ): NetworkResult<Long> {
            ebookPushes += libraryItemId to payload
            return NetworkResult.Success(6_000L)
        }

        override suspend fun syncAudiobookProgress(
            baseUrl: String,
            libraryItemId: String,
            payload: NetworkAudiobookProgressPayload,
            token: String,
            insecureAllowed: Boolean,
        ): NetworkResult<Long> {
            audiobookPushes += libraryItemId to payload
            return NetworkResult.Success(6_000L)
        }

        override suspend fun getProgress(
            baseUrl: String,
            libraryItemId: String,
            token: String,
            insecureAllowed: Boolean,
        ): NetworkResult<NetworkServerProgress> =
            // A never-touched item: reachable, but nothing stored yet, so the local row wins.
            NetworkResult.Success(NetworkServerProgress(ebookLocation = "", lastUpdate = 0L))
    }

    private object EmptyLibraryApi : AbsLibraryApi {
        override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
            NetworkResult.Success(emptyList<com.riffle.core.network.NetworkLibrary>())

        override suspend fun getLibraryItems(
            baseUrl: String,
            libraryId: String,
            token: String,
            insecureAllowed: Boolean,
        ) = NetworkResult.Success(emptyList<com.riffle.core.network.NetworkLibraryItem>())

        override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
            NetworkResult.Success(emptyList<com.riffle.core.network.NetworkSeries>())

        override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
            NetworkResult.Success(emptyList<com.riffle.core.network.NetworkCollection>())
    }

    private object NullServerInfoApi : AbsServerInfoApi {
        override suspend fun getServerInfo(baseUrl: String, token: String, insecureAllowed: Boolean): String? = null
        override suspend fun getCurrentUserId(baseUrl: String, token: String, insecureAllowed: Boolean): String? = null
    }

    private object FakeTokenStorage : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) {}
        override suspend fun getToken(sourceId: String): String = "tok"
        override suspend fun deleteToken(sourceId: String) {}
    }

    private object FakeDeviceIdStore : DeviceIdStore {
        override suspend fun getOrCreate(): String = "dev-1"
    }

    private object FixedClock : Clock {
        override fun nowMs(): Long = 5_000L
        override fun nowNs(): Long = 5_000L * 1_000_000L
    }

    private class FakeSourceRepository(private val source: Source) : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = emptyFlow()
        override suspend fun getActive(): Source = source
        override suspend fun getById(sourceId: String): Source? = source.takeIf { it.id == sourceId }
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            error("not needed in test")
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private class FakePositionStore : ReadingPositionStore {
        private val payloads = mutableMapOf<Pair<String, String>, String>()
        private val localUpdated = mutableMapOf<Pair<String, String>, Long>()
        private val lastSynced = mutableMapOf<Pair<String, String>, Long>()

        override suspend fun save(sourceId: String, itemId: String, payload: String) {
            payloads[sourceId to itemId] = payload
        }
        override suspend fun load(sourceId: String, itemId: String): String? = payloads[sourceId to itemId]
        override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long =
            localUpdated[sourceId to itemId] ?: 0L
        override suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long =
            lastSynced[sourceId to itemId] ?: 0L
        override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) {
            localUpdated[sourceId to itemId] = millis
        }
        override suspend fun markSyncedAt(sourceId: String, itemId: String, stamp: Long) {
            localUpdated[sourceId to itemId] = stamp
            lastSynced[sourceId to itemId] = stamp
        }
        override suspend fun acceptServer(sourceId: String, itemId: String, payload: String, serverStamp: Long) {
            payloads[sourceId to itemId] = payload
            markSyncedAt(sourceId, itemId, serverStamp)
        }
    }

    private object NoopAudiobookPositionStore : AudiobookPositionStore {
        override suspend fun save(sourceId: String, itemId: String, payload: Double) {}
        override suspend fun load(sourceId: String, itemId: String): Double? = null
        override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) {}
        override suspend fun acceptServer(sourceId: String, itemId: String, payload: Double, serverStamp: Long) {}
        override suspend fun markSyncedAt(sourceId: String, itemId: String, stamp: Long) {}
    }

    private object NoopReadaloudResumeStore : ReadaloudResumeStore {
        override suspend fun save(sourceId: String, itemId: String, position: ReadaloudResumePosition) {}
        override suspend fun load(sourceId: String, itemId: String): ReadaloudResumePosition? = null
        override suspend fun clear(sourceId: String, itemId: String) {}
    }

    private companion object {
        const val ITEM = "item-1"
    }
}
