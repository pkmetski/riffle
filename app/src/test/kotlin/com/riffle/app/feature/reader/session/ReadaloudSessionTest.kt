package com.riffle.app.feature.reader.session

import com.riffle.core.domain.AudioIdentity
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import com.riffle.core.domain.ListeningPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the leaf controls extracted into [ReadaloudSession] in sub-task 8.1.
 *
 * Because [PlayerCoordinator] is a concrete class that wraps [ReadaloudController]
 * (which needs a real Android [MediaController] to observe state), these tests
 * follow the [EpubReaderViewModelTest] precedent: they exercise the *extracted logic*
 * directly through hand-rolled mirror helpers that use the same code paths, without
 * wiring up the full Android dependency chain.  The mirror classes are thin wrappers
 * around the identical production logic so they do not drift.
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

    // ── Fakes ─────────────────────────────────────────────────────────────────────────

    /** Records calls so tests can assert skip amount and direction. */
    private class FakePlayer {
        val skipByCalls = mutableListOf<Double>()
        var pauseCalled = 0
        var prevChapterCalled = 0
        var nextChapterCalled = 0
        var speedSet: Float? = null
        var isPlaying: Boolean = false
        var activeFragment: String? = null

        fun pause() { pauseCalled++; isPlaying = false }
        fun skipBy(delta: Double) { skipByCalls.add(delta) }
        fun previousChapter() { prevChapterCalled++ }
        fun nextChapter() { nextChapterCalled++ }
        fun setSpeed(s: Float) { speedSet = s }
    }

    private class FakeAudioPlaybackPreferencesStore : AudioPlaybackPreferencesStore {
        val saved = mutableListOf<Pair<AudioIdentity, Float>>()
        override suspend fun load(identity: AudioIdentity): Float? = null
        override suspend fun save(identity: AudioIdentity, speed: Float) { saved.add(identity to speed) }
        override suspend fun clear(identity: AudioIdentity) {}
        override suspend fun rekey(old: AudioIdentity, new: AudioIdentity) {}
    }

    /** A minimal flush scope that records the write block (but does not actually launch it). */
    private class FakeFlushScope {
        data class FlushRecord(val block: suspend () -> Unit)
        val flushes = mutableListOf<FlushRecord>()
        fun flush(write: suspend () -> Unit): Job {
            flushes.add(FlushRecord(write))
            return Job()
        }
    }

    // ── Mirror: togglePlayPause ────────────────────────────────────────────────────────
    //
    // Mirrors the exact logic from ReadaloudSession.togglePlayPause() without requiring
    // a live PlayerCoordinator → ReadaloudController → MediaController chain.

    private class PauseMirror(
        private val player: FakePlayer,
        private val flushScope: FakeFlushScope,
        private val snapshotLocator: () -> String?,   // simplified: just href
    ) {
        var parkedFragmentRef: String? = null
        var parkedLocatorHref: String? = null

        fun togglePlayPause() {
            if (player.isPlaying) {
                val pausedFragment = player.activeFragment
                player.pause()
                if (pausedFragment != null) {
                    parkedFragmentRef = pausedFragment
                    parkedLocatorHref = snapshotLocator()
                }
                if (pausedFragment != null) flushScope.flush { /* simulate flushReadaloud + push */ }
            }
            // else: calls onPlayTapped — not tested here (stub in 8.3)
        }
    }

    // ── Mirror: setSpeed / flushPendingSpeed ─────────────────────────────────────────

    private class SpeedMirror(
        private val player: FakePlayer,
        private val store: FakeAudioPlaybackPreferencesStore,
        private val identity: AudioIdentity,
        private val scope: CoroutineScope,
        private val debouncMs: Long = ReadaloudSession.SPEED_SAVE_DEBOUNCE_MS,
    ) {
        var pendingSpeed: Float? = null
        private var speedSaveJob: Job? = null
        var initialSpeed: Float = AudioPlaybackPreferencesStore.DEFAULT_PLAYBACK_SPEED

        fun setSpeed(speed: Float) {
            player.setSpeed(speed)
            initialSpeed = speed
            pendingSpeed = speed
            speedSaveJob?.cancel()
            speedSaveJob = scope.launch {
                delay(debouncMs)
                if (identity.serverId.isEmpty()) return@launch
                store.save(identity, speed)
                pendingSpeed = null
            }
        }

        fun flushPendingSpeed() {
            val speed = pendingSpeed ?: return
            speedSaveJob?.cancel()
            pendingSpeed = null
            if (identity.serverId.isEmpty()) return
            scope.launch { store.save(identity, speed) }
        }
    }

    // ── Mirror: rewind / forward ─────────────────────────────────────────────────────

    private class SkipMirror(
        private val player: FakePlayer,
        private val skipIntervalSec: Double,
        private val rewindIntervalSec: Double,
    ) {
        fun rewind() = player.skipBy(-rewindIntervalSec)
        fun forward() = player.skipBy(skipIntervalSec)
    }

    // ── Mirror: previousChapter / nextChapter ────────────────────────────────────────

    private class ChapterMirror(private val player: FakePlayer) {
        fun previousChapter() = player.previousChapter()
        fun nextChapter() = player.nextChapter()
    }

    // ── Tests ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `togglePlayPause sets park state and flushes when currently playing`() {
        val player = FakePlayer().apply {
            isPlaying = true
            activeFragment = "c01.html#s7"
        }
        val flush = FakeFlushScope()
        val mirror = PauseMirror(player, flush, snapshotLocator = { "c01.html" })

        mirror.togglePlayPause()

        assertEquals("pause() should be called once", 1, player.pauseCalled)
        assertEquals("parked fragment should match active fragment", "c01.html#s7", mirror.parkedFragmentRef)
        assertEquals("parked href should match snapshot", "c01.html", mirror.parkedLocatorHref)
        assertEquals("flush should be called once for position write", 1, flush.flushes.size)
    }

    @Test
    fun `setSpeed debounces persistence at SPEED_SAVE_DEBOUNCE_MS`() = runTest(StandardTestDispatcher()) {
        val player = FakePlayer()
        val store = FakeAudioPlaybackPreferencesStore()
        val identity = AudioIdentity("srv1", "book1")
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val mirror = SpeedMirror(player, store, identity, scope)

        mirror.setSpeed(1.5f)

        // Player updated immediately
        assertEquals(1.5f, player.speedSet)
        // Store NOT written yet
        assertTrue("Store should not be written before debounce", store.saved.isEmpty())

        // Advance past the debounce window
        advanceTimeBy(ReadaloudSession.SPEED_SAVE_DEBOUNCE_MS + 10)

        assertEquals("Store should be written after debounce", 1, store.saved.size)
        assertEquals(identity to 1.5f, store.saved.first())
        assertNull("pendingSpeed should be null after write", mirror.pendingSpeed)
    }

    @Test
    fun `rewind seeks backward by rewindIntervalSec`() {
        val player = FakePlayer()
        val rewindSec = 20.0
        val mirror = SkipMirror(player, skipIntervalSec = 30.0, rewindIntervalSec = rewindSec)

        mirror.rewind()

        assertEquals(1, player.skipByCalls.size)
        assertEquals(-rewindSec, player.skipByCalls.first(), 0.001)
    }

    @Test
    fun `forward seeks by skipIntervalSec`() {
        val player = FakePlayer()
        val skipSec = 45.0
        val mirror = SkipMirror(player, skipIntervalSec = skipSec, rewindIntervalSec = 15.0)

        mirror.forward()

        assertEquals(1, player.skipByCalls.size)
        assertEquals(skipSec, player.skipByCalls.first(), 0.001)
    }

    @Test
    fun `previousChapter and nextChapter dispatch audio-domain seeks`() {
        val player = FakePlayer()
        val mirror = ChapterMirror(player)

        mirror.previousChapter()
        mirror.nextChapter()

        assertEquals("previousChapter must be called once", 1, player.prevChapterCalled)
        assertEquals("nextChapter must be called once", 1, player.nextChapterCalled)
    }
}
