package com.riffle.app.feature.audiobook

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.riffle.app.feature.reader.ReaderSyncCoordinator
import com.riffle.app.feature.reader.ReaderSyncFactory
import com.riffle.app.feature.reader.ProgressFlushScope
import com.riffle.app.feature.reader.AudiobookFollow
import com.riffle.app.playback.NowPlayingStore
import com.riffle.core.data.CrossEpubIndexBuildTrigger
import com.riffle.core.data.OpenReconcileTargets
import com.riffle.core.domain.AudioIdentity
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import com.riffle.core.domain.AudiobookBookmark
import com.riffle.core.domain.AudiobookBookmarkStore
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.AudiobookRepository
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.domain.BookmarkTitleBuilder
import com.riffle.core.domain.BundleAudiobookSource
import com.riffle.core.domain.CrossEpubIndex
import com.riffle.core.domain.CrossEpubIndexStore
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.PositionSnapshot
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudLink
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadaloudResumePosition
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.StoredItemRef
import com.riffle.core.domain.SyncPositionStore
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkGetProgressResult
import com.riffle.core.network.NetworkSyncSessionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class AudiobookPlayerViewModelBookmarkTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val serverId = "srv-1"
    private val itemId = "item-1"
    private val fixedNow = 1_700_000_000_000L
    private val timeline = AudiobookTimeline(
        durationSec = 1000.0,
        chapters = listOf(
            AudiobookChapter(index = 0, startSec = 0.0, endSec = 500.0, title = "Chapter One"),
            AudiobookChapter(index = 1, startSec = 500.0, endSec = 1000.0, title = "Chapter Two"),
        ),
    )

    // A fake controller whose book-absolute position is settable and whose seekTo is recorded. Only the
    // members the VM actually touches are overridden; the heavy Media3/Context machinery is bypassed.
    private class FakeController(var position: Double) : AudiobookController() {
        val seeks = mutableListOf<Double>()
        override val state = MutableStateFlow(PlaybackState())
        override suspend fun prepare(
            trackUrls: List<String>,
            spans: List<com.riffle.core.domain.AudiobookTrackSpan>,
            durationSec: Double,
            startAtSec: Double,
            localZipFile: File?,
        ) { /* no-op */ }
        override fun play() {}
        override fun setSpeed(speed: Float) {}
        override fun currentAbsoluteSec(): Double = position
        override fun seekTo(absoluteSec: Double) { seeks.add(absoluteSec) }
    }

    // Cancel the ViewModel's [viewModelScope] (the never-ending follow loop + bookmark observation) so
    // runTest's end-of-body drain doesn't spin forever on the follow loop's recurring delay. Uses the
    // public viewModelScope extension — the scope already exists (init touched it) — to cancel directly.
    private fun AudiobookPlayerViewModel.clearForTest() {
        this.viewModelScope.cancel()
    }

    private fun buildViewModel(
        controller: FakeController,
        bookmarkStore: AudiobookBookmarkStore,
        connectivity: FakeConnectivityObserver = FakeConnectivityObserver(online = true),
    ): AudiobookPlayerViewModel {
        val session = AudiobookSession(
            trackUrls = listOf("http://x/track0"),
            tracks = listOf(com.riffle.core.domain.AudiobookTrackSpan(0, 0.0, 1000.0)),
            timeline = timeline,
            serverCurrentTimeSec = 0.0,
            serverLastUpdate = 0L,
        )
        return AudiobookPlayerViewModel(
            savedStateHandle = SavedStateHandle(mapOf("itemId" to itemId)),
            audiobookRepository = FakeAudiobookRepository(session),
            audiobookDownloadRepository = NoDownloadRepo,
            bundleAudiobookSource = NoBundleSource,
            libraryRepository = FakeLibraryRepository(),
            serverRepository = FakeServerRepository(),
            tokenStorage = FakeTokenStorage,
            controller = controller,
            audioPlaybackPreferencesStore = FakePrefsStore,
            audioIdentityResolver = FakeIdentityResolver,
            readerSyncFactory = TestReaderSyncFactory(),
            readaloudLinkRepository = FakeLinkRepository,
            readaloudAudioRepository = FakeAudioRepo,
            nowPlayingStore = NowPlayingStore(),
            audiobookPositionStore = FakePositionStore(),
            readingSyncStore = FakeSyncStore(),
            audioSyncStore = FakeSyncStoreDouble(),
            readaloudResumeStore = FakeResumeStore,
            openReconcileTargets = OpenReconcileTargets(),
            progressFlushScope = ProgressFlushScope(CoroutineScope(testDispatcher)),
            bookmarkStore = bookmarkStore,
            connectivityObserver = connectivity,
            now = { fixedNow },
        )
    }

    @Test
    fun `defaultBookmarkTitle matches BookmarkTitleBuilder for the current position`() = runTest(testDispatcher) {
        val controller = FakeController(position = 540.0)
        val vm = buildViewModel(controller, FakeBookmarkStore())
        runCurrent()

        assertEquals(
            BookmarkTitleBuilder.defaultTitle(timeline, 540.0),
            vm.defaultBookmarkTitle(),
        )
        vm.clearForTest()
    }

    @Test
    fun `addBookmark forwards serverId itemId position title and the fixed clock to the store`() = runTest(testDispatcher) {
        val controller = FakeController(position = 321.0)
        val store = FakeBookmarkStore()
        val vm = buildViewModel(controller, store)
        runCurrent()

        vm.addBookmark("My title")
        runCurrent()

        assertEquals(1, store.added.size)
        assertEquals(
            AddCall(serverId, itemId, 321.0, "My title", fixedNow),
            store.added.single(),
        )
        // The new id is surfaced for an Undo.
        assertEquals(store.lastId, vm.uiState.value.lastCreatedBookmarkId)
        vm.clearForTest()
    }

    @Test
    fun `bookmarks emitted by the store appear in uiState`() = runTest(testDispatcher) {
        val controller = FakeController(position = 0.0)
        val store = FakeBookmarkStore()
        val vm = buildViewModel(controller, store)
        runCurrent()

        val bookmark = AudiobookBookmark(id = "bm-1", positionSec = 42.0, title = "Saved", createdAt = fixedNow)
        store.flow.value = listOf(bookmark)
        runCurrent()

        assertEquals(listOf(bookmark), vm.uiState.value.bookmarks)
        vm.clearForTest()
    }

    @Test
    fun `bookmarks observed during init survive the init success-path`() = runTest(testDispatcher) {
        val controller = FakeController(position = 0.0)
        val store = FakeBookmarkStore()
        // Seed the store BEFORE the ViewModel is built, so the live collector observes it during init's
        // suspend points — before the success-path writes the resolved metadata. A success path that
        // builds a fresh state (rather than copy()) would wipe this; copy() carries it forward.
        val bookmark = AudiobookBookmark(id = "bm-1", positionSec = 42.0, title = "Saved", createdAt = fixedNow)
        store.flow.value = listOf(bookmark)

        val vm = buildViewModel(controller, store)
        runCurrent()

        assertEquals(listOf(bookmark), vm.uiState.value.bookmarks)
        vm.clearForTest()
    }

    @Test
    fun `seekToBookmark seeks the controller to the bookmark position`() = runTest(testDispatcher) {
        val controller = FakeController(position = 0.0)
        val vm = buildViewModel(controller, FakeBookmarkStore())
        runCurrent()

        vm.seekToBookmark(123.0)
        runCurrent()

        assertEquals(listOf(123.0), controller.seeks)
        vm.clearForTest()
    }

    @Test
    fun `bookmarksOffline is true only when there are unsynced bookmarks AND the device is offline`() = runTest(testDispatcher) {
        // offline + unsynced → note shows
        run {
            val store = FakeBookmarkStore().apply { hasUnsynced.value = true }
            val vm = buildViewModel(FakeController(0.0), store, FakeConnectivityObserver(online = false))
            runCurrent()
            assertEquals(true, vm.uiState.value.bookmarksOffline)
            vm.clearForTest()
        }
        // offline + nothing unsynced → silent
        run {
            val store = FakeBookmarkStore().apply { hasUnsynced.value = false }
            val vm = buildViewModel(FakeController(0.0), store, FakeConnectivityObserver(online = false))
            runCurrent()
            assertEquals(false, vm.uiState.value.bookmarksOffline)
            vm.clearForTest()
        }
        // online + unsynced → silent (it'll sync)
        run {
            val store = FakeBookmarkStore().apply { hasUnsynced.value = true }
            val vm = buildViewModel(FakeController(0.0), store, FakeConnectivityObserver(online = true))
            runCurrent()
            assertEquals(false, vm.uiState.value.bookmarksOffline)
            vm.clearForTest()
        }
        // online + nothing unsynced → silent
        run {
            val store = FakeBookmarkStore().apply { hasUnsynced.value = false }
            val vm = buildViewModel(FakeController(0.0), store, FakeConnectivityObserver(online = true))
            runCurrent()
            assertEquals(false, vm.uiState.value.bookmarksOffline)
            vm.clearForTest()
        }
    }

    // --- fakes ---

    private class FakeConnectivityObserver(online: Boolean) : com.riffle.core.domain.ConnectivityObserver {
        override val isOnline = MutableStateFlow(online)
    }

    private data class AddCall(
        val serverId: String,
        val itemId: String,
        val positionSec: Double,
        val title: String,
        val now: Long,
    )

    private class FakeBookmarkStore : AudiobookBookmarkStore {
        val flow = MutableStateFlow<List<AudiobookBookmark>>(emptyList())
        val hasUnsynced = MutableStateFlow(false)
        val added = mutableListOf<AddCall>()
        var lastId: String = ""
        private var seq = 0
        override fun observe(serverId: String, itemId: String): Flow<List<AudiobookBookmark>> = flow
        override fun observeHasUnsynced(serverId: String, itemId: String): Flow<Boolean> = hasUnsynced
        override suspend fun add(serverId: String, itemId: String, positionSec: Double, title: String, now: Long): String {
            added.add(AddCall(serverId, itemId, positionSec, title, now))
            lastId = "bm-${seq++}"
            return lastId
        }
        override suspend fun rename(id: String, title: String, now: Long) {}
        override suspend fun delete(id: String, now: Long) {}
    }

    private class FakeAudiobookRepository(private val session: AudiobookSession) : AudiobookRepository {
        override suspend fun openSession(serverId: String, itemId: String): AudiobookSession = session
        override suspend fun saveProgress(serverId: String, itemId: String, positionSec: Double, durationSec: Double) {}
    }

    private object NoDownloadRepo : AudiobookDownloadRepository {
        override fun isDownloaded(serverId: String, itemId: String) = false
        override fun localSession(serverId: String, itemId: String): AudiobookSession? = null
        override suspend fun download(
            serverId: String,
            itemId: String,
            onProgress: (Long, Long) -> Unit,
        ) = com.riffle.core.domain.AudiobookDownloadResult.Success
        override suspend fun remove(serverId: String, itemId: String): Long = 0
    }

    private object NoBundleSource : BundleAudiobookSource {
        override suspend fun localSession(serverId: String, itemId: String): AudiobookSession? = null
        override fun isAvailableOffline(serverId: String, itemId: String) = false
    }

    private inner class FakeLibraryRepository : LibraryRepository {
        private val item = LibraryItem(
            id = itemId,
            libraryId = "lib",
            title = "Book",
            author = "Author",
            coverUrl = null,
            readingProgress = 0f,
            isCached = false,
            isDownloaded = false,
            ebookFormat = EbookFormat.Unsupported,
            hasAudio = true,
            audioDurationSec = 1000.0,
            serverId = serverId,
        )
        override suspend fun getItem(itemId: String): LibraryItem = item
        override suspend fun getItem(serverId: String, itemId: String): LibraryItem = item
        override suspend fun updateReadingProgress(itemId: String, progress: Float) {}
        override fun observeItem(itemId: String) = throw NotImplementedError()
        override fun observeLibraries() = throw NotImplementedError()
        override fun observeLibraries(serverId: String) = throw NotImplementedError()
        override fun observeLibraryItems(libraryId: String) = throw NotImplementedError()
        override fun observeUngroupedLibraryItems(libraryId: String) = throw NotImplementedError()
        override fun observeInProgressItems(libraryId: String) = throw NotImplementedError()
        override fun observeFinishedItems(libraryId: String) = throw NotImplementedError()
        override fun observeRecentlyAddedItems(libraryId: String) = throw NotImplementedError()
        override fun observeAllBooks(libraryId: String) = throw NotImplementedError()
        override fun observeSeries(libraryId: String) = throw NotImplementedError()
        override fun observeCollections(libraryId: String) = throw NotImplementedError()
        override fun observeSeriesItems(seriesId: String) = throw NotImplementedError()
        override fun observeCollectionItems(collectionId: String) = throw NotImplementedError()
        override suspend fun getLibrary(libraryId: String) = throw NotImplementedError()
        override suspend fun getSeriesIdForItem(serverId: String, itemId: String): String? = null
        override suspend fun markItemOpened(itemId: String) {}
        override suspend fun refreshLibraries() = throw NotImplementedError()
        override suspend fun refreshLibraryItems(libraryId: String) = throw NotImplementedError()
        override suspend fun refreshSeries(libraryId: String) = throw NotImplementedError()
        override suspend fun refreshCollections(libraryId: String) = throw NotImplementedError()
    }

    private inner class FakeServerRepository : ServerRepository {
        private val server = Server(
            id = serverId,
            url = ServerUrl.parse("http://example.com")!!,
            isActive = true,
            insecureConnectionAllowed = false,
            username = "u",
        )
        override fun observeAll() = throw NotImplementedError()
        override suspend fun getActive(): Server = server
        override suspend fun getById(serverId: String): Server = server
        override suspend fun authenticate(
            url: ServerUrl,
            username: String,
            password: String,
            insecureAllowed: Boolean,
            serverType: com.riffle.core.domain.ServerType,
        ) = throw NotImplementedError()
        override suspend fun commit(
            pending: com.riffle.core.domain.PendingServer,
            hiddenLibraryIds: Set<String>,
        ) = throw NotImplementedError()
        override suspend fun setActive(serverId: String) {}
        override suspend fun remove(serverId: String) {}
        override suspend fun getServerVersion(serverId: String): String? = null
    }

    private object FakeTokenStorage : TokenStorage {
        override suspend fun saveToken(serverId: String, token: String) {}
        override suspend fun getToken(serverId: String): String = "token"
        override suspend fun deleteToken(serverId: String) {}
    }

    private object FakePrefsStore : AudioPlaybackPreferencesStore {
        override suspend fun load(identity: AudioIdentity): Float? = null
        override suspend fun save(identity: AudioIdentity, speed: Float) {}
        override suspend fun clear(identity: AudioIdentity) {}
        override suspend fun rekey(old: AudioIdentity, new: AudioIdentity) {}
    }

    private object FakeIdentityResolver : AudioIdentityResolver {
        override suspend fun resolveForStorytellerBook(
            storytellerServerId: String,
            storytellerBookId: String,
        ) = AudioIdentity("", "")
    }

    private object FakeLinkRepository : ReadaloudLinkRepository {
        override fun observeAll() = throw NotImplementedError()
        override fun observeLinkedAbsItemIds() = throw NotImplementedError()
        override suspend fun findByAbsItem(absServerId: String, absLibraryItemId: String): ReadaloudLink? = null
        override suspend fun findByStorytellerBook(storytellerServerId: String, storytellerBookId: String): List<ReadaloudLink> = emptyList()
        override suspend fun unlinkAbsItem(absServerId: String, absLibraryItemId: String) {}
        override suspend fun countForServer(serverId: String): Int = 0
    }

    private object FakeAudioRepo : ReadaloudAudioRepository {
        override fun isAudioAvailable(serverId: String, itemId: String) = false
        override fun bundleFile(serverId: String, itemId: String): File? = null
        override suspend fun readTrack(serverId: String, itemId: String): ReadaloudTrack? = null
        override suspend fun probeSizeBytes(serverId: String, itemId: String): Long? = null
        override suspend fun downloadAudio(
            serverId: String,
            bookId: String,
            onProgress: (Long, Long) -> Unit,
        ) = com.riffle.core.domain.AudioDownloadResult.NoBundle
        override suspend fun removeAudio(serverId: String, itemId: String): Long = 0
    }

    private class FakePositionStore : com.riffle.core.domain.AudiobookPositionStore {
        override suspend fun save(serverId: String, itemId: String, payload: Double) {}
        override suspend fun load(serverId: String, itemId: String): Double? = null
        override suspend fun loadLocalUpdatedAt(serverId: String, itemId: String): Long = 0
        override suspend fun updateLocalTimestamp(serverId: String, itemId: String, millis: Long) {}
    }

    private class FakeSyncStore : SyncPositionStore<String> {
        override suspend fun snapshot(serverId: String, itemId: String) = PositionSnapshot<String>(null, 0, 0)
        override suspend fun acceptServerPosition(serverId: String, itemId: String, position: String, serverStamp: Long, ifLocalUpdatedAt: Long) = false
        override suspend fun confirmPushed(serverId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long) = false
        override suspend fun confirmInSync(serverId: String, itemId: String, ifLocalUpdatedAt: Long) = false
        override suspend fun mirror(serverId: String, itemId: String, position: String, localUpdatedAt: Long, lastSyncedAt: Long) {}
    }

    private class FakeSyncStoreDouble : SyncPositionStore<Double> {
        override suspend fun snapshot(serverId: String, itemId: String) = PositionSnapshot<Double>(null, 0, 0)
        override suspend fun acceptServerPosition(serverId: String, itemId: String, position: Double, serverStamp: Long, ifLocalUpdatedAt: Long) = false
        override suspend fun confirmPushed(serverId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long) = false
        override suspend fun confirmInSync(serverId: String, itemId: String, ifLocalUpdatedAt: Long) = false
        override suspend fun mirror(serverId: String, itemId: String, position: Double, localUpdatedAt: Long, lastSyncedAt: Long) {}
    }

    private object FakeResumeStore : ReadaloudResumeStore {
        override suspend fun save(serverId: String, itemId: String, position: ReadaloudResumePosition) {}
        override suspend fun load(serverId: String, itemId: String): ReadaloudResumePosition? = null
    }

    // A ReaderSyncFactory whose two attach methods return null, so the player stays on its single-peer
    // path. The super-constructor deps are inert stubs — never touched once both methods short-circuit.
    private class TestReaderSyncFactory : ReaderSyncFactory(
        FakeLinkRepository,
        StubServer,
        FakeTokenStorage,
        StubIndexStore,
        StubAbsApi,
        StubLibrary,
        StubLocalStore,
        StubLocalStore,
        StubBuildTrigger,
    ) {
        override suspend fun createIfApplicable(itemId: String): ReaderSyncCoordinator? = null
        override suspend fun createAudiobookFollowIfApplicable(itemId: String): AudiobookFollow? = null
    }

    private object StubServer : ServerRepository {
        override fun observeAll() = throw NotImplementedError()
        override suspend fun getActive(): Server? = null
        override suspend fun authenticate(
            url: ServerUrl,
            username: String,
            password: String,
            insecureAllowed: Boolean,
            serverType: com.riffle.core.domain.ServerType,
        ) = throw NotImplementedError()
        override suspend fun commit(pending: com.riffle.core.domain.PendingServer, hiddenLibraryIds: Set<String>) = throw NotImplementedError()
        override suspend fun setActive(serverId: String) {}
        override suspend fun remove(serverId: String) {}
        override suspend fun getServerVersion(serverId: String): String? = null
    }

    private object StubLibrary : LibraryRepository {
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override suspend fun getItem(serverId: String, itemId: String): LibraryItem? = null
        override suspend fun updateReadingProgress(itemId: String, progress: Float) {}
        override fun observeItem(itemId: String) = throw NotImplementedError()
        override fun observeLibraries() = throw NotImplementedError()
        override fun observeLibraries(serverId: String) = throw NotImplementedError()
        override fun observeLibraryItems(libraryId: String) = throw NotImplementedError()
        override fun observeUngroupedLibraryItems(libraryId: String) = throw NotImplementedError()
        override fun observeInProgressItems(libraryId: String) = throw NotImplementedError()
        override fun observeFinishedItems(libraryId: String) = throw NotImplementedError()
        override fun observeRecentlyAddedItems(libraryId: String) = throw NotImplementedError()
        override fun observeAllBooks(libraryId: String) = throw NotImplementedError()
        override fun observeSeries(libraryId: String) = throw NotImplementedError()
        override fun observeCollections(libraryId: String) = throw NotImplementedError()
        override fun observeSeriesItems(seriesId: String) = throw NotImplementedError()
        override fun observeCollectionItems(collectionId: String) = throw NotImplementedError()
        override suspend fun getLibrary(libraryId: String) = throw NotImplementedError()
        override suspend fun getSeriesIdForItem(serverId: String, itemId: String): String? = null
        override suspend fun markItemOpened(itemId: String) {}
        override suspend fun refreshLibraries() = throw NotImplementedError()
        override suspend fun refreshLibraryItems(libraryId: String) = throw NotImplementedError()
        override suspend fun refreshSeries(libraryId: String) = throw NotImplementedError()
        override suspend fun refreshCollections(libraryId: String) = throw NotImplementedError()
    }

    private object StubIndexStore : CrossEpubIndexStore {
        override suspend fun exists(absChecksum: String, storytellerChecksum: String) = false
        override suspend fun put(absChecksum: String, storytellerChecksum: String, blob: String, builtAt: Long) {}
        override suspend fun load(absChecksum: String, storytellerChecksum: String): CrossEpubIndex? = null
    }

    private object StubAbsApi : AbsSessionApi {
        override suspend fun syncEbookProgress(
            baseUrl: String,
            libraryItemId: String,
            payload: NetworkEbookProgressPayload,
            token: String,
            insecureAllowed: Boolean,
        ) = NetworkSyncSessionResult.NetworkError(RuntimeException())
        override suspend fun syncAudiobookProgress(
            baseUrl: String,
            libraryItemId: String,
            payload: NetworkAudiobookProgressPayload,
            token: String,
            insecureAllowed: Boolean,
        ) = NetworkSyncSessionResult.NetworkError(RuntimeException())
        override suspend fun getProgress(
            baseUrl: String,
            libraryItemId: String,
            token: String,
            insecureAllowed: Boolean,
        ) = NetworkGetProgressResult.NetworkError(RuntimeException())
    }

    private object StubLocalStore : LocalStore {
        override fun get(serverId: String, itemId: String): File? = null
        override suspend fun save(serverId: String, itemId: String, stream: InputStream): File = File("/dev/null")
        override fun delete(serverId: String, itemId: String) {}
        override fun deleteServer(serverId: String) {}
        override fun clear() {}
        override fun listItems(): List<StoredItemRef> = emptyList()
    }

    private object StubBuildTrigger : CrossEpubIndexBuildTrigger {
        override fun enqueueBuild(link: ReadaloudLink) {}
    }
}
