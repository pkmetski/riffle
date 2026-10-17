package com.riffle.feature.reader

import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.AudioDownloadResult
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.PositionSnapshot
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudBundleReader
import com.riffle.core.domain.ReadaloudPreferences
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadaloudResumePosition
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SentenceSpanReader
import com.riffle.core.domain.SyncPositionStore
import com.riffle.core.logging.RecordingLogger
import com.riffle.core.models.AudioIdentity
import com.riffle.core.models.LibraryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReadaloudSessionTest {

    private val unconfinedDispatchers = object : DispatcherProvider {
        private val d = Dispatchers.Unconfined
        override val main = d
        override val mainImmediate = d
        override val io = d
        override val default = d
    }

    // ── Fakes ────────────────────────────────────────────────────────────────────────────

    private class FakePlayerController(
        isPlaying: Boolean = false,
        activeFragment: String? = null,
    ) : ReadaloudPlayerController {
        val skipByCalls = mutableListOf<Double>()
        var pauseCalled = 0
        var playCalled = 0
        var prevChapterCalled = 0
        var nextChapterCalled = 0
        var speedSet: Float? = null
        var closeCalled = 0

        private val _state = MutableStateFlow(ReadaloudPlaybackState(isPlaying = isPlaying))
        override val state: StateFlow<ReadaloudPlaybackState> = _state

        private val _activeFragmentRef = MutableStateFlow(activeFragment)
        override val activeFragmentRef: StateFlow<String?> = _activeFragmentRef

        override val narrationProgress: StateFlow<ReadaloudNarrationProgress?> = MutableStateFlow(null)

        override fun pause() { pauseCalled++; _state.value = _state.value.copy(isPlaying = false) }
        override fun play() { playCalled++; _state.value = _state.value.copy(isPlaying = true) }
        override fun skipBy(deltaSec: Double) { skipByCalls.add(deltaSec) }
        override fun previousChapter() { prevChapterCalled++ }
        override fun nextChapter() { nextChapterCalled++ }
        override fun setSpeed(speed: Float) { speedSet = speed }
        override suspend fun open(bookId: String, bundlePath: String, track: ReadaloudTrack) {}
        override suspend fun openStreaming(streamingSession: ReadaloudStreamingSession, track: ReadaloudTrack) {}
        override fun playFromHere(fragmentRef: String) {}
        override fun playFromReaderPosition(href: String, fragmentId: String?) {}
        override fun playFromSecond(globalSec: Double) {}
        override fun close() { closeCalled++; _state.value = _state.value.copy(isPlaying = false); _activeFragmentRef.value = null }
        override fun releaseForHandoff() {}
    }

    private class FakeAudioPlaybackPreferencesStore : AudioPlaybackPreferencesStore {
        val saved = mutableListOf<Pair<AudioIdentity, Float>>()
        override suspend fun load(identity: AudioIdentity): Float? = null
        override suspend fun save(identity: AudioIdentity, speed: Float) { saved.add(identity to speed) }
        override suspend fun clear(identity: AudioIdentity) {}
        override suspend fun rekey(old: AudioIdentity, new: AudioIdentity) {}
    }

    private class FakeListeningPreferencesStore(
        private val skipSec: Int = ListeningPreferencesStore.DEFAULT_SKIP_INTERVAL_SECONDS,
        private val rewindSec: Int = ListeningPreferencesStore.DEFAULT_REWIND_INTERVAL_SECONDS,
    ) : ListeningPreferencesStore {
        override val defaultPlaybackSpeed: Flow<Float> = flowOf(1.0f)
        override val skipIntervalSeconds: Flow<Int> = flowOf(skipSec)
        override val rewindIntervalSeconds: Flow<Int> = flowOf(rewindSec)
        override val rewindOnResumeSeconds: Flow<Int> = flowOf(0)
        override suspend fun setDefaultPlaybackSpeed(speed: Float) {}
        override suspend fun setSkipIntervalSeconds(seconds: Int) {}
        override suspend fun setRewindIntervalSeconds(seconds: Int) {}
        override suspend fun setRewindOnResumeSeconds(seconds: Int) {}
    }

    private class RecordingApplicationScope(scope: CoroutineScope) : ApplicationScope {
        private val _scope = scope
        override val coroutineScope: CoroutineScope = scope
        val flushes = mutableListOf<suspend () -> Unit>()
        override fun launchSurvivable(block: suspend CoroutineScope.() -> Unit): Job {
            flushes.add { block(_scope) }
            return Job()
        }
        override suspend fun <T> withSurvivable(block: suspend CoroutineScope.() -> T): T = block(_scope)
    }

    private object FakeSidecarDelegate : ReadaloudSidecarDelegate {
        override val states: Flow<Map<String, ReadaloudSidecarState>> = flowOf(emptyMap())
        override fun key(sourceId: String, itemId: String): String = "$sourceId/$itemId"
        override fun cachedBundlePath(sourceId: String, itemId: String): String? = null
        override fun prepare(sourceId: String, itemId: String) {}
        override suspend fun tryBuildStreamingSession(sourceId: String, itemId: String): ReadaloudStreamingSession? = null
        override fun remove(sourceId: String, itemId: String) {}
    }

    private object FakeStorytellerSyncDelegate : StorytellerSyncDelegate {
        override suspend fun runCycle(itemId: String, locatorJson: String): ReadaloudServerSyncOutcome =
            ReadaloudServerSyncOutcome.Offline
    }

    private object FakeAudioRepository : ReadaloudAudioRepository {
        override fun isAudioAvailable(sourceId: String, itemId: String): Boolean = false
        override suspend fun probeSizeBytes(sourceId: String, itemId: String): Long? = null
        override suspend fun downloadAudio(sourceId: String, bookId: String, onProgress: (Long, Long) -> Unit): AudioDownloadResult =
            AudioDownloadResult.NoBundle
        override suspend fun removeAudio(sourceId: String, itemId: String): Long = 0L
    }

    private object FakeBundleReader : ReadaloudBundleReader {
        override fun bundlePath(sourceId: String, itemId: String): String? = null
        override suspend fun readTrack(sourceId: String, itemId: String): ReadaloudTrack? = null
    }

    private object FakeConnectivity : ConnectivityObserver {
        override val isOnline: StateFlow<Boolean> = MutableStateFlow(true)
        override fun isMetered(): Boolean = false
    }

    private object FakeReadaloudPreferencesStore : ReadaloudPreferencesStore {
        override val preferences: Flow<ReadaloudPreferences> = flowOf(ReadaloudPreferences())
        override suspend fun update(prefs: ReadaloudPreferences) {}
    }

    private object FakeAudioIdentityResolver : AudioIdentityResolver {
        override suspend fun resolveForStorytellerBook(storytellerSourceId: String, storytellerBookId: String): AudioIdentity =
            AudioIdentity(storytellerSourceId, storytellerBookId)
    }

    private object FakeReadingSyncStore : SyncPositionStore<String> {
        override suspend fun snapshot(sourceId: String, itemId: String): PositionSnapshot<String> =
            PositionSnapshot(position = null, localUpdatedAt = 10L, lastSyncedAt = 5L)
        override suspend fun mirror(sourceId: String, itemId: String, position: String, localUpdatedAt: Long, lastSyncedAt: Long) {}
        override suspend fun acceptServerPosition(sourceId: String, itemId: String, position: String, serverStamp: Long, ifLocalUpdatedAt: Long, deleted: Boolean) = false
        override suspend fun confirmPushed(sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long) = false
        override suspend fun confirmInSync(sourceId: String, itemId: String, ifLocalUpdatedAt: Long) = false
    }

    private object FakeAudioSyncStore : SyncPositionStore<Double> {
        override suspend fun snapshot(sourceId: String, itemId: String): PositionSnapshot<Double> =
            PositionSnapshot(position = null, localUpdatedAt = 10L, lastSyncedAt = 5L)
        override suspend fun mirror(sourceId: String, itemId: String, position: Double, localUpdatedAt: Long, lastSyncedAt: Long) {}
        override suspend fun acceptServerPosition(sourceId: String, itemId: String, position: Double, serverStamp: Long, ifLocalUpdatedAt: Long, deleted: Boolean) = false
        override suspend fun confirmPushed(sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long) = false
        override suspend fun confirmInSync(sourceId: String, itemId: String, ifLocalUpdatedAt: Long) = false
    }

    private object FakeEpubRepository : EpubRepository {
        override suspend fun downloadEpub(item: LibraryItem, onProgress: (Long, Long) -> Unit): EpubDownloadResult =
            EpubDownloadResult.AlreadyDownloaded
        override suspend fun removeDownload(sourceId: String, itemId: String) {}
        override fun isDownloaded(sourceId: String, itemId: String): Boolean = false
        override fun isCached(sourceId: String, itemId: String): Boolean = false
        override suspend fun saveReadingPosition(sourceId: String, itemId: String, cfi: String) {}
    }

    private object FakeReadingPositionStore : ReadingPositionStore {
        override suspend fun save(sourceId: String, itemId: String, payload: String) {}
        override suspend fun load(sourceId: String, itemId: String): String? = null
        override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) {}
        override suspend fun acceptServer(sourceId: String, itemId: String, payload: String, serverStamp: Long) {}
        override suspend fun markSyncedAt(sourceId: String, itemId: String, stamp: Long) {}
    }

    private object FakeReadaloudResumeStore : ReadaloudResumeStore {
        override suspend fun save(sourceId: String, itemId: String, position: ReadaloudResumePosition) {}
        override suspend fun load(sourceId: String, itemId: String): ReadaloudResumePosition? = null
        override suspend fun clear(sourceId: String, itemId: String) {}
    }

    // ── Session factory ───────────────────────────────────────────────────────────────────

    private fun makeSession(
        scope: CoroutineScope,
        playerController: FakePlayerController = FakePlayerController(),
        audioPlaybackPreferencesStore: AudioPlaybackPreferencesStore = FakeAudioPlaybackPreferencesStore(),
        listeningPreferencesStore: ListeningPreferencesStore = FakeListeningPreferencesStore(),
        recordingAppScope: RecordingApplicationScope? = null,
        snapshotPosition: () -> ReadaloudPosition? = { null },
    ): ReadaloudSession {
        val appScope = recordingAppScope ?: RecordingApplicationScope(scope)
        return ReadaloudSession(
            scope = scope,
            snapshotPosition = snapshotPosition,
            playerController = playerController,
            audioRepository = FakeAudioRepository,
            bundleReader = FakeBundleReader,
            sidecarDelegate = FakeSidecarDelegate,
            storytellerSyncDelegate = FakeStorytellerSyncDelegate,
            audioPlaybackPreferencesStore = audioPlaybackPreferencesStore,
            listeningPreferencesStore = listeningPreferencesStore,
            audioIdentityResolver = FakeAudioIdentityResolver,
            readaloudPreferencesStore = FakeReadaloudPreferencesStore,
            readaloudResumeStore = FakeReadaloudResumeStore,
            readingPositionStore = FakeReadingPositionStore,
            readingSyncStore = FakeReadingSyncStore,
            audioSyncStore = FakeAudioSyncStore,
            epubRepository = FakeEpubRepository,
            progressFlushScope = ProgressFlushScope(appScope),
            connectivityObserver = FakeConnectivity,
            dispatchers = unconfinedDispatchers,
            logger = RecordingLogger(),
            extractEpub = { null },
            spanReader = SentenceSpanReader { _ -> emptyList() },
        )
    }

    // ── Tests ────────────────────────────────────────────────────────────────────────────

    @Test
    fun togglePlayPauseSetsFlushesWhenPlaying() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val fakePlayer = FakePlayerController(isPlaying = true, activeFragment = "c01.html#s7")
            val recordingAppScope = RecordingApplicationScope(sessionScope)
            val session = makeSession(
                scope = sessionScope,
                playerController = fakePlayer,
                recordingAppScope = recordingAppScope,
            )

            session.togglePlayPause()

            assertEquals(1, fakePlayer.pauseCalled, "pause() should be called once")
            assertEquals("c01.html#s7", session.parkPolicy.fragmentRef, "parked fragment should match active fragment")
            assertEquals(1, recordingAppScope.flushes.size, "flush should be called once for position write")
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun setSpeedDebouncesPersistenceAtSPEED_SAVE_DEBOUNCE_MS() = runTest(StandardTestDispatcher()) {
        val sessionScope = CoroutineScope(coroutineContext + Job())
        try {
            val fakePlayer = FakePlayerController()
            val store = FakeAudioPlaybackPreferencesStore()
            val identity = AudioIdentity("srv1", "book1")
            val session = makeSession(
                scope = sessionScope,
                playerController = fakePlayer,
                audioPlaybackPreferencesStore = store,
            )
            session.audioSettingsIdentity = identity

            session.setSpeed(1.5f)

            assertEquals(1.5f, fakePlayer.speedSet)
            assertTrue(store.saved.isEmpty(), "Store should not be written before debounce")

            advanceTimeBy(ReadaloudSession.SPEED_SAVE_DEBOUNCE_MS + 10)

            assertEquals(1, store.saved.size, "Store should be written after debounce")
            assertEquals(identity to 1.5f, store.saved.first())
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun rewindSeeksBackwardByRewindIntervalSec() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val rewindSec = 20
            val fakePlayer = FakePlayerController()
            val session = makeSession(
                scope = sessionScope,
                playerController = fakePlayer,
                listeningPreferencesStore = FakeListeningPreferencesStore(rewindSec = rewindSec),
            )

            session.rewind()

            assertEquals(1, fakePlayer.skipByCalls.size)
            assertEquals(-rewindSec.toDouble(), fakePlayer.skipByCalls.first())
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun forwardSeeksBySkipIntervalSec() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val skipSec = 45
            val fakePlayer = FakePlayerController()
            val session = makeSession(
                scope = sessionScope,
                playerController = fakePlayer,
                listeningPreferencesStore = FakeListeningPreferencesStore(skipSec = skipSec),
            )

            session.forward()

            assertEquals(1, fakePlayer.skipByCalls.size)
            assertEquals(skipSec.toDouble(), fakePlayer.skipByCalls.first())
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun previousChapterAndNextChapterDispatchAudioDomainSeeks() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val fakePlayer = FakePlayerController()
            val session = makeSession(scope = sessionScope, playerController = fakePlayer)

            session.previousChapter()
            session.nextChapter()

            assertEquals(1, fakePlayer.prevChapterCalled, "previousChapter must be called once")
            assertEquals(1, fakePlayer.nextChapterCalled, "nextChapter must be called once")
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun onBookClosedResetsAllSessionState() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val session = makeSession(scope = sessionScope)
            session.readaloudPrepared = true
            session.readaloudStarted = true
            session._readaloudOpen.value = true

            session.onBookClosed()

            assertEquals(false, session.readaloudPrepared)
            assertEquals(false, session.readaloudStarted)
            assertEquals(false, session.readaloudOpen.value)
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun resolveMatchedAbsMakesReadaloudVisibleWhenLinkExists() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val session = makeSession(scope = sessionScope)
            session.bind(
                sourceId = "abs-src",
                itemId = "ebook-1",
                isStorytellerService = false,
                audioBookId = "ebook-1",
                audioServerId = "abs-src",
                audioSettingsIdentity = AudioIdentity("abs-src", "ebook-1"),
                audiobookItemId = null,
                effectiveFormattingPreferencesFlow = MutableStateFlow(FormattingPreferences()),
                readerSyncProvider = { null },
                audiobookFollowProvider = { null },
            )
            assertEquals(false, session.readaloudVisible.value, "readaloud must be hidden before ABS link is resolved")

            session.resolveMatchedAbs(
                audioBookId = "st-book-1",
                audioServerId = "st-src",
                audiobookItemId = null,
            )

            assertEquals(true, session.readaloudVisible.value, "readaloud must be visible once the matched ABS link is resolved")
            assertEquals(true, session.readaloudAvailable.value, "readaloud must be enabled once the matched ABS link is resolved")
            assertEquals(null, session.audiobookItemId.value, "audiobookItemId remains null when item is not listenable")
        } finally {
            sessionScope.cancel()
        }
    }
}
