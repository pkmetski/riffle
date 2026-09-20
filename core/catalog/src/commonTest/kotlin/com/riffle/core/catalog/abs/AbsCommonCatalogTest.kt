package com.riffle.core.catalog.abs

import com.riffle.core.catalog.AudiobookProgressPeerCapability
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CfiDialect
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.catalog.SortKey
import com.riffle.core.common.Clock
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkLibrary
import com.riffle.core.network.NetworkLibraryFolder
import com.riffle.core.network.NetworkLibraryItem
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.NetworkServerProgress
import com.riffle.core.network.NetworkUserMediaProgress
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the ABS Catalog half that every platform runs — the one iOS had no access to at all,
 * because `AbsCatalog` is `jvmMain`-only and iOS registered no ABS `CatalogFactory`
 * (#1071 §P0.1). `AbsCatalogTest` (jvmTest) covers the same contract through `AbsCatalog`, which
 * now delegates these members here; this suite is the counterpart that actually runs on
 * `iosSimulatorArm64Test`.
 *
 * The push assertions below are the ones that flip red if the progress-peer members stop reaching
 * `AbsSessionApi`: they assert the recorded PATCH payload, not just that the call returned.
 */
class AbsCommonCatalogTest {

    private val config = AbsCatalogConfig(
        baseUrl = "http://abs.test",
        token = "tok",
        insecureAllowed = false,
        deviceId = "dev-1",
    )

    private class RecordingSessionApi : AbsSessionApi {
        val ebookPushes = mutableListOf<Triple<String, NetworkEbookProgressPayload, String>>()
        val audiobookPushes = mutableListOf<Triple<String, NetworkAudiobookProgressPayload, String>>()
        var progress: NetworkServerProgress = NetworkServerProgress(ebookLocation = "", lastUpdate = 0L)

        override suspend fun syncEbookProgress(
            baseUrl: String,
            libraryItemId: String,
            payload: NetworkEbookProgressPayload,
            token: String,
            insecureAllowed: Boolean,
        ): NetworkResult<Long> {
            ebookPushes += Triple(libraryItemId, payload, "$baseUrl|$token")
            return NetworkResult.Success(77L)
        }

        override suspend fun syncAudiobookProgress(
            baseUrl: String,
            libraryItemId: String,
            payload: NetworkAudiobookProgressPayload,
            token: String,
            insecureAllowed: Boolean,
        ): NetworkResult<Long> {
            audiobookPushes += Triple(libraryItemId, payload, "$baseUrl|$token")
            return NetworkResult.Success(88L)
        }

        override suspend fun getProgress(
            baseUrl: String,
            libraryItemId: String,
            token: String,
            insecureAllowed: Boolean,
        ): NetworkResult<NetworkServerProgress> = NetworkResult.Success(progress)
    }

    private class FakeLibraryApi(
        private val libraries: List<NetworkLibrary> = emptyList(),
        private val items: List<NetworkLibraryItem> = emptyList(),
        private val userProgress: Map<String, NetworkUserMediaProgress> = emptyMap(),
    ) : AbsLibraryApi {
        override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
            NetworkResult.Success(libraries)

        override suspend fun getLibraryItems(
            baseUrl: String,
            libraryId: String,
            token: String,
            insecureAllowed: Boolean,
        ) = NetworkResult.Success(items.filter { it.libraryId == libraryId })

        override suspend fun getItem(baseUrl: String, itemId: String, token: String, insecureAllowed: Boolean) =
            NetworkResult.Success(items.firstOrNull { it.id == itemId })

        override suspend fun searchLibrary(
            baseUrl: String,
            libraryId: String,
            query: String,
            limit: Int,
            token: String,
            insecureAllowed: Boolean,
        ) = NetworkResult.Success(items.filter { it.title.contains(query, ignoreCase = true) }.take(limit))

        override suspend fun getUserProgress(baseUrl: String, token: String, insecureAllowed: Boolean) =
            NetworkResult.Success(userProgress)

        override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
            NetworkResult.Success(emptyList<com.riffle.core.network.NetworkSeries>())

        override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
            NetworkResult.Success(emptyList<com.riffle.core.network.NetworkCollection>())
    }

    private class FakeServerInfoApi(private val version: String?) : AbsServerInfoApi {
        override suspend fun getServerInfo(baseUrl: String, token: String, insecureAllowed: Boolean): String? = version

        override suspend fun getCurrentUserId(baseUrl: String, token: String, insecureAllowed: Boolean): String? = null
    }

    private class FixedClock(private val value: Long) : Clock {
        override fun nowMs() = value
        override fun nowNs() = value * 1_000_000L
    }

    private fun item(id: String, title: String, libraryId: String = "lib-1") = NetworkLibraryItem(
        id = id,
        libraryId = libraryId,
        title = title,
        author = "Author",
        readingProgress = null,
        ebookFormat = EbookFormat.Epub,
    )

    private fun catalog(
        sessionApi: AbsSessionApi = RecordingSessionApi(),
        libraryApi: AbsLibraryApi = FakeLibraryApi(),
        serverInfoApi: AbsServerInfoApi = FakeServerInfoApi("2.17.0"),
    ) = AbsCommonCatalog(
        config = config,
        libraryApi = libraryApi,
        sessionApi = sessionApi,
        serverInfoApi = serverInfoApi,
        clock = FixedClock(5_000L),
    )

    @Test
    fun isAnAudiobookProgressPeerSoTheRegistryRoutesBothMediaToIt() {
        val c = catalog()
        assertEquals(SourceType.ABS, c.sourceType)
        // Both repository call sites cast to these: ReadingSessionRepositoryImpl to
        // ProgressPeerCapability, AudiobookRepositoryImpl.saveProgress to the audiobook one.
        assertTrue(c is ProgressPeerCapability)
        assertTrue(c is AudiobookProgressPeerCapability)
        assertEquals(CfiDialect.EPUB_JS, c.cfiDialect)
    }

    @Test
    fun pushEbookProgressPatchesTheSourceWithTheLocatorAndFraction() = runTest {
        val session = RecordingSessionApi()
        val stamp = catalog(sessionApi = session).pushEbookProgress(
            itemId = "item-1",
            location = "epubcfi(/6/4!/4/2)",
            progress = 0.42f,
            isFinished = null,
            lastUpdateEpochMs = 123L,
        )
        assertEquals(1, session.ebookPushes.size, "the reader's position never reached the source")
        val (itemId, payload, target) = session.ebookPushes.single()
        assertEquals("item-1", itemId)
        assertEquals("epubcfi(/6/4!/4/2)", payload.ebookLocation)
        assertEquals(0.42f, payload.ebookProgress)
        // null must stay null: a routine position save may not touch the audio dimension of ABS's
        // shared media-progress record.
        assertNull(payload.isFinished)
        assertEquals("http://abs.test|tok", target)
        assertEquals(77L, stamp)
    }

    @Test
    fun pushEbookProgressForwardsAnExplicitFinishedFlag() = runTest {
        val session = RecordingSessionApi()
        catalog(sessionApi = session).pushEbookProgress("item-1", "", 1f, isFinished = true, lastUpdateEpochMs = 1L)
        assertEquals(true, session.ebookPushes.single().second.isFinished)
    }

    @Test
    fun pushAudiobookProgressPatchesCurrentTimeAndDuration() = runTest {
        val session = RecordingSessionApi()
        val stamp = catalog(sessionApi = session).pushAudiobookProgress(
            itemId = "item-9",
            currentTimeSec = 640.5,
            durationSec = 3_600.0,
            isFinished = null,
            lastUpdateEpochMs = 1L,
        )
        assertEquals(1, session.audiobookPushes.size, "the player's position never reached the source")
        val (itemId, payload, _) = session.audiobookPushes.single()
        assertEquals("item-9", itemId)
        assertEquals(640.5, payload.currentTime)
        assertEquals(3_600.0, payload.duration)
        assertEquals(88L, stamp)
    }

    @Test
    fun pullProgressReturnsAReachableEmptyRecordRatherThanNull() = runTest {
        val session = RecordingSessionApi()
        val progress = catalog(sessionApi = session).pullProgress("item-1")
        assertEquals(0L, progress?.lastUpdate)
        assertNull(progress?.ebookLocation)
    }

    @Test
    fun pullProgressMapsANonEmptyRecordAndDerivesFinished() = runTest {
        val session = RecordingSessionApi().apply {
            progress = NetworkServerProgress(
                ebookLocation = "epubcfi(/6/2)",
                ebookProgress = 1f,
                currentTime = 10.0,
                duration = 20.0,
                lastUpdate = 999L,
            )
        }
        val progress = catalog(sessionApi = session).pullProgress("item-1")!!
        assertEquals("epubcfi(/6/2)", progress.ebookLocation)
        assertEquals(1f, progress.ebookProgress)
        assertEquals(999L, progress.lastUpdate)
        assertTrue(progress.isFinished)
    }

    @Test
    fun pullAllProgressCarriesAudioPositionAndFinishedState() = runTest {
        val c = catalog(
            libraryApi = FakeLibraryApi(
                userProgress = mapOf(
                    "a" to NetworkUserMediaProgress(
                        ebookProgress = 0.5f,
                        lastUpdate = 10L,
                        currentTime = 30.0,
                        duration = 60.0,
                    ),
                    "b" to NetworkUserMediaProgress(ebookProgress = null, lastUpdate = 20L, finishedAt = 5L),
                ),
            ),
        )
        val all = c.pullAllProgress().associateBy { it.itemId }
        assertEquals(30.0, all.getValue("a").audioCurrentTime)
        assertEquals(60.0, all.getValue("a").audioDuration)
        assertTrue(all.getValue("b").isFinished)
        assertEquals(0f, all.getValue("b").ebookProgress)
    }

    @Test
    fun listRootsAndBrowseAreServedOverAbsLibraryApi() = runTest {
        val c = catalog(
            libraryApi = FakeLibraryApi(
                libraries = listOf(
                    NetworkLibrary(
                        id = "lib-1",
                        name = "Books",
                        mediaType = "book",
                        audiobooksOnly = false,
                        folders = listOf(NetworkLibraryFolder(id = "folder-1", fullPath = "/books")),
                    ),
                ),
                items = listOf(item("i2", "Zeta"), item("i1", "Alpha")),
            ),
        )
        val roots = c.listRoots()
        assertEquals(listOf("lib-1"), roots.map { it.id })
        assertEquals("folder-1", roots.single().importFolderId)

        val browsed = c.browse("lib-1", SortKey.TITLE)
        assertEquals(listOf("Alpha", "Zeta"), browsed.map { it.title })
        assertEquals(BookFormat.Epub, browsed.first().ebookFormat)
        assertTrue(browsed.first().coverUrl!!.startsWith("http://abs.test"))

        assertEquals(listOf("i1"), c.search("lib-1", "Alph").map { it.id })
        assertEquals("Zeta", c.getItem("i2")?.title)
        assertNull(c.getItem("nope"))
    }

    @Test
    fun browseRefusesTheLocalOnlyRecentlyOpenedOrderingInsteadOfSilentlySortingByTitle() = runTest {
        assertFailsWith<CatalogException.UnsupportedFormat> {
            catalog().browse("lib-1", SortKey.RECENTLY_OPENED)
        }
    }

    @Test
    fun connectivityCheckReportsTheSourceVersion() = runTest {
        assertTrue(catalog().connectivityCheck().isReachable)
        assertEquals("2.17.0", catalog().connectivityCheck().serverVersion)
        assertTrue(catalog(serverInfoApi = FakeServerInfoApi(null)).connectivityCheck().isReachable.not())
    }

    @Test
    fun fileTransferMembersRefuseExplicitlyRatherThanReturningAHandleThatCannotWork() = runTest {
        // ABS ebook bytes come from AbsFileDownloadApi, which is JVM-only. Returning a plausible
        // CatalogFileHandle here would 404 at read time far from the cause.
        assertFailsWith<CatalogException.UnsupportedOperation> {
            catalog().fetchFile("item-1", BookFormat.Epub)
        }
        assertFailsWith<CatalogException.UnsupportedOperation> {
            catalog().withFileStream("item-1", BookFormat.Epub) { Unit }
        }
    }

    @Test
    fun factoryBuildsAnAudiobookProgressPeerAndReturnsNullWithoutAToken() = runTest {
        val source = Source(
            id = "src-1",
            url = SourceUrl.parse("http://abs.test")!!,
            isActive = true,
            insecureConnectionAllowed = false,
            username = "u",
            type = SourceType.ABS,
        )
        val withToken = factory(FakeTokenStorage("tok")).create(source)
        assertTrue(withToken is AudiobookProgressPeerCapability)
        assertEquals(SourceType.ABS, withToken.sourceType)
        assertNull(factory(FakeTokenStorage(null)).create(source))
    }

    private fun factory(tokenStorage: TokenStorage) = AbsCommonCatalogFactory(
        libraryApi = FakeLibraryApi(),
        sessionApi = RecordingSessionApi(),
        serverInfoApi = FakeServerInfoApi("2.17.0"),
        tokenStorage = tokenStorage,
        deviceIdStore = object : DeviceIdStore {
            override suspend fun getOrCreate() = "dev-1"
        },
        clock = FixedClock(1L),
    )

    private class FakeTokenStorage(private val token: String?) : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) {}
        override suspend fun getToken(sourceId: String): String? = token
        override suspend fun deleteToken(sourceId: String) {}
    }
}
