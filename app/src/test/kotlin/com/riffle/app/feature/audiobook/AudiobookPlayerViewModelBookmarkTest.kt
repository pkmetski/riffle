package com.riffle.app.feature.audiobook

import com.riffle.core.network.NetworkResult

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.riffle.app.feature.reader.ReaderSyncCoordinator
import com.riffle.app.feature.reader.ReaderSyncFactory
import com.riffle.app.feature.reader.ProgressFlushScope
import com.riffle.app.testing.TestApplicationScope
import com.riffle.app.feature.reader.AudiobookFollow
import com.riffle.app.playback.NowPlayingStore
import com.riffle.core.data.CrossEpubIndexBuildTrigger
import com.riffle.core.data.OpenReconcileTargets
import com.riffle.core.domain.AudioIdentity
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import com.riffle.core.domain.AudiobookBookmark
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.AudiobookBookmarkStore
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.AudiobookRepository
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.domain.BookmarkTitleBuilder
import com.riffle.core.domain.BundleAudiobookSource
import com.riffle.core.domain.Clock
import com.riffle.core.domain.CrossEpubIndex
import com.riffle.core.domain.CrossEpubIndexStore
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.PositionSnapshot
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudLink
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadaloudResumePosition
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SourceUrl
import com.riffle.core.domain.StoredItemRef
import com.riffle.core.domain.SyncPositionStore
import com.riffle.core.domain.TokenStorage
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.RecordingLogger
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkEbookProgressPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class AudiobookPlayerViewModelBookmarkTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val sourceId = "srv-1"
    private val itemId = "item-1"
    private val fixedNow = 1_700_000_000_000L
    private val timeline = AudiobookTimeline(
        durationSec = 1000.0,
        chapters = listOf(
            AudiobookChapter(index = 0, startSec = 0.0, endSec = 500.0, title = "Chapter One"),
            AudiobookChapter(index = 1, startSec = 500.0, endSec = 1000.0, title = "Chapter Two"),
        ),
    )

    private class FakeReadaloudController : com.riffle.app.feature.reader.readaloud.ReadaloudController()

    // A fake controller whose book-absolute position is settable and whose seekTo is recorded. Only the
    // members the VM actually touches are overridden; the heavy Media3/Context machinery is bypassed.
    private class FakeController(var position: Double) : AudiobookController() {
        val seeks = mutableListOf<Double>()
        var preparedStartAtSec: Double? = null
        override val state = MutableStateFlow(PlaybackState())
        private val ended = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override val playbackEnded: kotlinx.coroutines.flow.SharedFlow<Unit> = ended.asSharedFlow()
        fun emitEnded() { ended.tryEmit(Unit) }
        override suspend fun prepare(
            trackUrls: List<String>,
            spans: List<com.riffle.core.domain.AudiobookTrackSpan>,
            durationSec: Double,
            startAtSec: Double,
            localZipFile: File?,
            coverUri: String?,
        ) { preparedStartAtSec = startAtSec }
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

    // Captures the repo built by the most recent buildViewModel() so a test can read what progress the
    // VM persisted (saveProgress) — used by the rewind-on-resume drift regression test.
    private var lastAudiobookRepo: FakeAudiobookRepository? = null

    private fun buildViewModel(
        controller: FakeController,
        bookmarkStore: AudiobookBookmarkStore,
        connectivity: FakeConnectivityObserver = FakeConnectivityObserver(online = true),
        prefsStore: AudioPlaybackPreferencesStore = FakePrefsStore,
        listeningStore: ListeningPreferencesStore = FakeListeningPreferencesStore,
        positionStore: com.riffle.core.domain.AudiobookPositionStore = FakePositionStore(),
        logger: RecordingLogger = RecordingLogger(),
    ): AudiobookPlayerViewModel {
        val session = AudiobookSession(
            trackUrls = listOf("http://x/track0"),
            tracks = listOf(com.riffle.core.domain.AudiobookTrackSpan(0, 0.0, 1000.0)),
            timeline = timeline,
            serverCurrentTimeSec = 0.0,
            serverLastUpdate = 0L,
        )
        val repo = FakeAudiobookRepository(session)
        lastAudiobookRepo = repo
        return AudiobookPlayerViewModel(
            savedStateHandle = SavedStateHandle(mapOf("itemId" to itemId)),
            audiobookRepository = repo,
            audiobookDownloadRepository = NoDownloadRepo,
            bundleAudiobookSource = NoBundleSource,
            libraryObserver = FakeLibraryRepository(),
            updateReadingProgressUseCase = com.riffle.app.testing.NoopUpdateReadingProgress(),
            sourceRepository = FakeServerRepository(),
            tokenStorage = FakeTokenStorage,
            controller = controller,
            readaloudController = FakeReadaloudController(),
            audioPlaybackPreferencesStore = prefsStore,
            listeningPreferencesStore = listeningStore,
            audioIdentityResolver = FakeIdentityResolver,
            readaloudLinkRepository = FakeLinkRepository,
            readaloudAudioRepository = FakeAudioRepo,
            nowPlayingStore = NowPlayingStore(),
            audiobookPositionStore = positionStore,
            openReconcileTargets = OpenReconcileTargets(),
            progressFlushScope = ProgressFlushScope(TestApplicationScope(CoroutineScope(testDispatcher))),
            bookmarkStore = bookmarkStore,
            connectivityObserver = connectivity,
            audiobookHandoffState = AudiobookHandoffState(),
            followLoopOrchestrator = FollowLoopOrchestrator(
                clock = object : Clock {
                    override fun nowMs(): Long = fixedNow
                    override fun nowNs(): Long = fixedNow * 1_000_000L
                },
                progressFlushScope = ProgressFlushScope(TestApplicationScope(CoroutineScope(testDispatcher))),
            ),
            resumeResolver = AudiobookResumeResolver(
                positionStore = positionStore,
                clock = object : Clock {
                    override fun nowMs(): Long = fixedNow
                    override fun nowNs(): Long = fixedNow * 1_000_000L
                },
            ),
            reconciliationCoordinator = AudiobookReconciliationCoordinator(
                readerSyncFactory = TestReaderSyncFactory(),
                openReconcileTargets = OpenReconcileTargets(),
                audioSyncStore = FakeSyncStoreDouble(),
                readingSyncStore = FakeSyncStore(),
                readaloudResumeStore = FakeResumeStore,
            ),
            clock = object : Clock {
                override fun nowMs(): Long = fixedNow
                override fun nowNs(): Long = fixedNow * 1_000_000L
            },
            logger = logger,
        )
    }

    @Test
    fun `currentPositionSec reports the controller's live book-absolute position`() = runTest(testDispatcher) {
        val controller = FakeController(position = 540.0)
        val vm = buildViewModel(controller, FakeBookmarkStore())
        runCurrent()

        assertEquals(540.0, vm.currentPositionSec(), 0.0001)
        vm.clearForTest()
    }

    @Test
    fun `defaultBookmarkTitle matches BookmarkTitleBuilder for the pinned position`() = runTest(testDispatcher) {
        val controller = FakeController(position = 540.0)
        val vm = buildViewModel(controller, FakeBookmarkStore())
        runCurrent()

        assertEquals(
            BookmarkTitleBuilder.defaultTitle(timeline, 540.0),
            vm.defaultBookmarkTitle(540.0),
        )
        vm.clearForTest()
    }

    @Test
    fun `addBookmark forwards sourceId itemId the pinned position title and the fixed clock to the store`() = runTest(testDispatcher) {
        val controller = FakeController(position = 321.0)
        val store = FakeBookmarkStore()
        val vm = buildViewModel(controller, store)
        runCurrent()

        // The UI pins the position when the dialog opens; the live playhead has since drifted to 999,
        // but the bookmark must be saved at the pinned 321.0, not the drifted position.
        controller.position = 999.0
        vm.addBookmark("My title", positionSec = 321.0)
        runCurrent()

        assertEquals(1, store.added.size)
        assertEquals(
            AddCall(sourceId, itemId, 321.0, "My title", fixedNow),
            store.added.single(),
        )
        vm.clearForTest()
    }

    @Test
    fun `bookmarks emitted by the store appear in uiState`() = runTest(testDispatcher) {
        val controller = FakeController(position = 0.0)
        val store = FakeBookmarkStore()
        val vm = buildViewModel(controller, store)
        runCurrent()

        val bookmark = AudiobookBookmark(id = "bm-1", sourceId = "srv-1", itemId = "item-1", positionSec = 42.0, title = "Saved", createdAt = fixedNow)
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
        val bookmark = AudiobookBookmark(id = "bm-1", sourceId = "srv-1", itemId = "item-1", positionSec = 42.0, title = "Saved", createdAt = fixedNow)
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
    fun `controller playbackEnded emits a Finished UI event so the screen can close the player`() = runTest(testDispatcher) {
        val controller = FakeController(position = 0.0)
        val vm = buildViewModel(controller, FakeBookmarkStore())
        runCurrent()

        val collected = mutableListOf<AudiobookPlayerEvent>()
        val job = launch { vm.events.take(1).toList(collected) }
        runCurrent()

        controller.emitEnded()
        runCurrent()
        job.join()

        assertEquals(listOf(AudiobookPlayerEvent.Finished), collected)
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

    // --- speed persistence ---

    @Test
    fun `setSpeed saves with the resolved identity after the debounce window`() = runTest(testDispatcher) {
        val store = RecordingPrefsStore()
        val vm = buildViewModel(FakeController(0.0), FakeBookmarkStore(), prefsStore = store)
        runCurrent() // openBook() completes → audioSettingsIdentity = AudioIdentity(sourceId, itemId)

        vm.setSpeed(1.5f)
        advanceTimeBy(401L) // > SPEED_SAVE_DEBOUNCE_MS (400ms)
        runCurrent()

        assertNotNull("save() must have been called", store.lastSave)
        assertEquals(AudioIdentity(sourceId, itemId), store.lastSave!!.first)
        assertEquals(1.5f, store.lastSave!!.second)
        vm.clearForTest()
    }

    // --- rewind on resume ---

    @Test
    fun `togglePlayPause when resuming seeks back by rewindOnResumeSeconds before playing`() = runTest(testDispatcher) {
        val ctrl = FakeController(position = 30.0)
        val store = MutableListeningPreferencesStore().apply { rewindOnResumeSeconds.value = 10 }
        val vm = buildViewModel(ctrl, FakeBookmarkStore(), listeningStore = store)
        runCurrent()

        vm.togglePlayPause() // not playing → resume path
        runCurrent()

        // Must seek to 30 - 10 = 20 before playing
        assertEquals(listOf(20.0), ctrl.seeks)
        vm.clearForTest()
    }

    @Test
    fun `togglePlayPause when resuming with zero rewindOnResumeSeconds does not seek`() = runTest(testDispatcher) {
        val ctrl = FakeController(position = 30.0)
        val store = MutableListeningPreferencesStore().apply { rewindOnResumeSeconds.value = 0 }
        val vm = buildViewModel(ctrl, FakeBookmarkStore(), listeningStore = store)
        runCurrent()

        vm.togglePlayPause()
        runCurrent()

        assertEquals(emptyList<Double>(), ctrl.seeks)
        vm.clearForTest()
    }

    @Test
    fun `togglePlayPause when resuming at position less than rewindSec clamps to zero`() = runTest(testDispatcher) {
        val ctrl = FakeController(position = 5.0)
        val store = MutableListeningPreferencesStore().apply { rewindOnResumeSeconds.value = 10 }
        val vm = buildViewModel(ctrl, FakeBookmarkStore(), listeningStore = store)
        runCurrent()

        vm.togglePlayPause()
        runCurrent()

        assertEquals(listOf(0.0), ctrl.seeks)
        vm.clearForTest()
    }

    @Test
    fun `openBook resuming an in-progress book rewinds the start position by rewindOnResumeSeconds`() = runTest(testDispatcher) {
        // Reopening a book is the most common "resume" and plays directly (no togglePlayPause), so the
        // rewind has to be applied to the prepared start position — otherwise the setting only ever fires
        // on an in-player pause→play and looks broken when you leave and come back.
        val ctrl = FakeController(position = 0.0)
        val store = MutableListeningPreferencesStore().apply { rewindOnResumeSeconds.value = 10 }
        val vm = buildViewModel(
            ctrl,
            FakeBookmarkStore(),
            listeningStore = store,
            positionStore = FakePositionStore(savedSec = 540.0, savedUpdatedAt = fixedNow),
        )
        runCurrent()

        // Resume position 540 rewound by 10 → prepared at 530.
        assertEquals(530.0, ctrl.preparedStartAtSec!!, 0.0001)
        vm.clearForTest()
    }

    @Test
    fun `openBook with zero rewindOnResumeSeconds prepares at the unmodified resume position`() = runTest(testDispatcher) {
        val ctrl = FakeController(position = 0.0)
        val store = MutableListeningPreferencesStore().apply { rewindOnResumeSeconds.value = 0 }
        val vm = buildViewModel(
            ctrl,
            FakeBookmarkStore(),
            listeningStore = store,
            positionStore = FakePositionStore(savedSec = 540.0, savedUpdatedAt = fixedNow),
        )
        runCurrent()

        assertEquals(540.0, ctrl.preparedStartAtSec!!, 0.0001)
        vm.clearForTest()
    }

    @Test
    fun `openBook resuming near the start clamps the rewound position to zero`() = runTest(testDispatcher) {
        val ctrl = FakeController(position = 0.0)
        val store = MutableListeningPreferencesStore().apply { rewindOnResumeSeconds.value = 10 }
        val vm = buildViewModel(
            ctrl,
            FakeBookmarkStore(),
            listeningStore = store,
            positionStore = FakePositionStore(savedSec = 4.0, savedUpdatedAt = fixedNow),
        )
        runCurrent()

        assertEquals(0.0, ctrl.preparedStartAtSec!!, 0.0001)
        vm.clearForTest()
    }

    @Test
    fun `open-path rewind shifts only playback start, not the persisted resume floor`() = runTest(testDispatcher) {
        // Regression: the rewind must NOT lower the resume floor, or pausing/closing while still inside the
        // rewound seconds would persist that lower position — letting repeated open→close cycles creep the
        // saved position backward by rewindOnResume each time. Resume 540, rewind 10 → playback starts at
        // 530, but the floor stays 540; pausing at the rewound 530 (below the floor) must persist nothing.
        val ctrl = FakeController(position = 0.0)
        val store = MutableListeningPreferencesStore().apply { rewindOnResumeSeconds.value = 10 }
        val vm = buildViewModel(
            ctrl,
            FakeBookmarkStore(),
            listeningStore = store,
            positionStore = FakePositionStore(savedSec = 540.0, savedUpdatedAt = fixedNow),
        )
        runCurrent()
        assertEquals(530.0, ctrl.preparedStartAtSec!!, 0.0001) // playback starts at the rewound point

        // Simulate the controller settled at the rewound start and playing, then pause.
        ctrl.position = 530.0
        ctrl.state.value = AudiobookController.PlaybackState(isPlaying = true)
        vm.togglePlayPause() // playing → pause → pushProgressOnStop
        runCurrent()

        // 530 is below the 540 floor, so no backward progress is written.
        assertEquals(emptyList<Double>(), lastAudiobookRepo!!.savedProgress)
        vm.clearForTest()
    }

    @Test
    fun `init emits AB-VM-init-start log on the Handoff channel`() = runTest(testDispatcher) {
        val logger = RecordingLogger()
        val vm = buildViewModel(FakeController(position = 0.0), FakeBookmarkStore(), logger = logger)
        runCurrent()

        val messages = logger.records(LogChannel.Handoff).map { it.message }
        assertTrue(
            "expected an 'AB.VM init start' Handoff log but got $messages",
            messages.any { it.startsWith("AB.VM init start itemId=") },
        )
        vm.clearForTest()
    }

    // --- fakes ---

    private class FakeConnectivityObserver(online: Boolean) : com.riffle.core.domain.ConnectivityObserver {
        override val isOnline = MutableStateFlow(online)
    }

    private data class AddCall(
        val sourceId: String,
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
        override fun observe(sourceId: String, itemId: String): Flow<List<AudiobookBookmark>> = flow
        override fun observeForSource(sourceId: String): Flow<List<AudiobookBookmark>> = flow
        override fun observeHasUnsynced(sourceId: String, itemId: String): Flow<Boolean> = hasUnsynced
        override suspend fun add(sourceId: String, itemId: String, positionSec: Double, title: String, now: Long): String {
            added.add(AddCall(sourceId, itemId, positionSec, title, now))
            lastId = "bm-${seq++}"
            return lastId
        }
        override suspend fun rename(id: String, title: String, now: Long) {}
        override suspend fun delete(id: String, now: Long) {}
    }

    private class FakeAudiobookRepository(private val session: AudiobookSession) : AudiobookRepository {
        val savedProgress = mutableListOf<Double>()
        override suspend fun openSession(sourceId: String, itemId: String): AudiobookSession = session
        override suspend fun saveProgress(sourceId: String, itemId: String, positionSec: Double, durationSec: Double) {
            savedProgress.add(positionSec)
        }
    }

    private object NoDownloadRepo : AudiobookDownloadRepository {
        override fun isDownloaded(sourceId: String, itemId: String) = false
        override fun localSession(sourceId: String, itemId: String): AudiobookSession? = null
        override suspend fun download(
            sourceId: String,
            itemId: String,
            onProgress: (Long, Long) -> Unit,
        ) = com.riffle.core.domain.AudiobookDownloadResult.Success
        override suspend fun remove(sourceId: String, itemId: String): Long = 0
    }

    private object NoBundleSource : BundleAudiobookSource {
        override suspend fun localSession(sourceId: String, itemId: String): AudiobookSession? = null
        override fun isAvailableOffline(sourceId: String, itemId: String) = false
    }

    private inner class FakeLibraryRepository : LibraryObserver {
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
            sourceId = sourceId,
        )
        override suspend fun getItem(itemId: String): LibraryItem = item
        override suspend fun getItem(sourceId: String, itemId: String): LibraryItem = item
        override fun observeItem(itemId: String) = throw NotImplementedError()
        override fun observeLibraries() = throw NotImplementedError()
        override fun observeLibraries(sourceId: String) = throw NotImplementedError()
        override fun observeLibraryItems(libraryId: String) = throw NotImplementedError()
        override fun observeUngroupedLibraryItems(libraryId: String) = throw NotImplementedError()
        override fun observeInProgressItems(libraryId: String) = throw NotImplementedError()
        override fun observeFinishedItems(libraryId: String) = throw NotImplementedError()
        override fun observeRecentlyAddedItems(libraryId: String) = throw NotImplementedError()
        override fun observeContinueSeriesItems(libraryId: String) = throw NotImplementedError()
        override fun observeAllBooks(libraryId: String) = throw NotImplementedError()
        override fun observeSeries(libraryId: String) = throw NotImplementedError()
        override fun observeCollections(libraryId: String) = throw NotImplementedError()
        override fun observeSeriesItems(seriesId: String) = throw NotImplementedError()
        override fun observeCollectionItems(collectionId: String) = throw NotImplementedError()
        override suspend fun getLibrary(libraryId: String) = throw NotImplementedError()
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
    }

    private inner class FakeServerRepository : SourceRepository {
        private val server = Source(
            id = sourceId,
            url = SourceUrl.parse("http://example.com")!!,
            isActive = true,
            insecureConnectionAllowed = false,
            username = "u",
        )
        override fun observeAll() = throw NotImplementedError()
        override suspend fun getActive(): Source = server
        override suspend fun getById(sourceId: String): Source = server
        override suspend fun commit(
            pending: com.riffle.core.domain.PendingSource,
            hiddenLibraryIds: Set<String>,
        ) = throw NotImplementedError()
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private object FakeTokenStorage : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) {}
        override suspend fun getToken(sourceId: String): String = "token"
        override suspend fun deleteToken(sourceId: String) {}
    }

    private object FakePrefsStore : AudioPlaybackPreferencesStore {
        override suspend fun load(identity: AudioIdentity): Float? = null
        override suspend fun save(identity: AudioIdentity, speed: Float) {}
        override suspend fun clear(identity: AudioIdentity) {}
        override suspend fun rekey(old: AudioIdentity, new: AudioIdentity) {}
    }

    private class RecordingPrefsStore : AudioPlaybackPreferencesStore {
        var lastSave: Pair<AudioIdentity, Float>? = null
        override suspend fun load(identity: AudioIdentity): Float? = null
        override suspend fun save(identity: AudioIdentity, speed: Float) { lastSave = identity to speed }
        override suspend fun clear(identity: AudioIdentity) {}
        override suspend fun rekey(old: AudioIdentity, new: AudioIdentity) {}
    }

    private object FakeListeningPreferencesStore : ListeningPreferencesStore {
        override val defaultPlaybackSpeed = MutableStateFlow(ListeningPreferencesStore.DEFAULT_PLAYBACK_SPEED)
        override val skipIntervalSeconds = MutableStateFlow(ListeningPreferencesStore.DEFAULT_SKIP_INTERVAL_SECONDS)
        override val rewindIntervalSeconds = MutableStateFlow(ListeningPreferencesStore.DEFAULT_REWIND_INTERVAL_SECONDS)
        override val rewindOnResumeSeconds = MutableStateFlow(ListeningPreferencesStore.DEFAULT_REWIND_ON_RESUME_SECONDS)
        override suspend fun setDefaultPlaybackSpeed(speed: Float) {}
        override suspend fun setSkipIntervalSeconds(seconds: Int) {}
        override suspend fun setRewindIntervalSeconds(seconds: Int) {}
        override suspend fun setRewindOnResumeSeconds(seconds: Int) {}
    }

    private class MutableListeningPreferencesStore : ListeningPreferencesStore {
        override val defaultPlaybackSpeed = MutableStateFlow(ListeningPreferencesStore.DEFAULT_PLAYBACK_SPEED)
        override val skipIntervalSeconds = MutableStateFlow(ListeningPreferencesStore.DEFAULT_SKIP_INTERVAL_SECONDS)
        override val rewindIntervalSeconds = MutableStateFlow(ListeningPreferencesStore.DEFAULT_REWIND_INTERVAL_SECONDS)
        override val rewindOnResumeSeconds = MutableStateFlow(ListeningPreferencesStore.DEFAULT_REWIND_ON_RESUME_SECONDS)
        override suspend fun setDefaultPlaybackSpeed(speed: Float) { defaultPlaybackSpeed.value = speed }
        override suspend fun setSkipIntervalSeconds(seconds: Int) { skipIntervalSeconds.value = seconds }
        override suspend fun setRewindIntervalSeconds(seconds: Int) { rewindIntervalSeconds.value = seconds }
        override suspend fun setRewindOnResumeSeconds(seconds: Int) { rewindOnResumeSeconds.value = seconds }
    }

    private object FakeIdentityResolver : AudioIdentityResolver {
        override suspend fun resolveForStorytellerBook(
            storytellerSourceId: String,
            storytellerBookId: String,
        ) = AudioIdentity("", "")
    }

    private object FakeLinkRepository : ReadaloudLinkRepository {
        override fun observeAll() = throw NotImplementedError()
        override fun observeLinkedAbsItemIds() = throw NotImplementedError()
        override suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String): ReadaloudLink? = null
        override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String): List<ReadaloudLink> = emptyList()
        override suspend fun unlinkAbsItem(absSourceId: String, absLibraryItemId: String) {}
        override suspend fun countForSource(sourceId: String): Int = 0
    }

    private object FakeAudioRepo : ReadaloudAudioRepository {
        override fun isAudioAvailable(sourceId: String, itemId: String) = false
        override fun bundleFile(sourceId: String, itemId: String): File? = null
        override suspend fun readTrack(sourceId: String, itemId: String): ReadaloudTrack? = null
        override suspend fun probeSizeBytes(sourceId: String, itemId: String): Long? = null
        override suspend fun downloadAudio(
            sourceId: String,
            bookId: String,
            onProgress: (Long, Long) -> Unit,
        ) = com.riffle.core.domain.AudioDownloadResult.NoBundle
        override suspend fun removeAudio(sourceId: String, itemId: String): Long = 0
    }

    private class FakePositionStore(
        private val savedSec: Double? = null,
        private val savedUpdatedAt: Long = 0,
    ) : com.riffle.core.domain.AudiobookPositionStore {
        override suspend fun save(sourceId: String, itemId: String, payload: Double) {}
        override suspend fun load(sourceId: String, itemId: String): Double? = savedSec
        override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long = savedUpdatedAt
        override suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun acceptServer(sourceId: String, itemId: String, payload: Double, serverStamp: Long) {}
        override suspend fun markSyncedAt(sourceId: String, itemId: String, stamp: Long) { }
        override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) {}
    }

    private class FakeSyncStore : SyncPositionStore<String> {
        override suspend fun snapshot(sourceId: String, itemId: String) = PositionSnapshot<String>(null, 0, 0)
        override suspend fun acceptServerPosition(sourceId: String, itemId: String, position: String, serverStamp: Long, ifLocalUpdatedAt: Long) = false
        override suspend fun confirmPushed(sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long) = false
        override suspend fun confirmInSync(sourceId: String, itemId: String, ifLocalUpdatedAt: Long) = false
        override suspend fun mirror(sourceId: String, itemId: String, position: String, localUpdatedAt: Long, lastSyncedAt: Long) {}
    }

    private class FakeSyncStoreDouble : SyncPositionStore<Double> {
        override suspend fun snapshot(sourceId: String, itemId: String) = PositionSnapshot<Double>(null, 0, 0)
        override suspend fun acceptServerPosition(sourceId: String, itemId: String, position: Double, serverStamp: Long, ifLocalUpdatedAt: Long) = false
        override suspend fun confirmPushed(sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long) = false
        override suspend fun confirmInSync(sourceId: String, itemId: String, ifLocalUpdatedAt: Long) = false
        override suspend fun mirror(sourceId: String, itemId: String, position: Double, localUpdatedAt: Long, lastSyncedAt: Long) {}
    }

    private object FakeResumeStore : ReadaloudResumeStore {
        override suspend fun save(sourceId: String, itemId: String, position: ReadaloudResumePosition) {}
        override suspend fun load(sourceId: String, itemId: String): ReadaloudResumePosition? = null
        override suspend fun clear(sourceId: String, itemId: String) {}
    }

    // A ReaderSyncFactory whose two attach methods return null, so the player stays on its single-peer
    // path. The super-constructor deps are inert stubs — never touched once both methods short-circuit.
    private class TestReaderSyncFactory : ReaderSyncFactory(
        FakeLinkRepository,
        StubServer,
        StubCatalogRegistry,
        StubIndexStore,
        StubLibrary,
        StubLocalStore,
        StubLocalStore,
        StubBuildTrigger,
        sidecarCache = { _, _ -> null },
        clock = object : com.riffle.core.domain.Clock { override fun nowMs() = 0L; override fun nowNs() = 0L },
        logger = RecordingLogger(),
    ) {
        override suspend fun createIfApplicable(itemId: String): ReaderSyncCoordinator? = null
        override suspend fun createAudiobookFollowIfApplicable(itemId: String): AudiobookFollow? = null
    }

    private object StubCatalogRegistry : com.riffle.core.catalog.CatalogRegistry {
        override suspend fun forActive(): com.riffle.core.catalog.Catalog? = null
        override suspend fun forSource(source: Source): com.riffle.core.catalog.Catalog? = null
        override suspend fun forSourceId(sourceId: String): com.riffle.core.catalog.Catalog? = null
    }

    private object StubServer : SourceRepository {
        override fun observeAll() = throw NotImplementedError()
        override suspend fun getActive(): Source? = null
        override suspend fun commit(pending: com.riffle.core.domain.PendingSource, hiddenLibraryIds: Set<String>) = throw NotImplementedError()
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private object StubLibrary : LibraryObserver {
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? = null
        override fun observeItem(itemId: String) = throw NotImplementedError()
        override fun observeLibraries() = throw NotImplementedError()
        override fun observeLibraries(sourceId: String) = throw NotImplementedError()
        override fun observeLibraryItems(libraryId: String) = throw NotImplementedError()
        override fun observeUngroupedLibraryItems(libraryId: String) = throw NotImplementedError()
        override fun observeInProgressItems(libraryId: String) = throw NotImplementedError()
        override fun observeFinishedItems(libraryId: String) = throw NotImplementedError()
        override fun observeRecentlyAddedItems(libraryId: String) = throw NotImplementedError()
        override fun observeContinueSeriesItems(libraryId: String) = throw NotImplementedError()
        override fun observeAllBooks(libraryId: String) = throw NotImplementedError()
        override fun observeSeries(libraryId: String) = throw NotImplementedError()
        override fun observeCollections(libraryId: String) = throw NotImplementedError()
        override fun observeSeriesItems(seriesId: String) = throw NotImplementedError()
        override fun observeCollectionItems(collectionId: String) = throw NotImplementedError()
        override suspend fun getLibrary(libraryId: String) = throw NotImplementedError()
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
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
        ) = NetworkResult.Offline(RuntimeException())
        override suspend fun syncAudiobookProgress(
            baseUrl: String,
            libraryItemId: String,
            payload: NetworkAudiobookProgressPayload,
            token: String,
            insecureAllowed: Boolean,
        ) = NetworkResult.Offline(RuntimeException())
        override suspend fun getProgress(
            baseUrl: String,
            libraryItemId: String,
            token: String,
            insecureAllowed: Boolean,
        ) = NetworkResult.Offline(RuntimeException())
    }

    private object StubLocalStore : LocalStore {
        override fun get(sourceId: String, itemId: String): File? = null
        override suspend fun save(sourceId: String, itemId: String, stream: InputStream): File = File("/dev/null")
        override fun delete(sourceId: String, itemId: String) {}
        override fun deleteSource(sourceId: String) {}
        override fun clear() {}
        override fun listItems(): List<StoredItemRef> = emptyList()
    }

    private object StubBuildTrigger : CrossEpubIndexBuildTrigger {
        override fun enqueueBuild(link: ReadaloudLink) {}
    }
}
