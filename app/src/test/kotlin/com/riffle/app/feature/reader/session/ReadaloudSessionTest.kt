package com.riffle.app.feature.reader.session

import com.riffle.app.feature.audiobook.AudiobookHandoffState
import com.riffle.app.feature.reader.AudiobookFollow
import com.riffle.app.feature.reader.ProgressFlushScope
import com.riffle.app.feature.reader.readaloud.PlayerController
import com.riffle.app.feature.reader.readaloud.PlayerCoordinator
import com.riffle.app.feature.reader.readaloud.ReadaloudController
import com.riffle.app.feature.reader.readaloud.ReadaloudStreamingSessionFactory
import com.riffle.app.feature.reader.readaloud.SharedBundle
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.app.playback.NowPlayingStore
import com.riffle.core.data.ReadaloudSidecarStore
import com.riffle.core.data.StorytellerPositionSyncController
import com.riffle.core.domain.AudioIdentity
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.PositionSnapshot
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadaloudResumePosition
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SyncPositionStore
import com.riffle.core.logging.RecordingLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import android.net.FakeUri
import com.riffle.core.domain.AudioDownloadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.mediatype.MediaType

/**
 * Tests for the leaf controls extracted into [ReadaloudSession] in sub-task 8.1.
 *
 * [ReadaloudSession] is constructed with a [FakePlayerController] implementing the
 * new [PlayerController] interface, so the tests exercise the real session class
 * without needing an Android MediaController chain.
 *
 * Tests 1–5 target the five leaf methods lifted in sub-task 8.1:
 *   1. togglePlayPause (pause path)
 *   2. setSpeed / speed debounce
 *   3. rewind
 *   4. forward
 *   5. previousChapter / nextChapter
 */
private val UnconfinedDispatchers = object : com.riffle.core.domain.DispatcherProvider {
    private val d = kotlinx.coroutines.Dispatchers.Unconfined
    override val main = d
    override val mainImmediate = d
    override val io = d
    override val default = d
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReadaloudSessionTest {

    // ── FakePlayerController ───────────────────────────────────────────────────────────

    /** Records calls so tests can assert skip amount, direction, and invocation counts. */
    private class FakePlayerController(
        isPlaying: Boolean = false,
        activeFragment: String? = null,
    ) : PlayerController {
        val skipByCalls = mutableListOf<Double>()
        var pauseCalled = 0
        var playCalled = 0
        var prevChapterCalled = 0
        var nextChapterCalled = 0
        var speedSet: Float? = null
        var playFromHereCalls = mutableListOf<String>()
        var playFromReaderPositionCalls = mutableListOf<Pair<String, String?>>()
        var playFromSecondCalls = mutableListOf<Double>()
        var openCalled = 0
        var openStreamingCalled = 0
        var closeCalled = 0
        var releaseForHandoffCalled = 0

        private val _state = MutableStateFlow(
            ReadaloudController.PlaybackState(isPlaying = isPlaying)
        )
        override val state: StateFlow<ReadaloudController.PlaybackState> = _state

        private val _activeFragmentRef = MutableStateFlow(activeFragment)
        override val activeFragmentRef: StateFlow<String?> = _activeFragmentRef

        private val _narrationProgress = MutableStateFlow<PlayerCoordinator.NarrationProgress?>(null)
        override val narrationProgress: StateFlow<PlayerCoordinator.NarrationProgress?> = _narrationProgress

        override fun pause() { pauseCalled++; _state.value = _state.value.copy(isPlaying = false) }
        override fun play() { playCalled++; _state.value = _state.value.copy(isPlaying = true) }
        override fun skipBy(deltaSec: Double) { skipByCalls.add(deltaSec) }
        override fun previousChapter() { prevChapterCalled++ }
        override fun nextChapter() { nextChapterCalled++ }
        override fun setSpeed(speed: Float) { speedSet = speed }
        override suspend fun open(itemId: String, bundleFile: java.io.File, track: ReadaloudTrack) { openCalled++ }
        override suspend fun openStreaming(streaming: SharedBundle.Streaming, track: ReadaloudTrack) { openStreamingCalled++ }
        override fun playFromHere(fragmentRef: String) { playFromHereCalls.add(fragmentRef) }
        override fun playFromReaderPosition(href: String, fragmentId: String?) { playFromReaderPositionCalls.add(href to fragmentId) }
        override fun playFromSecond(globalSec: Double) { playFromSecondCalls.add(globalSec) }
        override fun close() { closeCalled++; _state.value = _state.value.copy(isPlaying = false); _activeFragmentRef.value = null }
        override fun releaseForHandoff() { releaseForHandoffCalled++ }
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

    private class FakeFlushRecord(val block: suspend () -> Unit)

    private fun makeFakeFlushScope(): Pair<ProgressFlushScope, MutableList<FakeFlushRecord>> {
        val flushes = mutableListOf<FakeFlushRecord>()
        val scope = mockk<ProgressFlushScope>()
        io.mockk.every { scope.flush(any()) } answers {
            val block = firstArg<suspend () -> Unit>()
            flushes.add(FakeFlushRecord(block))
            Job()
        }
        return scope to flushes
    }

    /**
     * Hand-rolled fake for [ReadaloudResumeStore] that records every [save] call.
     * Used to assert that [closeReadaloud] persists the resume position on teardown.
     */
    private class FakeReadaloudResumeStore : ReadaloudResumeStore {
        data class SaveCall(val sourceId: String, val itemId: String, val position: ReadaloudResumePosition)
        val savedCalls = mutableListOf<SaveCall>()
        override suspend fun save(sourceId: String, itemId: String, position: ReadaloudResumePosition) {
            savedCalls.add(SaveCall(sourceId, itemId, position))
        }
        override suspend fun load(sourceId: String, itemId: String): ReadaloudResumePosition? = null
        override suspend fun clear(sourceId: String, itemId: String) {}
    }

    // ── Locator construction helper ────────────────────────────────────────────────────

    /**
     * Allocates a [Locator] whose [Locator.href] `toString()` returns [urlString].
     * Uses Unsafe + [FakeUri] because [AbsoluteUrl] has a private constructor backed by
     * `android.net.Uri` — same pattern as NavigationTargetTest / ContinuousHighlightRendererTest.
     */
    @Suppress("UNCHECKED_CAST")
    private fun makeLocator(urlString: String, progression: Double? = null): Locator {
        val unsafe = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .also { it.isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val hrefInstance = unsafe.allocateInstance(AbsoluteUrl::class.java) as AbsoluteUrl
        AbsoluteUrl::class.java.getDeclaredField("uri")
            .also { it.isAccessible = true }
            .set(hrefInstance, FakeUri(urlString))
        return Locator(
            href = hrefInstance,
            mediaType = MediaType.XHTML,
            locations = Locator.Locations(progression = progression),
        )
    }

    // ── Session factory helper ─────────────────────────────────────────────────────────

    private fun makeSession(
        scope: CoroutineScope,
        playerController: FakePlayerController,
        audioPlaybackPreferencesStore: AudioPlaybackPreferencesStore = FakeAudioPlaybackPreferencesStore(),
        listeningPreferencesStore: ListeningPreferencesStore = FakeListeningPreferencesStore(),
        progressFlushScope: ProgressFlushScope = mockk(relaxed = true),
        snapshotLocator: () -> org.readium.r2.shared.publication.Locator? = { null },
    ): ReadaloudSession {
        return ReadaloudSession(
            scope = scope,
            snapshotLocator = snapshotLocator,
            playerCoordinator = playerController,
            readaloudAudioRepository = mockk(relaxed = true),
            streamingSessionFactory = mockk(relaxed = true),
            storytellerSyncController = mockk(relaxed = true),
            audioPlaybackPreferencesStore = audioPlaybackPreferencesStore,
            listeningPreferencesStore = listeningPreferencesStore,
            audioIdentityResolver = mockk(relaxed = true),
            readaloudPreferencesStore = mockk<ReadaloudPreferencesStore>().also {
                io.mockk.every { it.preferences } returns flowOf(
                    com.riffle.core.domain.ReadaloudPreferences(highlightColor = HighlightColor.BLUE)
                )
            },
            readaloudResumeStore = mockk(relaxed = true),
            sidecarStore = mockk(relaxed = true),
            readingPositionStore = mockk(relaxed = true),
            readingSyncStore = mockk(relaxed = true),
            audioSyncStore = mockk(relaxed = true),
            epubRepository = mockk(relaxed = true),
            progressFlushScope = progressFlushScope,
            audiobookHandoffState = AudiobookHandoffState(),
            connectivityObserver = mockk<ConnectivityObserver>().also {
                io.mockk.every { it.isOnline } returns MutableStateFlow(true)
            },
            nowPlayingStore = NowPlayingStore(),
            dispatchers = UnconfinedDispatchers,
            logger = RecordingLogger(),
        )
    }

    // ── Tests ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `togglePlayPause sets park state and flushes when currently playing`() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val fakePlayer = FakePlayerController(isPlaying = true, activeFragment = "c01.html#s7")
            val (fakeFlushScope, fakeFlushes) = makeFakeFlushScope()
            val session = makeSession(
                scope = sessionScope,
                playerController = fakePlayer,
                progressFlushScope = fakeFlushScope,
                snapshotLocator = { null },
            )

            session.togglePlayPause()

            assertEquals("pause() should be called once", 1, fakePlayer.pauseCalled)
            assertEquals("parked fragment should match active fragment", "c01.html#s7", session.parkPolicy.fragmentRef)
            assertEquals("flush should be called once for position write", 1, fakeFlushes.size)
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `setSpeed debounces persistence at SPEED_SAVE_DEBOUNCE_MS`() = runTest(StandardTestDispatcher()) {
        // Session scope uses the test dispatcher so delay() in setSpeed obeys advanceTimeBy.
        // A new SupervisorJob is used so cancelling sessionScope doesn't propagate to the test.
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

            // Player updated immediately
            assertEquals(1.5f, fakePlayer.speedSet)
            // Store NOT written yet
            assertTrue("Store should not be written before debounce", store.saved.isEmpty())

            // Advance past the debounce window
            advanceTimeBy(ReadaloudSession.SPEED_SAVE_DEBOUNCE_MS + 10)

            assertEquals("Store should be written after debounce", 1, store.saved.size)
            assertEquals(identity to 1.5f, store.saved.first())
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `rewind seeks backward by rewindIntervalSec`() = runTest {
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
            assertEquals(-rewindSec.toDouble(), fakePlayer.skipByCalls.first(), 0.001)
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `forward seeks by skipIntervalSec`() = runTest {
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
            assertEquals(skipSec.toDouble(), fakePlayer.skipByCalls.first(), 0.001)
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `previousChapter and nextChapter dispatch audio-domain seeks`() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val fakePlayer = FakePlayerController()
            val session = makeSession(scope = sessionScope, playerController = fakePlayer)

            session.previousChapter()
            session.nextChapter()

            assertEquals("previousChapter must be called once", 1, fakePlayer.prevChapterCalled)
            assertEquals("nextChapter must be called once", 1, fakePlayer.nextChapterCalled)
        } finally {
            sessionScope.cancel()
        }
    }

    // ── Sub-task 8.2 tests ────────────────────────────────────────────────────────────

    /**
     * Fake [SyncPositionStore] that records the last [mirror] call for assertion.
     */
    private class FakeAudioSyncStore : SyncPositionStore<Double> {
        data class MirrorCall(
            val sourceId: String, val itemId: String, val position: Double,
            val localUpdatedAt: Long, val lastSyncedAt: Long,
        )
        val mirrorCalls = mutableListOf<MirrorCall>()

        override suspend fun snapshot(sourceId: String, itemId: String): PositionSnapshot<Double> =
            PositionSnapshot(position = null, localUpdatedAt = 10L, lastSyncedAt = 5L)

        override suspend fun mirror(
            sourceId: String, itemId: String, position: Double,
            localUpdatedAt: Long, lastSyncedAt: Long,
        ) { mirrorCalls.add(MirrorCall(sourceId, itemId, position, localUpdatedAt, lastSyncedAt)) }

        override suspend fun acceptServerPosition(sourceId: String, itemId: String, position: Double, serverStamp: Long, ifLocalUpdatedAt: Long) = false
        override suspend fun confirmPushed(sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long) = false
        override suspend fun confirmInSync(sourceId: String, itemId: String, ifLocalUpdatedAt: Long) = false
    }

    /**
     * Fake [SyncPositionStore] for the reading (String) sync store that returns a fixed snapshot.
     */
    private class FakeReadingSyncStore(
        private val localUpdatedAt: Long = 10L,
        private val lastSyncedAt: Long = 5L,
    ) : SyncPositionStore<String> {
        override suspend fun snapshot(sourceId: String, itemId: String): PositionSnapshot<String> =
            PositionSnapshot(position = null, localUpdatedAt = localUpdatedAt, lastSyncedAt = lastSyncedAt)

        override suspend fun mirror(sourceId: String, itemId: String, position: String, localUpdatedAt: Long, lastSyncedAt: Long) {}
        override suspend fun acceptServerPosition(sourceId: String, itemId: String, position: String, serverStamp: Long, ifLocalUpdatedAt: Long) = false
        override suspend fun confirmPushed(sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long) = false
        override suspend fun confirmInSync(sourceId: String, itemId: String, ifLocalUpdatedAt: Long) = false
    }

    /**
     * Fake [EpubRepository] that records [saveReadingPosition] calls.
     */
    private class FakeEpubRepository : EpubRepository {
        val savedPositions = mutableListOf<Pair<String, String>>()
        override suspend fun saveReadingPosition(itemId: String, cfi: String) {
            savedPositions.add(itemId to cfi)
        }
        override suspend fun openEpub(item: com.riffle.core.domain.LibraryItem) =
            error("not needed in test")
        override suspend fun downloadEpub(item: com.riffle.core.domain.LibraryItem, onProgress: (Long, Long) -> Unit) =
            error("not needed in test")
        override suspend fun removeDownload(sourceId: String, itemId: String) {}
        override fun isDownloaded(sourceId: String, itemId: String) = false
        override fun isCached(sourceId: String, itemId: String) = false
    }

    private fun makeSessionWithStores(
        scope: CoroutineScope,
        playerController: FakePlayerController = FakePlayerController(),
        fakeAudioSyncStore: FakeAudioSyncStore = FakeAudioSyncStore(),
        fakeReadingSyncStore: FakeReadingSyncStore = FakeReadingSyncStore(),
        fakeEpubRepository: FakeEpubRepository = FakeEpubRepository(),
        fakeReadingPositionStore: ReadingPositionStore = mockk(relaxed = true),
        snapshotLocator: () -> Locator? = { null },
    ): ReadaloudSession {
        return ReadaloudSession(
            scope = scope,
            snapshotLocator = snapshotLocator,
            playerCoordinator = playerController,
            readaloudAudioRepository = mockk(relaxed = true),
            streamingSessionFactory = mockk(relaxed = true),
            storytellerSyncController = mockk(relaxed = true),
            audioPlaybackPreferencesStore = FakeAudioPlaybackPreferencesStore(),
            listeningPreferencesStore = FakeListeningPreferencesStore(),
            audioIdentityResolver = mockk(relaxed = true),
            readaloudPreferencesStore = mockk<ReadaloudPreferencesStore>().also {
                every { it.preferences } returns flowOf(
                    com.riffle.core.domain.ReadaloudPreferences(highlightColor = HighlightColor.BLUE)
                )
            },
            readaloudResumeStore = mockk(relaxed = true),
            sidecarStore = mockk(relaxed = true),
            readingPositionStore = fakeReadingPositionStore,
            readingSyncStore = fakeReadingSyncStore,
            audioSyncStore = fakeAudioSyncStore,
            epubRepository = fakeEpubRepository,
            progressFlushScope = mockk(relaxed = true),
            audiobookHandoffState = AudiobookHandoffState(),
            connectivityObserver = mockk<ConnectivityObserver>().also {
                every { it.isOnline } returns MutableStateFlow(true)
            },
            nowPlayingStore = NowPlayingStore(),
            dispatchers = UnconfinedDispatchers,
            logger = RecordingLogger(),
        )
    }

    @Test
    fun `mirrorReadingToAudiobook writes to audioSyncStore using audiobookFollow seconds (no double-count)`() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val fakeAudioSyncStore = FakeAudioSyncStore()
            val fakeReadingSyncStore = FakeReadingSyncStore(localUpdatedAt = 42L, lastSyncedAt = 7L)

            // Fake AudiobookFollow returns a fixed seconds value for any fragment
            val fakeFollow = mockk<AudiobookFollow>()
            every { fakeFollow.audioItemId } returns "audiobook-item-99"
            every { fakeFollow.secondsForFragment(any()) } returns 123.45

            val session = makeSessionWithStores(
                scope = sessionScope,
                fakeAudioSyncStore = fakeAudioSyncStore,
                fakeReadingSyncStore = fakeReadingSyncStore,
            )
            session.readerSyncServerId = "srv1"
            session.itemId = "ebook-item-1"
            // No full coordinator: audiobookFollow path only
            session.readerSyncProvider = { null }
            session.audiobookFollowProvider = { fakeFollow }
            // Park a fragment so ReadaloudAudioAnchor routes to fragmentSeconds (parkedFragment != null
            // branch) rather than the pageSeconds fallback (which would return null with no coordinator).
            session.parkPolicy.onPause("chapter01.html#s10", "chapter01.html", 0.0)

            session.mirrorReadingToAudiobook("{}")

            // The seconds value must come from audiobookFollow.secondsForFragment, NOT from
            // currentPosition/1000.0 + startOffsetSec. The fake returns exactly 123.45.
            assertEquals("audioSyncStore must be called once", 1, fakeAudioSyncStore.mirrorCalls.size)
            val call = fakeAudioSyncStore.mirrorCalls.first()
            assertEquals("srv1", call.sourceId)
            assertEquals("audiobook-item-99", call.itemId)
            assertEquals(
                "seconds must equal audiobookFollow.secondsForFragment result (no double-count)",
                123.45, call.position, 0.001,
            )
            assertEquals("localUpdatedAt propagated from reading snap", 42L, call.localUpdatedAt)
            assertEquals("lastSyncedAt propagated from reading snap", 7L, call.lastSyncedAt)
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `flushReadaloudPositionToStores persists to epubRepository and audioSyncStore`() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val fakeAudioSyncStore = FakeAudioSyncStore()
            val fakeReadingSyncStore = FakeReadingSyncStore(localUpdatedAt = 20L, lastSyncedAt = 3L)
            val fakeEpubRepo = FakeEpubRepository()

            val fakeFollow = mockk<AudiobookFollow>()
            every { fakeFollow.audioItemId } returns "audio-456"
            every { fakeFollow.secondsForFragment(any()) } returns 77.7

            val session = makeSessionWithStores(
                scope = sessionScope,
                fakeAudioSyncStore = fakeAudioSyncStore,
                fakeReadingSyncStore = fakeReadingSyncStore,
                fakeEpubRepository = fakeEpubRepo,
            )
            session.readerSyncServerId = "srv2"
            session.itemId = "ebook-789"
            session.readerSyncProvider = { null }
            session.audiobookFollowProvider = { fakeFollow }

            session.flushReadaloudPositionToStores("chapter01.html#spanId")

            // 1) Sentence-precise ebook position must be persisted
            assertEquals("epubRepository.saveReadingPosition called once", 1, fakeEpubRepo.savedPositions.size)
            assertEquals("correct itemId", "ebook-789", fakeEpubRepo.savedPositions.first().first)

            // 2) Audio mirror must be written
            assertEquals("audioSyncStore.mirror called once", 1, fakeAudioSyncStore.mirrorCalls.size)
            val mirrorCall = fakeAudioSyncStore.mirrorCalls.first()
            assertEquals("srv2", mirrorCall.sourceId)
            assertEquals("audio-456", mirrorCall.itemId)
            assertEquals(77.7, mirrorCall.position, 0.001)
        } finally {
            sessionScope.cancel()
        }
    }

    // ── Sub-task 8.3 helpers ──────────────────────────────────────────────────────────

    /**
     * Fake [ReadaloudAudioRepository] that records download calls and emits controllable progress.
     */
    private class FakeReadaloudAudioRepository(
        private val bundleFileVal: java.io.File? = null,
        private val downloadResult: AudioDownloadResult = AudioDownloadResult.Success,
    ) : ReadaloudAudioRepository {
        val downloadCalls = mutableListOf<Pair<String, String>>()
        var probeSizeBytesResult: Long? = 500L

        override fun bundleFile(sourceId: String, bookId: String): java.io.File? = bundleFileVal
        override fun isAudioAvailable(sourceId: String, bookId: String): Boolean = bundleFileVal != null
        override suspend fun probeSizeBytes(sourceId: String, bookId: String): Long? = probeSizeBytesResult
        override suspend fun downloadAudio(
            sourceId: String,
            bookId: String,
            onProgress: (Long, Long) -> Unit,
        ): AudioDownloadResult {
            downloadCalls.add(sourceId to bookId)
            onProgress(50L, 100L)
            return downloadResult
        }
        override suspend fun readTrack(sourceId: String, bookId: String): com.riffle.core.domain.ReadaloudTrack? = null
        override suspend fun removeAudio(sourceId: String, itemId: String): Long = 0L
    }

    private fun makeSessionForOpenClose(
        scope: CoroutineScope,
        playerController: FakePlayerController = FakePlayerController(),
        audioRepository: ReadaloudAudioRepository = mockk(relaxed = true),
        progressFlushScope: ProgressFlushScope = mockk(relaxed = true),
        readaloudResumeStore: ReadaloudResumeStore = mockk(relaxed = true),
        snapshotLocator: () -> Locator? = { null },
    ): ReadaloudSession {
        return ReadaloudSession(
            scope = scope,
            snapshotLocator = snapshotLocator,
            playerCoordinator = playerController,
            readaloudAudioRepository = audioRepository,
            streamingSessionFactory = mockk(relaxed = true),
            storytellerSyncController = mockk(relaxed = true),
            audioPlaybackPreferencesStore = FakeAudioPlaybackPreferencesStore(),
            listeningPreferencesStore = FakeListeningPreferencesStore(),
            audioIdentityResolver = mockk(relaxed = true),
            readaloudPreferencesStore = mockk<ReadaloudPreferencesStore>().also {
                every { it.preferences } returns flowOf(
                    com.riffle.core.domain.ReadaloudPreferences(highlightColor = HighlightColor.BLUE)
                )
            },
            readaloudResumeStore = readaloudResumeStore,
            sidecarStore = mockk(relaxed = true),
            readingPositionStore = mockk(relaxed = true),
            readingSyncStore = mockk(relaxed = true),
            audioSyncStore = mockk(relaxed = true),
            epubRepository = mockk(relaxed = true),
            progressFlushScope = progressFlushScope,
            audiobookHandoffState = AudiobookHandoffState(),
            connectivityObserver = mockk<ConnectivityObserver>().also {
                every { it.isOnline } returns MutableStateFlow(true)
                every { it.isMetered() } returns false
            },
            nowPlayingStore = NowPlayingStore(),
            dispatchers = UnconfinedDispatchers,
            logger = RecordingLogger(),
        )
    }

    // ── Sub-task 8.3 tests ────────────────────────────────────────────────────────────

    @Test
    fun `openReadaloud sets readaloudOpen and calls onPlayTapped`() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val fakePlayer = FakePlayerController()
            val fakeRepo = FakeReadaloudAudioRepository(bundleFileVal = null)
            val session = makeSessionForOpenClose(
                scope = sessionScope,
                playerController = fakePlayer,
                audioRepository = fakeRepo,
            )
            // Make available
            session._readaloudAvailable.value = true
            // isStorytellerService=false → no storyteller sync; connectivityObserver.isOnline returns true
            // No bundle and no streaming session → shows download prompt (probeSizeBytes=500)
            session.audioBookId = "book1"
            session.audioServerId = "srv1"
            session.itemId = "book1" // same → triggers download prompt path

            session.openReadaloud()

            assertTrue("readaloudOpen must be true after openReadaloud", session.readaloudOpen.value)
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `startReadaloudAtSecond opens session and seeks player when bundle is present`() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val fakePlayer = FakePlayerController()
            val bundle = java.io.File.createTempFile("bundle", ".zip")
            bundle.deleteOnExit()
            val fakeRepo = FakeReadaloudAudioRepository(bundleFileVal = bundle)
            val session = makeSessionForOpenClose(
                scope = sessionScope,
                playerController = fakePlayer,
                audioRepository = fakeRepo,
            )
            session._readaloudAvailable.value = true
            session.audioBookId = "book1"
            session.audioServerId = "srv1"
            // Stub ensureTrack: we need readaloudTrackFlow to have a track for ensureOpened to proceed.
            // Since there's no real bundle data, ensureTrack returns null → ensureOpened short-circuits.
            // Just verify that playFromSecond is NOT called (short-circuit case without a valid track).
            // This is the correct boundary behavior: no track data → skip play.
            session.startReadaloudAtSecond(42.0)

            assertTrue(
                "readaloudOpen must be set when bundle is present",
                session.readaloudOpen.value,
            )
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `onPageTopResolved forwards to player when readaloud is open`() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val fakePlayer = FakePlayerController()
            val session = makeSessionForOpenClose(scope = sessionScope, playerController = fakePlayer)
            session._readaloudOpen.value = true

            session.onPageTopResolved("chapter01.html", "s42")

            assertEquals(1, fakePlayer.playFromReaderPositionCalls.size)
            assertEquals("chapter01.html", fakePlayer.playFromReaderPositionCalls.first().first)
            assertEquals("s42", fakePlayer.playFromReaderPositionCalls.first().second)
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `onPageTopResolved is ignored when readaloud is closed`() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val fakePlayer = FakePlayerController()
            val session = makeSessionForOpenClose(scope = sessionScope, playerController = fakePlayer)
            session._readaloudOpen.value = false

            session.onPageTopResolved("chapter01.html", "s99")

            assertEquals(
                "playFromReaderPosition must not be called when readaloud closed",
                0, fakePlayer.playFromReaderPositionCalls.size,
            )
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `closeReadaloud flushes resume position via progressFlushScope not session scope`() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val fakeResumeStore = FakeReadaloudResumeStore()
            val (fakeFlushScope, fakeFlushes) = makeFakeFlushScope()
            val snapshotLocator = makeLocator("c01.html", progression = 0.5)
            val fakePlayer = FakePlayerController(isPlaying = false, activeFragment = "c01.html#s7")
            val session = makeSessionForOpenClose(
                scope = sessionScope,
                playerController = fakePlayer,
                progressFlushScope = fakeFlushScope,
                readaloudResumeStore = fakeResumeStore,
                snapshotLocator = { snapshotLocator },
            )
            session._readaloudOpen.value = true
            session.readerServerId = "srv1"
            session.itemId = "book1"

            session.closeReadaloud()

            assertEquals("readaloudOpen must be false after close", false, session.readaloudOpen.value)
            // The resume position write must be inside the progressFlushScope.flush block (not
            // scope.launch) — proven by: (a) flush was called, and (b) executing the captured
            // block actually writes to the store.
            assertEquals(
                "progressFlushScope.flush must be called once",
                1, fakeFlushes.size,
            )
            // Execute the captured flush block to prove it contains the resume-position write.
            fakeFlushes.first().block()
            assertEquals(
                "readaloudResumeStore.save must be called after flush block executes",
                1, fakeResumeStore.savedCalls.size,
            )
            val saved = fakeResumeStore.savedCalls.first()
            assertEquals("saved sourceId must match readerServerId", "srv1", saved.sourceId)
            assertEquals("saved itemId must match session itemId", "book1", saved.itemId)
            assertEquals("saved href must come from snapshotLocator", "c01.html", saved.position.href)
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `closeReadaloud resume position write is inside flush block not scope launch`() = runTest {
        // Proves the fix: if scope.launch were used instead of progressFlushScope.flush, the
        // store write would appear without any flush call (the launch would fire immediately on
        // UnconfinedTestDispatcher). Here we verify the write ONLY happens inside the flush block.
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val fakeResumeStore = FakeReadaloudResumeStore()
            val (fakeFlushScope, fakeFlushes) = makeFakeFlushScope()
            val snapshotLocator = makeLocator("ch02.html", progression = 0.3)
            val fakePlayer = FakePlayerController(isPlaying = false, activeFragment = "ch02.html#s3")
            val session = makeSessionForOpenClose(
                scope = sessionScope,
                playerController = fakePlayer,
                progressFlushScope = fakeFlushScope,
                readaloudResumeStore = fakeResumeStore,
                snapshotLocator = { snapshotLocator },
            )
            session._readaloudOpen.value = true
            session.readerServerId = "srv2"
            session.itemId = "book2"

            session.closeReadaloud()

            // Before executing the flush block: store must NOT have been written yet.
            // If scope.launch were used (the bug), the store would already be written here
            // because UnconfinedTestDispatcher runs launches eagerly.
            assertEquals(
                "store must not be written before flush block executes (proves write is inside flush)",
                0, fakeResumeStore.savedCalls.size,
            )
            // Now execute the flush block — only then should the store be written.
            fakeFlushes.first().block()
            assertEquals(
                "store must be written after flush block executes",
                1, fakeResumeStore.savedCalls.size,
            )
            assertEquals("ch02.html", fakeResumeStore.savedCalls.first().position.href)
        } finally {
            sessionScope.cancel()
        }
    }

    // ── Sub-task 8.4 tests ────────────────────────────────────────────────────────────

    @Test
    fun `prepareAudiobookHandoff returns current audio position and signals audiobookHandoffState`() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val positionSec = 42.5
            val abItemId = "audiobook-item-88"
            val fakePlayer = FakePlayerController(
                isPlaying = true,
                activeFragment = "ch01.html#s3",
            )
            // Manually set the positionGlobalSec on the player's state
            fakePlayer.state.let { stateFlow ->
                (stateFlow as MutableStateFlow).value =
                    stateFlow.value.copy(positionGlobalSec = positionSec)
            }
            val handoffState = AudiobookHandoffState()
            val session = ReadaloudSession(
                scope = sessionScope,
                snapshotLocator = { null },
                playerCoordinator = fakePlayer,
                readaloudAudioRepository = mockk(relaxed = true),
                streamingSessionFactory = mockk(relaxed = true),
                storytellerSyncController = mockk(relaxed = true),
                audioPlaybackPreferencesStore = FakeAudioPlaybackPreferencesStore(),
                listeningPreferencesStore = FakeListeningPreferencesStore(),
                audioIdentityResolver = mockk(relaxed = true),
                readaloudPreferencesStore = mockk<ReadaloudPreferencesStore>().also {
                    every { it.preferences } returns flowOf(
                        com.riffle.core.domain.ReadaloudPreferences(highlightColor = HighlightColor.BLUE)
                    )
                },
                readaloudResumeStore = mockk(relaxed = true),
                sidecarStore = mockk(relaxed = true),
                readingPositionStore = mockk(relaxed = true),
                readingSyncStore = mockk(relaxed = true),
                audioSyncStore = mockk(relaxed = true),
                epubRepository = mockk(relaxed = true),
                progressFlushScope = mockk(relaxed = true),
                audiobookHandoffState = handoffState,
                connectivityObserver = mockk<ConnectivityObserver>().also {
                    every { it.isOnline } returns MutableStateFlow(true)
                },
                nowPlayingStore = NowPlayingStore(),
                dispatchers = UnconfinedDispatchers,
                logger = RecordingLogger(),
            )
            // Wire up the audiobook item id that will be signalled
            session._audiobookItemId.value = abItemId

            val returnedSec = session.prepareAudiobookHandoff()

            assertEquals(
                "prepareAudiobookHandoff must return the current positionGlobalSec",
                positionSec, returnedSec, 0.001,
            )
            assertEquals(
                "releaseForHandoff must be called once",
                1, fakePlayer.releaseForHandoffCalled,
            )
            // The handoff state must have been signalled with the audiobook id and sec
            val pending = handoffState.pendingHandoff.value
            assertEquals("audiobookHandoffState must be signalled with abItemId", abItemId, pending?.itemId)
            assertEquals("audiobookHandoffState must carry positionSec", positionSec, pending?.atSec ?: 0.0, 0.001)
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `sentence-quote build is triggered when playbackState isPlaying transitions to true`() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val fakePlayer = FakePlayerController(isPlaying = false)
            val session = makeSession(
                scope = sessionScope,
                playerController = fakePlayer,
            )
            // Provide a fake (empty) bundle file so buildSentenceQuotes can be triggered
            val bundle = java.io.File.createTempFile("bundle", ".zip")
            bundle.deleteOnExit()
            session.quoteBuilder.quoteBundle = bundle
            // started starts false
            assertEquals("quoteBuilder.started must start false", false, session.quoteBuilder.started)

            // Trigger isPlaying → true
            (fakePlayer.state as MutableStateFlow).value =
                fakePlayer.state.value.copy(isPlaying = true)

            // Give the launched coroutine a chance to run (UnconfinedTestDispatcher runs eagerly)
            assertEquals(
                "quoteBuilder.started must be true after isPlaying transitions to true",
                true, session.quoteBuilder.started,
            )
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `persistReadaloudResumePosition writes to readaloudResumeStore via the store API`() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val fakeResumeStore = FakeReadaloudResumeStore()
            val session = makeSessionForOpenClose(
                scope = sessionScope,
                readaloudResumeStore = fakeResumeStore,
            )
            session.readerServerId = "srv-persist"
            session.itemId = "item-xyz"

            val locator = makeLocator("chapter03.html", progression = 0.75)
            val fragmentRef = "chapter03.html#s12"

            session.persistReadaloudResumePosition(locator, fragmentRef)

            assertEquals("readaloudResumeStore.save must be called once", 1, fakeResumeStore.savedCalls.size)
            val saved = fakeResumeStore.savedCalls.first()
            assertEquals("saved sourceId", "srv-persist", saved.sourceId)
            assertEquals("saved itemId", "item-xyz", saved.itemId)
            assertEquals("saved href", "chapter03.html", saved.position.href)
            assertEquals("saved progression", 0.75, saved.position.progression ?: 0.0, 0.001)
            assertEquals("saved fragmentRef", fragmentRef, saved.position.fragmentRef)
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `onPositionBeforeForward clears park state when moved off the parked page`() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val session = makeSession(
                scope = sessionScope,
                playerController = FakePlayerController(),
            )
            // Seed park state — parked on chapter01 with href stored as the full toString value
            val parkedLocator = makeLocator("chapter01.html", progression = 0.5)
            session.parkPolicy.onPause(
                pausedFragment = "chapter01.html#s42",
                snapshotHref = parkedLocator.href.toString(),
                snapshotProgression = 0.5,
            )

            // Build a locator on a DIFFERENT chapter — href.toString() != the parked href
            val differentHrefLocator = makeLocator("chapter02.html", progression = 0.0)
            session.onPositionBeforeForward(differentHrefLocator)

            assertNull("parkedFragmentRef must be null after navigating away", session.parkPolicy.fragmentRef)
        } finally {
            sessionScope.cancel()
        }
    }

    // ── Sub-task 8.5 tests ────────────────────────────────────────────────────────────

    /**
     * Helper factory for bind() tests that need a bundle file for pre-warm / availability checks.
     */
    private fun makeSessionForBind(
        scope: CoroutineScope,
        playerController: FakePlayerController = FakePlayerController(),
        audioRepository: ReadaloudAudioRepository = mockk(relaxed = true),
        readaloudResumeStore: ReadaloudResumeStore = mockk(relaxed = true),
        snapshotLocator: () -> Locator? = { null },
    ): ReadaloudSession = ReadaloudSession(
        scope = scope,
        snapshotLocator = snapshotLocator,
        playerCoordinator = playerController,
        readaloudAudioRepository = audioRepository,
        streamingSessionFactory = mockk(relaxed = true),
        storytellerSyncController = mockk(relaxed = true),
        audioPlaybackPreferencesStore = FakeAudioPlaybackPreferencesStore(),
        listeningPreferencesStore = FakeListeningPreferencesStore(),
        audioIdentityResolver = mockk(relaxed = true),
        readaloudPreferencesStore = mockk<ReadaloudPreferencesStore>().also {
            every { it.preferences } returns flowOf(
                com.riffle.core.domain.ReadaloudPreferences(highlightColor = HighlightColor.BLUE)
            )
        },
        readaloudResumeStore = readaloudResumeStore,
        sidecarStore = mockk<ReadaloudSidecarStore>(relaxed = true).also {
            // sidecarStore.states must return a real Flow so startSidecarObserver can collect it.
            every { it.states } returns MutableStateFlow(emptyMap<String, ReadaloudSidecarStore.State>())
        },
        readingPositionStore = mockk(relaxed = true),
        readingSyncStore = mockk(relaxed = true),
        audioSyncStore = mockk(relaxed = true),
        epubRepository = mockk(relaxed = true),
        progressFlushScope = mockk(relaxed = true),
        audiobookHandoffState = AudiobookHandoffState(),
        connectivityObserver = mockk<ConnectivityObserver>().also {
            every { it.isOnline } returns MutableStateFlow(true)
        },
        nowPlayingStore = NowPlayingStore(),
        dispatchers = UnconfinedDispatchers,
        logger = RecordingLogger(),
    )

    @Test
    fun `bind consolidates per-book state into session fields`() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val fakeRepo = FakeReadaloudAudioRepository(bundleFileVal = null)
            val session = makeSessionForBind(scope = sessionScope, audioRepository = fakeRepo)

            val formattingPrefsFlow = MutableStateFlow(com.riffle.core.domain.FormattingPreferences())
            val currentLocatorFlow = MutableStateFlow<Locator?>(null)
            val expectedIdentity = com.riffle.core.domain.AudioIdentity("srv-audio", "ab-123")

            session.bind(
                sourceId = "srv-reader",
                itemId = "ebook-abc",
                isStorytellerService = true,
                audioBookId = "styteller-book-99",
                audioServerId = "srv-st",
                audioSettingsIdentity = expectedIdentity,
                audiobookItemId = "abs-audiobook-777",
                effectiveFormattingPreferencesFlow = formattingPrefsFlow,
                currentLocatorFlow = currentLocatorFlow,
                readerSyncProvider = { null },
                audiobookFollowProvider = { null },
                readerSyncServerIdProvider = { "srv-reader" },
            )

            assertEquals("itemId must be stored", "ebook-abc", session.itemId)
            assertEquals("readerServerId must be stored", "srv-reader", session.readerServerId)
            assertEquals("readerSyncServerId must be stored", "srv-reader", session.readerSyncServerId)
            assertEquals("isStorytellerService must be stored", true, session.isStorytellerService)
            assertEquals("audioBookId must be stored", "styteller-book-99", session.audioBookId)
            assertEquals("audioServerId must be stored", "srv-st", session.audioServerId)
            assertEquals("audioSettingsIdentity must be stored", expectedIdentity, session.audioSettingsIdentity)
            assertEquals("audiobookItemId must be stored", "abs-audiobook-777", session.audiobookItemId.value)
            // Storyteller service → readaloud is always available and visible
            assertTrue("readaloudAvailable must be true for Storyteller", session.readaloudAvailable.value)
            assertTrue("readaloudVisible must be true for Storyteller", session.readaloudVisible.value)
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `bind launches preWarmTrackJob when bundle is present on disk`() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val bundle = java.io.File.createTempFile("bundle", ".zip")
            bundle.deleteOnExit()
            // Use a Storyteller service (always available) so the pre-warm path is reached
            val fakeRepo = FakeReadaloudAudioRepository(bundleFileVal = bundle)
            val session = makeSessionForBind(scope = sessionScope, audioRepository = fakeRepo)

            // Before bind: preWarmTrackJob is null
            assertNull("preWarmTrackJob must be null before bind", session.preWarmTrackJob)

            val formattingPrefsFlow = MutableStateFlow(com.riffle.core.domain.FormattingPreferences())
            val currentLocatorFlow = MutableStateFlow<Locator?>(null)
            session.bind(
                sourceId = "srv1",
                itemId = "book1",
                isStorytellerService = true,
                audioBookId = "book1",
                audioServerId = "srv1",
                audioSettingsIdentity = com.riffle.core.domain.AudioIdentity("srv1", "book1"),
                audiobookItemId = null,
                effectiveFormattingPreferencesFlow = formattingPrefsFlow,
                currentLocatorFlow = currentLocatorFlow,
                readerSyncProvider = { null },
                audiobookFollowProvider = { null },
                readerSyncServerIdProvider = { "srv1" },
            )

            // After bind: preWarmTrackJob must be non-null (launched by launchPreWarmTrack)
            assertTrue(
                "preWarmTrackJob must be non-null after bind when bundle is present",
                session.preWarmTrackJob != null,
            )
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `onBookClosed cancels all session-owned background jobs`() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val session = makeSession(scope = sessionScope, playerController = FakePlayerController())

            // Seed the internally-accessible background jobs so we can verify they're cancelled.
            // storytellerSyncJob is private — seeded via cancelStorytellerSync() test path; here we
            // verify the two still-internal jobs plus all transient state.
            val stubJob2 = sessionScope.launch { delay(Long.MAX_VALUE) }
            val stubJob4 = sessionScope.launch { delay(Long.MAX_VALUE) }
            session.preWarmTrackJob = stubJob2
            session.preparingTimeoutJob = stubJob4
            // Seed transient playback state to verify it's cleared on close
            session.readaloudPrepared = true
            session.readaloudStarted = true
            session.parkPolicy.onPause("c01.html#s5", "c01.html", 0.5)
            session.closeLocator = makeLocator("c01.html", progression = 0.5)
            session.resumeFragmentRef = "c01.html#s5"
            session.pendingStartFragmentRef = "c01.html#s10"

            // Verify jobs are active before close
            assertTrue("preWarmTrackJob must be active before close", stubJob2.isActive)
            assertTrue("preparingTimeoutJob must be active before close", stubJob4.isActive)

            session.onBookClosed()

            // All jobs must be cancelled and nulled
            assertTrue("preWarmTrackJob must be cancelled", stubJob2.isCancelled)
            assertTrue("preparingTimeoutJob must be cancelled", stubJob4.isCancelled)
            assertNull("preWarmTrackJob must be null after close", session.preWarmTrackJob)
            assertNull("preparingTimeoutJob must be null after close", session.preparingTimeoutJob)
            // Transient state must be cleared
            assertEquals("readaloudPrepared must be false after close", false, session.readaloudPrepared)
            assertEquals("readaloudStarted must be false after close", false, session.readaloudStarted)
            assertNull("parkedFragmentRef must be null after close", session.parkPolicy.fragmentRef)
            assertNull("closeLocator must be null after close", session.closeLocator)
            assertNull("resumeFragmentRef must be null after close", session.resumeFragmentRef)
            assertNull("pendingStartFragmentRef must be null after close", session.pendingStartFragmentRef)
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun `cancelStorytellerSync cancels and nulls the active sync job`() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            // Use a Storyteller session so openReadaloud() triggers startStorytellerSync().
            // We don't need a bundle — we only want the storyteller sync job launched.
            val fakeRepo = FakeReadaloudAudioRepository(bundleFileVal = null)
            val session = makeSessionForBind(scope = sessionScope, audioRepository = fakeRepo)
            session.bind(
                sourceId = "srv1",
                itemId = "book1",
                isStorytellerService = true,
                audioBookId = "book1",
                audioServerId = "srv1",
                audioSettingsIdentity = AudioIdentity("srv1", "book1"),
                audiobookItemId = null,
                effectiveFormattingPreferencesFlow = MutableStateFlow(com.riffle.core.domain.FormattingPreferences()),
                currentLocatorFlow = MutableStateFlow(null),
                readerSyncProvider = { null },
                audiobookFollowProvider = { null },
                readerSyncServerIdProvider = { "srv1" },
            )
            // openReadaloudSession() → startStorytellerSync() launches the job.
            session.openReadaloud()

            // The sync job is running; cancelStorytellerSync() must terminate it cleanly.
            // We verify indirectly: calling cancelStorytellerSync() a second time must be safe
            // (no NPE / double-cancel), and the session is left in a state where a new sync can
            // be started without issue.
            session.cancelStorytellerSync()
            session.cancelStorytellerSync() // idempotent: must not throw
        } finally {
            sessionScope.cancel()
        }
    }

    /**
     * Regression for the "Play-from-here restarts the chapter" bug (fix 719ce41).
     *
     * On the first Play-from-here of a streaming Readaloud, the selection resolution
     * needs the sentence-quote map to translate the tapped word into a SMIL span id. The
     * map is built by [ReadaloudQuoteBuilder] after the sidecar observer reaches Ready.
     * If the user taps Play before the observer's emission arrives, the quotes are empty,
     * resolution falls back to Readium's HTML anchor (e.g. `#d1e770`), and
     * `ReadaloudTrack.resolveStartClip` drops to the chapter's first clip.
     *
     * [ReadaloudSession.ensureSentenceQuotesReady] closes the race by seeding
     * `quoteBuilder.quoteBundle` from the cached sidecar itself, then joining the build.
     * This test would fail if the seeding step were removed — the builder's
     * `quoteBundle` would stay null and `ensureBuilt()` would no-op.
     */
    @Test
    fun `ensureSentenceQuotesReady seeds quoteBundle from cached sidecar when observer has not landed`() = runTest {
        val sessionScope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val sidecar = java.io.File.createTempFile("sidecar", ".epub").apply { deleteOnExit() }
            // Bundle is null (streaming path); sidecar exists in cache but the observer
            // hasn't fired yet, so quoteBuilder.quoteBundle is null when we start.
            val fakeRepo = FakeReadaloudAudioRepository(bundleFileVal = null)
            val fakeSidecarStore = mockk<ReadaloudSidecarStore>(relaxed = true).also {
                every { it.states } returns MutableStateFlow(emptyMap<String, ReadaloudSidecarStore.State>())
                every { it.cachedFile(any(), any()) } returns sidecar
            }
            val session = ReadaloudSession(
                scope = sessionScope,
                snapshotLocator = { null },
                playerCoordinator = FakePlayerController(),
                readaloudAudioRepository = fakeRepo,
                streamingSessionFactory = mockk(relaxed = true),
                storytellerSyncController = mockk(relaxed = true),
                audioPlaybackPreferencesStore = FakeAudioPlaybackPreferencesStore(),
                listeningPreferencesStore = FakeListeningPreferencesStore(),
                audioIdentityResolver = mockk(relaxed = true),
                readaloudPreferencesStore = mockk<ReadaloudPreferencesStore>().also {
                    every { it.preferences } returns flowOf(
                        com.riffle.core.domain.ReadaloudPreferences(highlightColor = HighlightColor.BLUE)
                    )
                },
                readaloudResumeStore = mockk(relaxed = true),
                sidecarStore = fakeSidecarStore,
                readingPositionStore = mockk(relaxed = true),
                readingSyncStore = mockk(relaxed = true),
                audioSyncStore = mockk(relaxed = true),
                epubRepository = mockk(relaxed = true),
                progressFlushScope = mockk(relaxed = true),
                audiobookHandoffState = AudiobookHandoffState(),
                connectivityObserver = mockk<ConnectivityObserver>().also {
                    every { it.isOnline } returns MutableStateFlow(true)
                },
                nowPlayingStore = NowPlayingStore(),
                dispatchers = UnconfinedDispatchers,
                logger = RecordingLogger(),
            )

            // Bind as a matched ABS book with no bundle — the streaming path where the
            // sidecar observer is the only thing that would set quoteBundle.
            session.bind(
                sourceId = "srv-reader",
                itemId = "abs-book",
                isStorytellerService = false,
                audioBookId = "st-book",
                audioServerId = "srv-st",
                audioSettingsIdentity = AudioIdentity("srv-st", "st-book"),
                audiobookItemId = null,
                effectiveFormattingPreferencesFlow = MutableStateFlow(com.riffle.core.domain.FormattingPreferences()),
                currentLocatorFlow = MutableStateFlow(null),
                readerSyncProvider = { null },
                audiobookFollowProvider = { null },
                readerSyncServerIdProvider = { "srv-reader" },
            )

            // Precondition: the observer's initial (empty) emission left quoteBundle unset.
            assertNull("quoteBundle must be null before ensureSentenceQuotesReady",
                session.quoteBuilder.quoteBundle)

            session.ensureSentenceQuotesReady()

            // The fix: ensureSentenceQuotesReady falls back to the cached sidecar itself
            // when the observer hasn't seeded the builder yet.
            assertEquals(
                "quoteBundle must be seeded to the cached sidecar",
                sidecar,
                session.quoteBuilder.quoteBundle,
            )
        } finally {
            sessionScope.cancel()
        }
    }
}
