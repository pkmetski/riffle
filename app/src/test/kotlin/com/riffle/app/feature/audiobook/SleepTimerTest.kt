package com.riffle.app.feature.audiobook

import com.riffle.core.network.NetworkResult

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.riffle.app.feature.reader.AudiobookFollow
import com.riffle.app.feature.reader.ProgressFlushScope
import com.riffle.app.testing.TestApplicationScope
import com.riffle.app.feature.reader.ReaderSyncCoordinator
import com.riffle.app.feature.reader.ReaderSyncFactory
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
import com.riffle.core.domain.AudiobookTrackSpan
import com.riffle.core.domain.BookmarkTitleBuilder
import com.riffle.core.domain.BundleAudiobookSource
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
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.StoredItemRef
import com.riffle.core.domain.Clock
import com.riffle.core.domain.SyncPositionStore
import com.riffle.core.domain.TokenStorage
import com.riffle.core.logging.RecordingLogger
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkEbookProgressPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // ── formatCountdown ──────────────────────────────────────────────────────────

    @Test
    fun `formatCountdown formats minutes and seconds`() {
        val mode = SleepTimerMode.CountDown(remainingMs = 30 * 60 * 1_000L)
        assertEquals("30:00", mode.formatCountdown())
    }

    @Test
    fun `formatCountdown pads seconds with leading zero`() {
        val mode = SleepTimerMode.CountDown(remainingMs = 5 * 60 * 1_000L + 7_000L)
        assertEquals("5:07", mode.formatCountdown())
    }

    @Test
    fun `formatCountdown returns empty string for non-CountDown mode`() {
        assertEquals("", SleepTimerMode.None.formatCountdown())
        assertEquals("", SleepTimerMode.EndOfChapter.formatCountdown())
    }

    @Test
    fun `formatCountdown handles sub-minute duration`() {
        val mode = SleepTimerMode.CountDown(remainingMs = 30_000L)
        assertEquals("0:30", mode.formatCountdown())
    }

    @Test
    fun `formatCountdown handles zero remaining`() {
        val mode = SleepTimerMode.CountDown(remainingMs = 0L)
        assertEquals("0:00", mode.formatCountdown())
    }

    // ── shared timeline ──────────────────────────────────────────────────────────

    private val serverId = "srv-1"
    private val itemId = "item-1"
    private val timeline = AudiobookTimeline(
        durationSec = 1000.0,
        chapters = listOf(
            AudiobookChapter(index = 0, startSec = 0.0, endSec = 500.0, title = "Chapter One"),
            AudiobookChapter(index = 1, startSec = 500.0, endSec = 1000.0, title = "Chapter Two"),
        ),
    )

    // ── FakeController ───────────────────────────────────────────────────────────

    private class FakeReadaloudController : com.riffle.app.feature.reader.readaloud.ReadaloudController()

    private class FakeController(position: Double = 0.0) : AudiobookController() {
        var position: Double = position

        override val state = MutableStateFlow(AudiobookController.PlaybackState(positionSec = position))

        private val _sleepTimer = MutableStateFlow<SleepTimerMode>(SleepTimerMode.None)
        override val sleepTimer: StateFlow<SleepTimerMode> = _sleepTimer.asStateFlow()

        val setSleepTimerCalls = mutableListOf<SleepTimerMode>()
        var cancelCalled = 0
        var triggerNowCalled = 0
        val seeks = mutableListOf<Double>()

        override suspend fun prepare(
            trackUrls: List<String>,
            spans: List<AudiobookTrackSpan>,
            durationSec: Double,
            startAtSec: Double,
            localZipFile: File?,
            coverUri: String?,
        ) { /* no-op */ }

        override fun play() {}
        override fun setSpeed(speed: Float) {}
        override fun currentAbsoluteSec(): Double = position
        override fun seekTo(absoluteSec: Double) { seeks.add(absoluteSec) }

        override fun setSleepTimer(mode: SleepTimerMode) {
            setSleepTimerCalls.add(mode)
            _sleepTimer.value = mode
        }

        override fun cancelSleepTimer() {
            cancelCalled++
            _sleepTimer.value = SleepTimerMode.None
        }

        override fun triggerSleepNow() {
            triggerNowCalled++
            _sleepTimer.value = SleepTimerMode.None
        }
    }

    // ── ViewModel helpers ─────────────────────────────────────────────────────────

    private fun AudiobookPlayerViewModel.clearForTest() {
        this.viewModelScope.cancel()
    }

    private fun buildViewModel(
        controller: FakeController,
        bookmarkStore: AudiobookBookmarkStore = FakeBookmarkStore(),
        connectivity: FakeConnectivityObserver = FakeConnectivityObserver(online = true),
    ): AudiobookPlayerViewModel {
        val session = AudiobookSession(
            trackUrls = listOf("http://x/track0"),
            tracks = listOf(AudiobookTrackSpan(0, 0.0, 1000.0)),
            timeline = timeline,
            serverCurrentTimeSec = 0.0,
            serverLastUpdate = 0L,
        )
        val sharedPositionStore = FakePositionStore()
        return AudiobookPlayerViewModel(
            savedStateHandle = SavedStateHandle(mapOf("itemId" to itemId)),
            audiobookRepository = FakeAudiobookRepository(session),
            audiobookDownloadRepository = NoDownloadRepo,
            bundleAudiobookSource = NoBundleSource,
            libraryObserver = FakeLibraryRepository(),
            updateReadingProgressUseCase = com.riffle.app.testing.NoopUpdateReadingProgress(),
            serverRepository = FakeServerRepository(),
            tokenStorage = FakeTokenStorage,
            controller = controller,
            readaloudController = FakeReadaloudController(),
            audioPlaybackPreferencesStore = FakePrefsStore,
            listeningPreferencesStore = FakeListeningPreferencesStore,
            audioIdentityResolver = FakeIdentityResolver,
            readerSyncFactory = TestReaderSyncFactory(),
            readaloudLinkRepository = FakeLinkRepository,
            readaloudAudioRepository = FakeAudioRepo,
            nowPlayingStore = NowPlayingStore(),
            audiobookPositionStore = sharedPositionStore,
            readingSyncStore = FakeSyncStore(),
            audioSyncStore = FakeSyncStoreDouble(),
            readaloudResumeStore = FakeResumeStore,
            openReconcileTargets = OpenReconcileTargets(),
            progressFlushScope = ProgressFlushScope(TestApplicationScope(CoroutineScope(testDispatcher))),
            bookmarkStore = bookmarkStore,
            connectivityObserver = connectivity,
            audiobookHandoffState = AudiobookHandoffState(),
            followLoopOrchestrator = FollowLoopOrchestrator(
                applicationScope = TestApplicationScope(CoroutineScope(testDispatcher)),
                clock = object : Clock {
                    override fun nowMs(): Long = 0L
                    override fun nowNs(): Long = 0L
                },
                progressFlushScope = ProgressFlushScope(TestApplicationScope(CoroutineScope(testDispatcher))),
            ),
            resumeResolver = AudiobookResumeResolver(
                positionStore = sharedPositionStore,
                clock = object : Clock {
                    override fun nowMs(): Long = 0L
                    override fun nowNs(): Long = 0L
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
                override fun nowMs(): Long = 0L
                override fun nowNs(): Long = 0L
            },
            logger = RecordingLogger(),
        )
    }

    // ── delegation tests ─────────────────────────────────────────────────────────

    @Test
    fun `setSleepTimer delegates to controller with CountDown mode`() = runTest(testDispatcher) {
        val controller = FakeController()
        val vm = buildViewModel(controller)
        runCurrent()

        vm.setSleepTimer(SleepTimerMode.CountDown(30 * 60_000L))
        runCurrent()

        assertEquals(1, controller.setSleepTimerCalls.size)
        assertTrue(controller.setSleepTimerCalls[0] is SleepTimerMode.CountDown)
        assertEquals(30 * 60_000L, (controller.setSleepTimerCalls[0] as SleepTimerMode.CountDown).remainingMs)
        vm.clearForTest()
    }

    @Test
    fun `setSleepTimer delegates to controller with EndOfChapter mode`() = runTest(testDispatcher) {
        val controller = FakeController()
        val vm = buildViewModel(controller)
        runCurrent()

        vm.setSleepTimer(SleepTimerMode.EndOfChapter)
        runCurrent()

        assertEquals(SleepTimerMode.EndOfChapter, controller.setSleepTimerCalls[0])
        assertEquals(SleepTimerMode.EndOfChapter, controller.sleepTimer.value)
        vm.clearForTest()
    }

    @Test
    fun `cancelSleepTimer resets timer to None`() = runTest(testDispatcher) {
        val controller = FakeController()
        val vm = buildViewModel(controller)
        runCurrent()

        vm.setSleepTimer(SleepTimerMode.CountDown(30 * 60_000L))
        runCurrent()
        vm.cancelSleepTimer()
        runCurrent()

        assertEquals(1, controller.cancelCalled)
        assertEquals(SleepTimerMode.None, controller.sleepTimer.value)
        vm.clearForTest()
    }

    @Test
    fun `setting a new timer replaces the previous one`() = runTest(testDispatcher) {
        val controller = FakeController()
        val vm = buildViewModel(controller)
        runCurrent()

        vm.setSleepTimer(SleepTimerMode.CountDown(30 * 60_000L))
        runCurrent()
        vm.setSleepTimer(SleepTimerMode.EndOfChapter)
        runCurrent()

        assertEquals(2, controller.setSleepTimerCalls.size)
        assertEquals(SleepTimerMode.EndOfChapter, controller.sleepTimer.value)
        vm.clearForTest()
    }

    // ── EoC detection tests ───────────────────────────────────────────────────────

    @Test
    fun `EoC mode fires triggerSleepNow when chapter index advances`() = runTest(testDispatcher) {
        // Start in chapter 0 (positionSec = 0.0)
        val controller = FakeController(position = 0.0)
        val vm = buildViewModel(controller)
        runCurrent()

        // Arm the EoC timer
        vm.setSleepTimer(SleepTimerMode.EndOfChapter)
        runCurrent()

        // Advance to chapter 1 by moving position into [500, 1000)
        controller.state.value = AudiobookController.PlaybackState(positionSec = 600.0)
        runCurrent()

        assertEquals(1, controller.triggerNowCalled)
        vm.clearForTest()
    }

    @Test
    fun `EoC mode does NOT fire triggerSleepNow when chapter index decreases`() = runTest(testDispatcher) {
        // Start in chapter 1 (positionSec = 600.0)
        val controller = FakeController(position = 600.0)
        controller.state.value = AudiobookController.PlaybackState(positionSec = 600.0)
        val vm = buildViewModel(controller)
        runCurrent()

        // Arm the EoC timer
        vm.setSleepTimer(SleepTimerMode.EndOfChapter)
        runCurrent()

        // Go backward to chapter 0
        controller.state.value = AudiobookController.PlaybackState(positionSec = 100.0)
        runCurrent()

        assertEquals(0, controller.triggerNowCalled)
        vm.clearForTest()
    }

    @Test
    fun `CountDown mode does NOT fire triggerSleepNow when chapter advances`() = runTest(testDispatcher) {
        // Start in chapter 0
        val controller = FakeController(position = 0.0)
        val vm = buildViewModel(controller)
        runCurrent()

        // Arm a CountDown timer (not EoC)
        vm.setSleepTimer(SleepTimerMode.CountDown(30 * 60_000L))
        runCurrent()

        // Advance to chapter 1
        controller.state.value = AudiobookController.PlaybackState(positionSec = 600.0)
        runCurrent()

        assertEquals(0, controller.triggerNowCalled)
        vm.clearForTest()
    }

    // ── fakes ─────────────────────────────────────────────────────────────────────

    private class FakeConnectivityObserver(online: Boolean) : com.riffle.core.domain.ConnectivityObserver {
        override val isOnline = MutableStateFlow(online)
    }

    private class FakeBookmarkStore : AudiobookBookmarkStore {
        val flow = MutableStateFlow<List<AudiobookBookmark>>(emptyList())
        val hasUnsynced = MutableStateFlow(false)
        override fun observe(serverId: String, itemId: String): Flow<List<AudiobookBookmark>> = flow
        override fun observeForServer(serverId: String): Flow<List<AudiobookBookmark>> = flow
        override fun observeHasUnsynced(serverId: String, itemId: String): Flow<Boolean> = hasUnsynced
        override suspend fun add(serverId: String, itemId: String, positionSec: Double, title: String, now: Long): String = "bm-0"
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
            serverId = serverId,
        )
        override suspend fun getItem(itemId: String): LibraryItem = item
        override suspend fun getItem(serverId: String, itemId: String): LibraryItem = item
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
        override fun observeContinueSeriesItems(libraryId: String) = throw NotImplementedError()
        override fun observeCollectionItems(collectionId: String) = throw NotImplementedError()
        override suspend fun getLibrary(libraryId: String) = throw NotImplementedError()
        override suspend fun getSeriesIdForItem(serverId: String, itemId: String): String? = null
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
        override suspend fun clear(serverId: String, itemId: String) {}
    }

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
        sidecarCache = { _, _ -> null },
        logger = RecordingLogger(),
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

    private object StubLibrary : LibraryObserver {
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override suspend fun getItem(serverId: String, itemId: String): LibraryItem? = null
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
        override fun observeContinueSeriesItems(libraryId: String) = throw NotImplementedError()
        override fun observeCollectionItems(collectionId: String) = throw NotImplementedError()
        override suspend fun getLibrary(libraryId: String) = throw NotImplementedError()
        override suspend fun getSeriesIdForItem(serverId: String, itemId: String): String? = null
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
