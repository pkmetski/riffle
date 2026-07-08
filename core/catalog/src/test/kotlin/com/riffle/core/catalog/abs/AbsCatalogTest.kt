package com.riffle.core.catalog.abs

import com.riffle.core.catalog.AudiobookMediaCapability
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CollectionsCapability
import com.riffle.core.catalog.OfflineBrowseCapability
import com.riffle.core.catalog.PlaylistsCapability
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.catalog.ReadingSessionsCapability
import com.riffle.core.catalog.SeriesCapability
import com.riffle.core.catalog.SortKey
import com.riffle.core.catalog.StatsCapability
import com.riffle.core.catalog.has
import com.riffle.core.domain.AudiobookFingerprint
import com.riffle.core.domain.Clock
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.SourceType
import com.riffle.core.network.AbsBookmarkApi
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.AbsPlaybackApi
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAbsAudioTrack
import com.riffle.core.network.NetworkAbsBookmark
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkAudioTrack
import com.riffle.core.network.NetworkCollection
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkLibrary
import com.riffle.core.network.NetworkLibraryItem
import com.riffle.core.network.NetworkListeningStats
import com.riffle.core.network.NetworkPlaybackSession
import com.riffle.core.network.NetworkPlaylist
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.NetworkSeries
import com.riffle.core.network.NetworkSeriesItem
import com.riffle.core.network.NetworkServerProgress
import com.riffle.core.network.NetworkUserMediaProgress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AbsCatalogTest {

    private val config = AbsCatalogConfig(
        baseUrl = "https://abs.example.com",
        token = "T",
        insecureAllowed = false,
        deviceId = "device-A",
    )

    private val clock = object : Clock {
        var now = 1_700_000_000_000L
        override fun nowMs(): Long = now
        override fun nowNs(): Long = 0L
    }

    private val libraryApi = FakeAbsLibraryApi()
    private val playbackApi = FakeAbsPlaybackApi()
    private val sessionApi = FakeAbsSessionApi()
    private val serverInfoApi = FakeAbsServerInfoApi()
    private val bookmarkApi = FakeAbsBookmarkApi()

    private val catalog = AbsCatalog(
        config = config,
        libraryApi = libraryApi,
        playbackApi = playbackApi,
        sessionApi = sessionApi,
        bookmarkApi = bookmarkApi,
        serverInfoApi = serverInfoApi,
        clock = clock,
    )

    // region sourceType + capability presence

    @Test fun `sourceType is ABS`() {
        assertEquals(SourceType.ABS, catalog.sourceType)
    }

    @Test fun `implements every capability ABS provides`() {
        assertTrue(catalog.has<SeriesCapability>())
        assertTrue(catalog.has<CollectionsCapability>())
        assertTrue(catalog.has<PlaylistsCapability>())
        assertTrue(catalog.has<ProgressPeerCapability>())
        assertTrue(catalog.has<ReadingSessionsCapability>())
        assertTrue(catalog.has<StatsCapability>())
        assertTrue(catalog.has<AudiobookMediaCapability>())
        assertTrue(catalog.has<OfflineBrowseCapability>())
    }

    // endregion

    // region Catalog — mandatory core

    @Test fun `listRoots maps libraries to CatalogRoot`() = runTest {
        libraryApi.libraries = listOf(
            NetworkLibrary(id = "lib-a", name = "Ebooks", mediaType = "book", audiobooksOnly = false),
            NetworkLibrary(id = "lib-b", name = "Casts", mediaType = "podcast", audiobooksOnly = false),
        )

        val roots = catalog.listRoots()

        assertEquals(2, roots.size)
        assertEquals("lib-a", roots[0].id)
        assertEquals("Ebooks", roots[0].name)
        assertEquals("book", roots[0].mediaType)
        assertEquals(false, roots[0].isUnsupported)
        // Podcast media flagged as unsupported so UI can hide the tab.
        assertEquals(true, roots[1].isUnsupported)
    }

    @Test fun `browse sorts by title and pages results`() = runTest {
        libraryApi.libraryItems["lib-a"] = listOf(
            item(id = "3", title = "Charlie"),
            item(id = "1", title = "Alpha"),
            item(id = "2", title = "Bravo"),
            item(id = "4", title = "Delta"),
        )

        val page0 = catalog.browse(rootId = "lib-a", sort = SortKey.TITLE, page = 0, pageSize = 2)
        val page1 = catalog.browse(rootId = "lib-a", sort = SortKey.TITLE, page = 1, pageSize = 2)
        val page2 = catalog.browse(rootId = "lib-a", sort = SortKey.TITLE, page = 2, pageSize = 2)

        assertEquals(listOf("Alpha", "Bravo"), page0.map { it.title })
        assertEquals(listOf("Charlie", "Delta"), page1.map { it.title })
        assertTrue(page2.isEmpty())
    }

    @Test fun `browse with RECENTLY_OPENED refuses instead of silently sorting by title`() = runTest {
        libraryApi.libraryItems["lib-a"] = listOf(item(id = "1", title = "Alpha"))

        try {
            catalog.browse(rootId = "lib-a", sort = SortKey.RECENTLY_OPENED)
            fail("expected CatalogException.UnsupportedFormat")
        } catch (_: CatalogException.UnsupportedFormat) {
        }
    }

    @Test fun `browse sorts by ADDED_AT descending`() = runTest {
        libraryApi.libraryItems["lib-a"] = listOf(
            item(id = "1", title = "Old", addedAt = 100L),
            item(id = "2", title = "New", addedAt = 300L),
            item(id = "3", title = "Mid", addedAt = 200L),
        )

        val items = catalog.browse(rootId = "lib-a", sort = SortKey.ADDED_AT)

        assertEquals(listOf("New", "Mid", "Old"), items.map { it.title })
    }

    @Test fun `search maps hits and pages client-side`() = runTest {
        libraryApi.searchResults["hobbit"] = listOf(
            item(id = "h1", title = "The Hobbit"),
            item(id = "h2", title = "The Hobbit — Illustrated"),
        )

        val page0 = catalog.search(rootId = "lib-a", query = "hobbit", page = 0, pageSize = 1)
        val page1 = catalog.search(rootId = "lib-a", query = "hobbit", page = 1, pageSize = 1)

        assertEquals(1, page0.size)
        assertEquals("The Hobbit", page0.single().title)
        assertEquals("The Hobbit — Illustrated", page1.single().title)
        // ABS's limit param must be at least pageSize per call.
        assertTrue(libraryApi.lastSearchLimit >= 1)
    }

    @Test fun `getItem returns null on missing item`() = runTest {
        val item = catalog.getItem(itemId = "missing")

        assertNull(item)
    }

    @Test fun `getItem maps to CatalogItem with cover URL`() = runTest {
        libraryApi.singleItems["it-1"] = item(id = "it-1", title = "Item 1")

        val result = catalog.getItem(itemId = "it-1")

        assertNotNull(result)
        assertEquals("it-1", result!!.id)
        assertEquals("Item 1", result.title)
        assertEquals("https://abs.example.com/api/items/it-1/cover?t=555", result.coverUrl)
    }

    @Test fun `fetchFile Epub returns Stream with auth headers`() = runTest {
        libraryApi.ebookInos["it-1"] = "ino-x"

        val handle = catalog.fetchFile(itemId = "it-1", format = BookFormat.Epub) as CatalogFileHandle.Stream

        assertEquals("https://abs.example.com/api/items/it-1/ebook/ino-x", handle.url)
        assertEquals("Bearer T", handle.headers["Authorization"])
        assertEquals(BookFormat.Epub, handle.format)
    }

    @Test fun `fetchFile Audiobook rejects with CatalogException UnsupportedFormat`() = runTest {
        try {
            catalog.fetchFile(itemId = "it-1", format = BookFormat.Audiobook)
            fail("expected CatalogException.UnsupportedFormat")
        } catch (_: CatalogException.UnsupportedFormat) {
        }
    }

    @Test fun `fetchFile Unsupported rejects with CatalogException UnsupportedFormat`() = runTest {
        try {
            catalog.fetchFile(itemId = "it-1", format = BookFormat.Unsupported)
            fail("expected CatalogException.UnsupportedFormat")
        } catch (_: CatalogException.UnsupportedFormat) {
        }
    }

    @Test fun `connectivityCheck reports reachable when serverInfo returns version`() = runTest {
        serverInfoApi.serverVersion = "2.19.0"
        clock.now = 1000
        // getServerInfo advances the clock so the AbsCatalog latency read differs.
        serverInfoApi.onCall = { clock.now = 1042 }

        val health = catalog.connectivityCheck()

        assertEquals(true, health.isReachable)
        assertEquals("2.19.0", health.serverVersion)
        assertEquals(42L, health.latencyMs)
    }

    @Test fun `connectivityCheck reports unreachable when serverInfo returns null`() = runTest {
        serverInfoApi.serverVersion = null

        val health = catalog.connectivityCheck()

        assertEquals(false, health.isReachable)
        assertNull(health.serverVersion)
    }

    // endregion

    // region SeriesCapability

    @Test fun `listSeries maps series responses`() = runTest {
        libraryApi.seriesByLibrary["lib-a"] = listOf(
            NetworkSeries(
                id = "s1",
                libraryId = "lib-a",
                name = "Foundation",
                items = listOf(seriesItem("f1", "Foundation Book 1")),
            ),
        )

        val result = catalog.listSeries(rootId = "lib-a")

        assertEquals(1, result.size)
        assertEquals("s1", result.single().id)
        assertEquals("lib-a", result.single().rootId)
        assertEquals("Foundation", result.single().name)
        assertEquals(1, result.single().bookCount)
        // Cover URL falls through the first book.
        assertTrue(result.single().coverUrl!!.contains("/api/items/f1/cover"))
    }

    @Test fun `listItemsInSeries returns empty when series id not found`() = runTest {
        libraryApi.seriesByLibrary["lib-a"] = listOf(
            NetworkSeries(id = "s1", libraryId = "lib-a", name = "F", items = emptyList()),
        )

        val items = catalog.listItemsInSeries(rootId = "lib-a", seriesId = "unknown")

        assertTrue(items.isEmpty())
    }

    @Test fun `listItemsInSeries maps books of matching series`() = runTest {
        libraryApi.seriesByLibrary["lib-a"] = listOf(
            NetworkSeries(
                id = "s1",
                libraryId = "lib-a",
                name = "Foundation",
                items = listOf(seriesItem("f1", "Foundation Book 1"), seriesItem("f2", "Foundation Book 2")),
            ),
        )

        val items = catalog.listItemsInSeries(rootId = "lib-a", seriesId = "s1")

        assertEquals(listOf("f1", "f2"), items.map { it.id })
        assertEquals(listOf("Foundation Book 1", "Foundation Book 2"), items.map { it.title })
    }

    @Test fun `listItemsInSeries carries hasAudio through so audio-only titles stay Audiobook`() = runTest {
        libraryApi.seriesByLibrary["lib-a"] = listOf(
            NetworkSeries(
                id = "s1",
                libraryId = "lib-a",
                name = "Foundation",
                items = listOf(
                    seriesItem("f1", "Ebook Book").copy(hasAudio = false, ebookFormat = EbookFormat.Epub),
                    seriesItem("f2", "Audiobook Book").copy(hasAudio = true, audioDurationSec = 3600.0, ebookFormat = EbookFormat.Unsupported),
                ),
            ),
        )

        val items = catalog.listItemsInSeries(rootId = "lib-a", seriesId = "s1")

        assertEquals(BookFormat.Epub, items[0].ebookFormat)
        assertEquals(BookFormat.Audiobook, items[1].ebookFormat)
        assertEquals(true, items[1].hasAudio)
        assertEquals(3600.0, items[1].audioDurationSec, 0.0)
    }

    // endregion

    // region CollectionsCapability

    @Test fun `listCollections maps ABS collections`() = runTest {
        libraryApi.collectionsByLibrary["lib-a"] = listOf(
            NetworkCollection(id = "c1", libraryId = "lib-a", name = "Favorites", items = emptyList()),
        )

        val result = catalog.listCollections(rootId = "lib-a")

        assertEquals("c1", result.single().id)
        assertEquals("Favorites", result.single().name)
    }

    @Test fun `createCollection returns the created collection`() = runTest {
        libraryApi.nextCreatedCollection = NetworkCollection(id = "c-new", libraryId = "lib-a", name = "New List", items = emptyList())

        val result = catalog.createCollection(rootId = "lib-a", name = "New List")

        assertEquals("c-new", result.id)
        assertEquals("New List", result.name)
    }

    @Test fun `addItemToCollection routes to the book endpoint`() = runTest {
        libraryApi.nextCreatedCollection = NetworkCollection("c1", "lib-a", "X", emptyList())

        catalog.addItemToCollection(collectionId = "c1", itemId = "book-1")

        assertEquals("c1" to "book-1", libraryApi.lastCollectionAdd)
    }

    @Test fun `removeItemFromCollection routes to the book endpoint`() = runTest {
        libraryApi.nextCreatedCollection = NetworkCollection("c1", "lib-a", "X", emptyList())

        catalog.removeItemFromCollection(collectionId = "c1", itemId = "book-1")

        assertEquals("c1" to "book-1", libraryApi.lastCollectionRemove)
    }

    // endregion

    // region PlaylistsCapability

    @Test fun `listPlaylists maps ABS playlists`() = runTest {
        libraryApi.playlistsByLibrary["lib-a"] = listOf(
            NetworkPlaylist(id = "p1", libraryId = "lib-a", name = "Queue", items = emptyList(), bookIds = emptySet()),
        )

        val result = catalog.listPlaylists(rootId = "lib-a")

        assertEquals("p1", result.single().id)
        assertEquals("Queue", result.single().name)
    }

    @Test fun `createPlaylist returns the created playlist`() = runTest {
        libraryApi.nextCreatedPlaylist = NetworkPlaylist("p-new", "lib-a", "New Q", emptyList(), emptySet())

        val result = catalog.createPlaylist(rootId = "lib-a", name = "New Q")

        assertEquals("p-new", result.id)
    }

    @Test fun `addItemToPlaylist routes to the book endpoint`() = runTest {
        libraryApi.nextCreatedPlaylist = NetworkPlaylist("p1", "lib-a", "Q", emptyList(), emptySet())

        catalog.addItemToPlaylist(playlistId = "p1", itemId = "book-1")

        assertEquals("p1" to "book-1", libraryApi.lastPlaylistAdd)
    }

    // endregion

    // region ProgressPeerCapability

    @Test fun `pushEbookProgress sends location progress and isFinished`() = runTest {
        catalog.pushEbookProgress(
            itemId = "it-1",
            location = "epubcfi(/6/4)",
            progress = 0.5f,
            isFinished = false,
            lastUpdateEpochMs = 42L,
        )

        assertEquals("it-1", sessionApi.lastEbookPushItemId)
        val payload = sessionApi.lastEbookPushPayload!!
        assertEquals("epubcfi(/6/4)", payload.ebookLocation)
        assertEquals(0.5f, payload.ebookProgress)
        assertEquals(false, payload.isFinished)
    }

    @Test fun `pushAudiobookProgress sends currentTime and duration`() = runTest {
        catalog.pushAudiobookProgress(
            itemId = "it-1",
            currentTimeSec = 120.5,
            durationSec = 3600.0,
            isFinished = false,
            lastUpdateEpochMs = 42L,
        )

        assertEquals("it-1", sessionApi.lastAudiobookPushItemId)
        assertEquals(120.5, sessionApi.lastAudiobookPushPayload!!.currentTime, 0.0)
        assertEquals(3600.0, sessionApi.lastAudiobookPushPayload!!.duration, 0.0)
    }

    @Test fun `pullProgress returns null on empty ABS record`() = runTest {
        sessionApi.progressForItem["it-1"] = NetworkServerProgress(ebookLocation = "", lastUpdate = 0L)

        val result = catalog.pullProgress(itemId = "it-1")

        assertNull(result)
    }

    @Test fun `pullProgress maps a non-empty ABS record`() = runTest {
        sessionApi.progressForItem["it-1"] = NetworkServerProgress(
            ebookLocation = "epubcfi(/6/4)",
            ebookProgress = 0.5f,
            currentTime = 30.0,
            duration = 300.0,
            lastUpdate = 999L,
        )

        val result = catalog.pullProgress(itemId = "it-1")!!

        assertEquals("epubcfi(/6/4)", result.ebookLocation)
        assertEquals(0.5f, result.ebookProgress)
        assertEquals(30.0, result.audioCurrentTime, 0.0)
        assertEquals(300.0, result.audioDuration, 0.0)
        assertEquals(999L, result.lastUpdate)
        assertEquals(false, result.isFinished)
    }

    @Test fun `pullProgress derives isFinished when ebook progress hits 1`() = runTest {
        sessionApi.progressForItem["it-1"] = NetworkServerProgress(
            ebookLocation = "epubcfi(/6/8)",
            ebookProgress = 1f,
            lastUpdate = 999L,
        )

        val result = catalog.pullProgress(itemId = "it-1")!!

        assertEquals(true, result.isFinished)
    }

    @Test fun `pullProgress derives isFinished when audio currentTime reaches duration`() = runTest {
        sessionApi.progressForItem["it-1"] = NetworkServerProgress(
            ebookLocation = "",
            currentTime = 3600.0,
            duration = 3600.0,
            lastUpdate = 999L,
        )

        val result = catalog.pullProgress(itemId = "it-1")!!

        assertEquals(true, result.isFinished)
    }

    @Test fun `pullAllProgress converts NetworkUserMediaProgress map to list`() = runTest {
        libraryApi.userProgress = mapOf(
            "a" to NetworkUserMediaProgress(ebookProgress = 0.25f, lastUpdate = 100L, finishedAt = null),
            "b" to NetworkUserMediaProgress(ebookProgress = 1f, lastUpdate = 200L, finishedAt = 300L),
        )

        val result = catalog.pullAllProgress().associateBy { it.itemId }

        assertEquals(0.25f, result["a"]!!.ebookProgress)
        assertEquals(false, result["a"]!!.isFinished)
        assertEquals(true, result["b"]!!.isFinished)
    }

    // endregion

    // region ReadingSessionsCapability

    @Test fun `openSession returns handle keyed on session id and current clock`() = runTest {
        playbackApi.nextSession = NetworkPlaybackSession(
            sessionId = "sess-1",
            tracks = emptyList(),
            chapters = emptyList(),
            currentTimeSec = 0.0,
            durationSec = 0.0,
        )
        clock.now = 9_000L

        val handle = catalog.openSession(itemId = "it-1", deviceLabel = "Pixel")

        assertEquals("sess-1", handle.sessionId)
        assertEquals("it-1", handle.itemId)
        assertEquals(9_000L, handle.startedAtEpochMs)
        assertEquals("device-A", playbackApi.lastDeviceId)
    }

    @Test fun `openSession throws Unknown when ABS returns null sessionId`() = runTest {
        playbackApi.nextSession = NetworkPlaybackSession(
            sessionId = null,
            tracks = emptyList(),
            chapters = emptyList(),
            currentTimeSec = 0.0,
            durationSec = 0.0,
        )

        try {
            catalog.openSession(itemId = "it-1", deviceLabel = "Pixel")
            fail("expected CatalogException.Unknown")
        } catch (_: CatalogException.Unknown) {
        }
    }

    @Test fun `syncSession forwards sessionId currentTime and timeListened`() = runTest {
        val handle = com.riffle.core.catalog.CatalogSessionHandle("sess-1", "it-1", 0L)

        catalog.syncSession(handle, currentTimeSec = 120.0, timeListenedSec = 60.0)

        assertEquals("sess-1", playbackApi.lastSyncSessionId)
        assertEquals(120.0, playbackApi.lastSyncCurrent!!, 0.0)
        assertEquals(60.0, playbackApi.lastSyncListened!!, 0.0)
    }

    @Test fun `closeSession forwards sessionId currentTime and timeListened`() = runTest {
        val handle = com.riffle.core.catalog.CatalogSessionHandle("sess-1", "it-1", 0L)

        catalog.closeSession(handle, currentTimeSec = 200.0, timeListenedSec = 90.0)

        assertEquals("sess-1", playbackApi.lastCloseSessionId)
        assertEquals(200.0, playbackApi.lastCloseCurrent!!, 0.0)
    }

    // endregion

    // region StatsCapability

    @Test fun `getStats aggregates listening time and item counts`() = runTest {
        serverInfoApi.stats = NetworkListeningStats(totalTimeSec = 7200.0)
        libraryApi.userProgress = mapOf(
            "a" to NetworkUserMediaProgress(0.5f, 0L, finishedAt = null),
            "b" to NetworkUserMediaProgress(1f, 0L, finishedAt = 999L),
            "c" to NetworkUserMediaProgress(0.1f, 0L, finishedAt = null),
        )

        val stats = catalog.getStats()

        assertEquals(7200.0, stats.totalSecondsListened, 0.0)
        assertEquals(2, stats.totalItemsInProgress)
        assertEquals(1, stats.totalItemsFinished)
    }

    // endregion

    // region AudiobookMediaCapability

    @Test fun `getTracks builds startOffsets and content URLs from durations`() = runTest {
        libraryApi.audiobookTracks["it-1"] = listOf(
            NetworkAbsAudioTrack(ino = "a", index = 0, durationSec = 60.0),
            NetworkAbsAudioTrack(ino = "b", index = 1, durationSec = 120.0),
            NetworkAbsAudioTrack(ino = "c", index = 2, durationSec = 30.0),
        )

        val tracks = catalog.getTracks(itemId = "it-1")

        assertEquals(0.0, tracks[0].startOffsetSec, 0.0)
        assertEquals(60.0, tracks[1].startOffsetSec, 0.0)
        assertEquals(180.0, tracks[2].startOffsetSec, 0.0)
        assertEquals("https://abs.example.com/api/items/it-1/file/a", tracks[0].contentUrl)
    }

    @Test fun `getFingerprint maps ABS AudiobookFingerprint to catalog type`() = runTest {
        libraryApi.fingerprints["it-1"] = AudiobookFingerprint(
            fileSizeBytes = 12345L,
            durationSec = 3600.0,
            trackDurationsSec = listOf(1800.0, 1800.0),
        )

        val fp = catalog.getFingerprint(itemId = "it-1")

        assertEquals("it-1", fp.itemId)
        assertEquals(3600.0, fp.totalDurationSec, 0.0)
        assertEquals(listOf(1800.0, 1800.0), fp.trackDurations)
    }

    @Test fun `getFingerprint throws Unknown when item has no audiobook`() = runTest {
        libraryApi.fingerprints["it-1"] = null

        try {
            catalog.getFingerprint(itemId = "it-1")
            fail("expected CatalogException.Unknown")
        } catch (_: CatalogException.Unknown) {
        }
    }

    @Test fun `buildStreamUrl mirrors AbsAudioUrl track pattern`() {
        val url = catalog.buildStreamUrl(itemId = "it-1", trackIno = "a")

        assertEquals("https://abs.example.com/api/items/it-1/file/a", url)
    }

    // endregion

    // region error propagation

    @Test fun `network Auth surfaces as CatalogException Auth`() = runTest {
        libraryApi.librariesResult = NetworkResult.Auth

        try {
            catalog.listRoots()
            fail("expected CatalogException.Auth")
        } catch (_: CatalogException.Auth) {
        }
    }

    @Test fun `network ServerError surfaces the code`() = runTest {
        libraryApi.librariesResult = NetworkResult.ServerError(code = 503, errorMessage = "down")

        try {
            catalog.listRoots()
            fail("expected CatalogException.ServerError")
        } catch (e: CatalogException.ServerError) {
            assertEquals(503, e.code)
        }
    }

    @Test fun `network Offline surfaces as CatalogException Offline`() = runTest {
        libraryApi.librariesResult = NetworkResult.Offline(java.io.IOException("boom"))

        try {
            catalog.listRoots()
            fail("expected CatalogException.Offline")
        } catch (_: CatalogException.Offline) {
        }
    }

    // endregion

    // region helpers

    private fun item(
        id: String,
        title: String = "Untitled",
        author: String = "Anon",
        addedAt: Long? = null,
        hasAudio: Boolean = false,
        format: EbookFormat = EbookFormat.Epub,
    ) = NetworkLibraryItem(
        id = id,
        libraryId = "lib-a",
        title = title,
        author = author,
        readingProgress = null,
        ebookFormat = format,
        ebookFileIno = "ino-$id",
        hasAudio = hasAudio,
        addedAt = addedAt,
        updatedAt = 555L,
    )

    private fun seriesItem(id: String, title: String) = NetworkSeriesItem(
        id = id,
        libraryId = "lib-a",
        title = title,
        author = "Anon",
        sequence = null,
        readingProgress = null,
        ebookFormat = EbookFormat.Epub,
        updatedAt = 555L,
    )

    // endregion
}

// region fakes

private class FakeAbsLibraryApi : AbsLibraryApi {
    var libraries: List<NetworkLibrary> = emptyList()
    var librariesResult: NetworkResult<List<NetworkLibrary>>? = null
    val libraryItems = mutableMapOf<String, List<NetworkLibraryItem>>()
    val searchResults = mutableMapOf<String, List<NetworkLibraryItem>>()
    var lastSearchLimit: Int = -1
    val singleItems = mutableMapOf<String, NetworkLibraryItem>()
    val ebookInos = mutableMapOf<String, String>()
    val seriesByLibrary = mutableMapOf<String, List<NetworkSeries>>()
    val collectionsByLibrary = mutableMapOf<String, List<NetworkCollection>>()
    val playlistsByLibrary = mutableMapOf<String, List<NetworkPlaylist>>()
    var nextCreatedCollection: NetworkCollection? = null
    var nextCreatedPlaylist: NetworkPlaylist? = null
    var lastCollectionAdd: Pair<String, String>? = null
    var lastCollectionRemove: Pair<String, String>? = null
    var lastPlaylistAdd: Pair<String, String>? = null
    var lastPlaylistRemove: Pair<String, String>? = null
    val audiobookTracks = mutableMapOf<String, List<NetworkAbsAudioTrack>>()
    val fingerprints = mutableMapOf<String, AudiobookFingerprint?>()
    var userProgress: Map<String, NetworkUserMediaProgress> = emptyMap()

    override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibrary>> =
        librariesResult ?: NetworkResult.Success(libraries)

    override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> =
        NetworkResult.Success(libraryItems[libraryId].orEmpty())

    override suspend fun searchLibrary(baseUrl: String, libraryId: String, query: String, limit: Int, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkLibraryItem>> {
        lastSearchLimit = limit
        return NetworkResult.Success(searchResults[query].orEmpty())
    }

    override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkSeries>> =
        NetworkResult.Success(seriesByLibrary[libraryId].orEmpty())

    override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkCollection>> =
        NetworkResult.Success(collectionsByLibrary[libraryId].orEmpty())

    override suspend fun createCollection(baseUrl: String, libraryId: String, name: String, initialBookId: String?, token: String, insecureAllowed: Boolean): NetworkResult<NetworkCollection?> =
        NetworkResult.Success(nextCreatedCollection)

    override suspend fun addBookToCollection(baseUrl: String, collectionId: String, libraryItemId: String, token: String, insecureAllowed: Boolean): NetworkResult<NetworkCollection?> {
        lastCollectionAdd = collectionId to libraryItemId
        return NetworkResult.Success(nextCreatedCollection)
    }

    override suspend fun removeBookFromCollection(baseUrl: String, collectionId: String, libraryItemId: String, token: String, insecureAllowed: Boolean): NetworkResult<NetworkCollection?> {
        lastCollectionRemove = collectionId to libraryItemId
        return NetworkResult.Success(nextCreatedCollection)
    }

    override suspend fun getPlaylists(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkPlaylist>> =
        NetworkResult.Success(playlistsByLibrary[libraryId].orEmpty())

    override suspend fun createPlaylist(baseUrl: String, libraryId: String, name: String, initialBookId: String?, token: String, insecureAllowed: Boolean): NetworkResult<NetworkPlaylist?> =
        NetworkResult.Success(nextCreatedPlaylist)

    override suspend fun addBookToPlaylist(baseUrl: String, playlistId: String, libraryItemId: String, token: String, insecureAllowed: Boolean): NetworkResult<NetworkPlaylist?> {
        lastPlaylistAdd = playlistId to libraryItemId
        return NetworkResult.Success(nextCreatedPlaylist)
    }

    override suspend fun removeBookFromPlaylist(baseUrl: String, playlistId: String, libraryItemId: String, token: String, insecureAllowed: Boolean): NetworkResult<NetworkPlaylist?> {
        lastPlaylistRemove = playlistId to libraryItemId
        return NetworkResult.Success(nextCreatedPlaylist)
    }

    override suspend fun getItemEbookFileIno(baseUrl: String, itemId: String, token: String, insecureAllowed: Boolean): NetworkResult<String> =
        NetworkResult.Success(ebookInos[itemId] ?: "unknown-ino")

    override suspend fun getItem(baseUrl: String, itemId: String, token: String, insecureAllowed: Boolean): NetworkResult<NetworkLibraryItem?> =
        NetworkResult.Success(singleItems[itemId])

    override suspend fun getAudiobookFingerprint(baseUrl: String, itemId: String, token: String, insecureAllowed: Boolean): NetworkResult<AudiobookFingerprint?> =
        NetworkResult.Success(fingerprints[itemId])

    override suspend fun getAudiobookTracks(baseUrl: String, itemId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkAbsAudioTrack>> =
        NetworkResult.Success(audiobookTracks[itemId].orEmpty())

    override suspend fun getUserProgress(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<Map<String, NetworkUserMediaProgress>> =
        NetworkResult.Success(userProgress)
}

private class FakeAbsPlaybackApi : AbsPlaybackApi {
    var nextSession: NetworkPlaybackSession = NetworkPlaybackSession(
        sessionId = "sess-default",
        tracks = emptyList<NetworkAudioTrack>(),
        chapters = emptyList(),
        currentTimeSec = 0.0,
        durationSec = 0.0,
    )
    var lastDeviceId: String? = null
    var lastSyncSessionId: String? = null
    var lastSyncCurrent: Double? = null
    var lastSyncListened: Double? = null
    var lastCloseSessionId: String? = null
    var lastCloseCurrent: Double? = null
    var lastCloseListened: Double? = null

    override suspend fun openPlaybackSession(baseUrl: String, libraryItemId: String, deviceId: String, token: String, insecureAllowed: Boolean): NetworkResult<NetworkPlaybackSession> {
        lastDeviceId = deviceId
        return NetworkResult.Success(nextSession)
    }

    override suspend fun syncPlaybackSession(baseUrl: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, token: String, insecureAllowed: Boolean): NetworkResult<Unit> {
        lastSyncSessionId = sessionId
        lastSyncCurrent = currentTimeSec
        lastSyncListened = timeListenedSec
        return NetworkResult.Success(Unit)
    }

    override suspend fun closePlaybackSession(baseUrl: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, token: String, insecureAllowed: Boolean): NetworkResult<Unit> {
        lastCloseSessionId = sessionId
        lastCloseCurrent = currentTimeSec
        lastCloseListened = timeListenedSec
        return NetworkResult.Success(Unit)
    }
}

private class FakeAbsSessionApi : AbsSessionApi {
    val progressForItem = mutableMapOf<String, NetworkServerProgress>()
    var lastEbookPushItemId: String? = null
    var lastEbookPushPayload: NetworkEbookProgressPayload? = null
    var lastAudiobookPushItemId: String? = null
    var lastAudiobookPushPayload: NetworkAudiobookProgressPayload? = null

    override suspend fun syncEbookProgress(baseUrl: String, libraryItemId: String, payload: NetworkEbookProgressPayload, token: String, insecureAllowed: Boolean): NetworkResult<Long> {
        lastEbookPushItemId = libraryItemId
        lastEbookPushPayload = payload
        return NetworkResult.Success(1L)
    }

    override suspend fun syncAudiobookProgress(baseUrl: String, libraryItemId: String, payload: NetworkAudiobookProgressPayload, token: String, insecureAllowed: Boolean): NetworkResult<Long> {
        lastAudiobookPushItemId = libraryItemId
        lastAudiobookPushPayload = payload
        return NetworkResult.Success(1L)
    }

    override suspend fun getProgress(baseUrl: String, libraryItemId: String, token: String, insecureAllowed: Boolean): NetworkResult<NetworkServerProgress> =
        NetworkResult.Success(progressForItem[libraryItemId] ?: NetworkServerProgress(ebookLocation = "", lastUpdate = 0L))
}

private class FakeAbsBookmarkApi : AbsBookmarkApi {
    override suspend fun createBookmark(baseUrl: String, itemId: String, timeSec: Int, title: String, token: String, insecureAllowed: Boolean): NetworkResult<NetworkAbsBookmark> =
        NetworkResult.Success(NetworkAbsBookmark(itemId, title, timeSec, 0L))

    override suspend fun updateBookmark(baseUrl: String, itemId: String, timeSec: Int, title: String, token: String, insecureAllowed: Boolean): NetworkResult<NetworkAbsBookmark> =
        NetworkResult.Success(NetworkAbsBookmark(itemId, title, timeSec, 0L))

    override suspend fun deleteBookmark(baseUrl: String, itemId: String, timeSec: Int, token: String, insecureAllowed: Boolean): NetworkResult<NetworkAbsBookmark> =
        NetworkResult.Success(NetworkAbsBookmark(itemId, "", timeSec, 0L))

    override suspend fun listBookmarks(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkAbsBookmark>> =
        NetworkResult.Success(emptyList())
}

private class FakeAbsServerInfoApi : AbsServerInfoApi {
    var serverVersion: String? = "1.0.0"
    var stats: NetworkListeningStats = NetworkListeningStats(0.0)
    var onCall: (() -> Unit)? = null

    override suspend fun getServerInfo(baseUrl: String, token: String, insecureAllowed: Boolean): String? {
        onCall?.invoke()
        return serverVersion
    }

    override suspend fun getCurrentUserId(baseUrl: String, token: String, insecureAllowed: Boolean): String? = "user-1"

    override suspend fun getListeningStats(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<NetworkListeningStats> =
        NetworkResult.Success(stats)
}

// endregion
