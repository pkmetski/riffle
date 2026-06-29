package com.riffle.app.feature.reader.session

import com.riffle.app.feature.audiobook.AudiobookHandoffState
import com.riffle.app.feature.reader.ProgressFlushScope
import com.riffle.app.feature.reader.readaloud.PlayerController
import com.riffle.app.feature.reader.readaloud.PlayerCoordinator
import com.riffle.app.feature.reader.readaloud.ReadaloudController
import com.riffle.app.feature.reader.readaloud.ReadaloudStreamingSessionFactory
import com.riffle.app.playback.NowPlayingStore
import com.riffle.core.data.ReadaloudSidecarStore
import com.riffle.core.data.StorytellerPositionSyncController
import com.riffle.core.domain.AudioIdentity
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudHighlightColor
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SyncPositionStore
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        var prevChapterCalled = 0
        var nextChapterCalled = 0
        var speedSet: Float? = null

        private val _state = MutableStateFlow(
            ReadaloudController.PlaybackState(isPlaying = isPlaying)
        )
        override val state: StateFlow<ReadaloudController.PlaybackState> = _state

        private val _activeFragmentRef = MutableStateFlow(activeFragment)
        override val activeFragmentRef: StateFlow<String?> = _activeFragmentRef

        private val _narrationProgress = MutableStateFlow<PlayerCoordinator.NarrationProgress?>(null)
        override val narrationProgress: StateFlow<PlayerCoordinator.NarrationProgress?> = _narrationProgress

        override fun pause() { pauseCalled++; _state.value = _state.value.copy(isPlaying = false) }
        override fun skipBy(deltaSec: Double) { skipByCalls.add(deltaSec) }
        override fun previousChapter() { prevChapterCalled++ }
        override fun nextChapter() { nextChapterCalled++ }
        override fun setSpeed(speed: Float) { speedSet = speed }
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
                    com.riffle.core.domain.ReadaloudPreferences(highlightColor = ReadaloudHighlightColor.BLUE)
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
            assertEquals("parked fragment should match active fragment", "c01.html#s7", session.parkedFragmentRef)
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
}
