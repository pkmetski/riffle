package com.riffle.feature.player

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.models.AudiobookTrackSpan
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The "Skip Forward" / "Rewind" / "Rewind on Resume" Listening preferences, from the store to the
 * platform transport controls.
 *
 * This is `commonTest`, so it runs on the JVM *and* on `iosSimulatorArm64` — which matters, because
 * the defect this pins (issue #1071 §15) was iOS-only: the lock-screen skip commands were built
 * with a hardcoded `[30]` / `[15]` and the in-app player had no skip buttons at all, while the same
 * shared ViewModel already read the preference for Android.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SkipIntervalsTest {

    private class FakeListeningPreferencesStore(
        skip: Int = ListeningPreferencesStore.DEFAULT_SKIP_INTERVAL_SECONDS,
        rewind: Int = ListeningPreferencesStore.DEFAULT_REWIND_INTERVAL_SECONDS,
        rewindOnResume: Int = ListeningPreferencesStore.DEFAULT_REWIND_ON_RESUME_SECONDS,
    ) : ListeningPreferencesStore {
        override val defaultPlaybackSpeed = MutableStateFlow(ListeningPreferencesStore.DEFAULT_PLAYBACK_SPEED)
        override val skipIntervalSeconds = MutableStateFlow(skip)
        override val rewindIntervalSeconds = MutableStateFlow(rewind)
        override val rewindOnResumeSeconds = MutableStateFlow(rewindOnResume)

        override suspend fun setDefaultPlaybackSpeed(speed: Float) {
            defaultPlaybackSpeed.value = speed
        }

        override suspend fun setSkipIntervalSeconds(seconds: Int) {
            skipIntervalSeconds.value = seconds
        }

        override suspend fun setRewindIntervalSeconds(seconds: Int) {
            rewindIntervalSeconds.value = seconds
        }

        override suspend fun setRewindOnResumeSeconds(seconds: Int) {
            rewindOnResumeSeconds.value = seconds
        }
    }

    /** Records only what this test cares about; every other member is inert. */
    private class RecordingPlayer : AudioPlayerInterface {
        val pushedIntervals = mutableListOf<SkipIntervals>()

        private val _state = MutableStateFlow(AudioPlayerInterface.PlaybackState())
        override val state: StateFlow<AudioPlayerInterface.PlaybackState> = _state.asStateFlow()

        private val _sleepTimer = MutableStateFlow<SleepTimerMode>(SleepTimerMode.None)
        override val sleepTimer: StateFlow<SleepTimerMode> = _sleepTimer.asStateFlow()

        private val _ended = MutableSharedFlow<Unit>(replay = 1)
        override val playbackEnded: SharedFlow<Unit> = _ended.asSharedFlow()

        override suspend fun prepare(
            trackUrls: List<String>,
            spans: List<AudiobookTrackSpan>,
            durationSec: Double,
            startAtSec: Double,
            localZipFilePath: String?,
            coverUri: String?,
            bookTitle: String?,
            chapters: List<AudiobookChapter>,
        ) = Unit

        override suspend fun warmBinder() = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun setSpeed(speed: Float) = Unit

        override fun setSkipIntervals(intervals: SkipIntervals) {
            pushedIntervals += intervals
        }

        override fun setSleepTimer(mode: SleepTimerMode) = Unit
        override fun cancelSleepTimer() = Unit
        override fun triggerSleepNow() = Unit
        override fun seekTo(absoluteSec: Double) = Unit
        override fun skipBy(deltaSec: Double) = Unit
        override fun currentAbsoluteSec(): Double = 0.0
        override fun swapTracksFromIndex(fromIndex: Int, newUrls: List<String>) = Unit
        override fun clearEndOfBookCache() = Unit
        override fun stop() = Unit
        override fun releaseForHandoff() = Unit
    }

    // ── the transport follows the stored preference ──────────────────────────────

    @Test
    fun theTransportIsGivenTheStoredIntervalsNotTheDefaults() = runTest {
        val store = FakeListeningPreferencesStore(skip = 45, rewind = 20)
        val player = RecordingPlayer()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            player.followSkipIntervals(store)
        }

        assertEquals(
            listOf(SkipIntervals(forwardSec = 45, backwardSec = 20)),
            player.pushedIntervals,
            "the transport must be configured from the stored preference, not 30s/15s",
        )
        job.cancel()
    }

    @Test
    fun changingTheIntervalMidBookReachesTheTransport() = runTest {
        val store = FakeListeningPreferencesStore()
        val player = RecordingPlayer()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            player.followSkipIntervals(store)
        }
        store.setSkipIntervalSeconds(60)
        store.setRewindIntervalSeconds(10)

        assertEquals(
            listOf(
                SkipIntervals.DEFAULT,
                SkipIntervals(forwardSec = 60, backwardSec = SkipIntervals.DEFAULT.backwardSec),
                SkipIntervals(forwardSec = 60, backwardSec = 10),
            ),
            player.pushedIntervals,
            "the lock screen / media notification is configured once per open, so every later " +
                "preference change has to be pushed too",
        )
        job.cancel()
    }

    @Test
    fun rewritingAnUnchangedPreferenceDoesNotChurnTheTransport() = runTest {
        val store = FakeListeningPreferencesStore(skip = 30, rewind = 15)
        val player = RecordingPlayer()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            player.followSkipIntervals(store)
        }
        store.setSkipIntervalSeconds(30)
        store.setRewindIntervalSeconds(15)

        assertEquals(1, player.pushedIntervals.size, "identical values must not be re-pushed")
        job.cancel()
    }

    @Test
    fun outOfRangeStoredValuesAreClampedToTheStepperBounds() = runTest {
        val store = FakeListeningPreferencesStore(skip = 9_000, rewind = 0)

        assertEquals(
            SkipIntervals(forwardSec = SkipIntervals.MAX_SECONDS, backwardSec = SkipIntervals.MIN_SECONDS),
            store.transportSkipIntervals().first(),
        )
    }

    @Test
    fun theDefaultsAreTheStoresOwnDefaults() {
        assertEquals(ListeningPreferencesStore.DEFAULT_SKIP_INTERVAL_SECONDS, SkipIntervals.DEFAULT.forwardSec)
        assertEquals(ListeningPreferencesStore.DEFAULT_REWIND_INTERVAL_SECONDS, SkipIntervals.DEFAULT.backwardSec)
    }

    // ── where a skip lands ───────────────────────────────────────────────────────

    @Test
    fun aSkipJumpsTheConfiguredDistance() {
        val intervals = SkipIntervals(forwardSec = 45, backwardSec = 20)
        assertEquals(145.0, intervals.forwardTargetSec(currentSec = 100.0, durationSec = 600.0))
        assertEquals(80.0, intervals.backwardTargetSec(currentSec = 100.0))
    }

    @Test
    fun aSkipCannotRunOffEitherEndOfTheBook() {
        val intervals = SkipIntervals(forwardSec = 45, backwardSec = 20)
        assertEquals(600.0, intervals.forwardTargetSec(currentSec = 590.0, durationSec = 600.0))
        assertEquals(0.0, intervals.backwardTargetSec(currentSec = 5.0))
    }

    @Test
    fun anUnknownDurationDoesNotClampTheForwardSkipToZero() {
        // Media3 reports C.TIME_UNSET before the item is prepared; callers pass 0.0 for "unknown",
        // and clamping to it would pin every forward skip to the start of the book.
        val intervals = SkipIntervals(forwardSec = 30, backwardSec = 15)
        assertEquals(130.0, intervals.forwardTargetSec(currentSec = 100.0, durationSec = 0.0))
    }

    // ── rewind on resume ─────────────────────────────────────────────────────────

    @Test
    fun resumeRewindsByTheConfiguredAmount() {
        assertEquals(88.0, resumePositionSec(currentSec = 100.0, rewindOnResumeSec = 12.0))
    }

    @Test
    fun resumeIsUnchangedWhenRewindOnResumeIsOff() {
        assertEquals(100.0, resumePositionSec(currentSec = 100.0, rewindOnResumeSec = 0.0))
    }

    @Test
    fun resumeNeverRewindsPastTheStartOfTheBook() {
        assertEquals(0.0, resumePositionSec(currentSec = 4.0, rewindOnResumeSec = 30.0))
    }

    // ── notification / accessibility presentation ────────────────────────────────

    @Test
    fun theSkipLabelsNameTheConfiguredInterval() {
        assertEquals("Forward 45 seconds", skipForwardLabel(45))
        assertEquals("Rewind 20 seconds", skipBackwardLabel(20))
    }

    @Test
    fun theNumberedSkipGlyphsBucketTheSameWayMedia3Does() {
        assertEquals(SkipIconBucket.SEC_5, SkipIconBucket.of(5))
        assertEquals(SkipIconBucket.SEC_10, SkipIconBucket.of(10))
        assertEquals(SkipIconBucket.SEC_15, SkipIconBucket.of(15))
        assertEquals(SkipIconBucket.SEC_30, SkipIconBucket.of(30))
        // Media3's own thresholds round 20–39 s onto the 30 s glyph and 13–19 s onto the 15 s one.
        assertEquals(SkipIconBucket.SEC_30, SkipIconBucket.of(20))
        assertEquals(SkipIconBucket.SEC_15, SkipIconBucket.of(19))
        assertEquals(SkipIconBucket.GENERIC, SkipIconBucket.of(60))
    }
}
