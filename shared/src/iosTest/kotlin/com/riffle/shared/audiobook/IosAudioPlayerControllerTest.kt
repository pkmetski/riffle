package com.riffle.shared.audiobook

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.models.AudiobookTrackSpan
import com.riffle.feature.player.SkipIntervals
import com.riffle.feature.player.SleepTimerMode
import com.riffle.feature.player.followSkipIntervals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives the iOS audiobook controller — the only code path iOS executes between the shared
 * [com.riffle.feature.player.AudiobookPlayerViewModel] and the Swift AVQueuePlayer wrapper.
 *
 * Regressions pinned here (issue #1071 §P0):
 *  - the book-absolute position must be projected from the track index the bridge reports, not from
 *    whatever track happens to sit at queue slot 0 after AVQueuePlayer consumed the earlier items;
 *  - a seek must be issued against a *track*, in both directions, carrying the in-track offset;
 *  - the lock-screen metadata must be refreshed as the book plays, not pushed once at prepare().
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IosAudioPlayerControllerTest {

    private class FakeBridge : IosAudioPlayerBridge {
        var preparedUrls: List<String> = emptyList()
        var preparedStartTrackIndex: Int = -1
        var preparedStartOffsetSec: Double = -1.0
        val seeks = mutableListOf<Pair<Int, Double>>()
        val nowPlaying = mutableListOf<NowPlayingPush>()
        val replacements = mutableListOf<Pair<Int, List<String>>>()
        var playCalls = 0
        var pauseCalls = 0
        var disposeCalls = 0
        var reportedTrackIndex: Int = 0
        var reportedOffsetSec: Double = 0.0
        var bufferedAheadSec: Double = 0.0

        var positionCallback: IosPositionCallback? = null
            private set
        var remoteCommandCallback: IosRemoteCommandCallback? = null
            private set

        data class NowPlayingPush(
            val title: String,
            val author: String,
            val durationSec: Double,
            val positionSec: Double,
            val coverUrl: String?,
        )

        override fun preparePlayer(trackUrls: List<String>, startTrackIndex: Int, startOffsetSec: Double) {
            preparedUrls = trackUrls
            preparedStartTrackIndex = startTrackIndex
            preparedStartOffsetSec = startOffsetSec
            reportedTrackIndex = startTrackIndex
            reportedOffsetSec = startOffsetSec
        }

        override fun play() {
            playCalls++
        }
        override fun pause() {
            pauseCalls++
        }

        override fun seekToTrack(trackIndex: Int, offsetSec: Double) {
            seeks += trackIndex to offsetSec
            reportedTrackIndex = trackIndex
            reportedOffsetSec = offsetSec
        }

        override fun setSpeed(speed: Float) = Unit

        val skipIntervals = mutableListOf<SkipIntervals>()
        override fun setSkipIntervals(intervals: SkipIntervals) {
            skipIntervals += intervals
        }

        override fun currentTrackIndex(): Int = reportedTrackIndex
        override fun currentTrackOffsetSec(): Double = reportedOffsetSec
        override fun currentTrackBufferedSec(): Double = bufferedAheadSec
        override fun isPlaying(): Boolean = false

        override fun replaceTracksFrom(fromIndex: Int, trackUrls: List<String>) {
            replacements += fromIndex to trackUrls
        }

        override fun setPositionCallback(callback: IosPositionCallback?) {
            positionCallback = callback
        }
        override fun setPlayingCallback(callback: IosPlayingCallback?) = Unit
        override fun setRemoteCommandCallback(callback: IosRemoteCommandCallback?) {
            remoteCommandCallback = callback
        }
        override fun setEndOfBookCallback(callback: IosEndOfBookCallback?) = Unit

        override fun setNowPlayingInfo(
            title: String,
            author: String,
            durationSec: Double,
            positionSec: Double,
            coverUrl: String?,
        ) {
            nowPlaying += NowPlayingPush(title, author, durationSec, positionSec, coverUrl)
        }

        override fun dispose() {
            disposeCalls++
        }
    }

    // Three 100 s tracks; chapters deliberately do not line up with track boundaries.
    private val spans = listOf(
        AudiobookTrackSpan(index = 0, startOffsetSec = 0.0, durationSec = 100.0),
        AudiobookTrackSpan(index = 1, startOffsetSec = 100.0, durationSec = 100.0),
        AudiobookTrackSpan(index = 2, startOffsetSec = 200.0, durationSec = 100.0),
    )
    private val chapters = listOf(
        AudiobookChapter(index = 0, startSec = 0.0, endSec = 150.0, title = "Chapter One"),
        AudiobookChapter(index = 1, startSec = 150.0, endSec = 300.0, title = "Chapter Two"),
    )
    private val urls = listOf("http://h/t0.mp3", "http://h/t1.mp3", "http://h/t2.mp3")

    private suspend fun prepared(
        bridge: FakeBridge,
        startAtSec: Double = 0.0,
    ): IosAudioPlayerController {
        val controller = IosAudioPlayerController(bridge, UnconfinedTestDispatcher())
        controller.prepare(
            trackUrls = urls,
            spans = spans,
            durationSec = 300.0,
            startAtSec = startAtSec,
            coverUri = "http://h/cover.jpg",
            bookTitle = "Test Book",
            chapters = chapters,
        )
        return controller
    }

    @Test
    fun absolutePositionIsProjectedFromTheReportedTrackIndex() = runTest {
        val bridge = FakeBridge()
        val controller = prepared(bridge)
        // AVQueuePlayer has consumed tracks 0 and 1; the bridge reports the real index.
        bridge.reportedTrackIndex = 2
        bridge.reportedOffsetSec = 5.0
        assertEquals(
            205.0,
            controller.currentAbsoluteSec(),
            "book-absolute position must add track 2's start offset, not track 0's",
        )
    }

    @Test
    fun positionCallbackProjectsOntoTheBookTimeline() = runTest {
        val bridge = FakeBridge()
        val controller = prepared(bridge)
        assertNotNull(bridge.positionCallback).onPosition(trackIndex = 1, offsetSec = 10.0)
        assertEquals(110.0, controller.state.value.positionSec)
        assertEquals(300.0, controller.state.value.durationSec)
    }

    @Test
    fun bufferedPositionIsReportedOnTheBookTimeline() = runTest {
        val bridge = FakeBridge()
        bridge.bufferedAheadSec = 20.0
        val controller = prepared(bridge)
        assertNotNull(bridge.positionCallback).onPosition(trackIndex = 1, offsetSec = 10.0)
        assertEquals(130.0, controller.state.value.bufferedSec)
    }

    @Test
    fun prepareSeedsTheQueueAtTheResumeTrack() = runTest {
        val bridge = FakeBridge()
        prepared(bridge, startAtSec = 250.0)
        assertEquals(2, bridge.preparedStartTrackIndex)
        assertEquals(50.0, bridge.preparedStartOffsetSec)
    }

    @Test
    fun backwardCrossTrackSeekIssuesATrackTargetedSeek() = runTest {
        val bridge = FakeBridge()
        val controller = prepared(bridge, startAtSec = 250.0)
        bridge.seeks.clear()
        controller.seekTo(5.0)
        assertEquals(
            listOf(0 to 5.0),
            bridge.seeks,
            "seeking backwards across a track boundary must re-target track 0, not no-op",
        )
        assertEquals(5.0, controller.state.value.positionSec)
    }

    @Test
    fun forwardCrossTrackSeekCarriesTheInTrackOffset() = runTest {
        val bridge = FakeBridge()
        val controller = prepared(bridge)
        bridge.seeks.clear()
        controller.seekTo(250.0)
        assertEquals(
            listOf(2 to 50.0),
            bridge.seeks,
            "a forward cross-track seek must land 50 s into track 2, not at its start",
        )
    }

    @Test
    fun seekIsClampedToTheBookDuration() = runTest {
        val bridge = FakeBridge()
        val controller = prepared(bridge)
        bridge.seeks.clear()
        controller.seekTo(-30.0)
        controller.seekTo(9_999.0)
        assertEquals(listOf(0 to 0.0, 2 to 100.0), bridge.seeks)
    }

    @Test
    fun skipByUsesTheBridgeReportedTrackIndex() = runTest {
        val bridge = FakeBridge()
        val controller = prepared(bridge)
        bridge.reportedTrackIndex = 1
        bridge.reportedOffsetSec = 20.0
        bridge.seeks.clear()
        controller.skipBy(30.0)
        assertEquals(listOf(1 to 50.0), bridge.seeks, "skip must start from 120 s, not from 20 s")
    }

    @Test
    fun nowPlayingIsPushedAtPrepareWithTheChapterLine() = runTest {
        val bridge = FakeBridge()
        prepared(bridge)
        val first = bridge.nowPlaying.single()
        assertEquals("Test Book", first.title)
        assertTrue(
            first.author.startsWith("Chapter One"),
            "lock-screen subtitle must carry the chapter, was '${first.author}'",
        )
        assertEquals(300.0, first.durationSec)
        assertEquals("http://h/cover.jpg", first.coverUrl)
    }

    @Test
    fun nowPlayingIsRefreshedWhenTheChapterChanges() = runTest {
        val bridge = FakeBridge()
        prepared(bridge)
        val callback = assertNotNull(bridge.positionCallback)
        bridge.nowPlaying.clear()
        // 149 s → still Chapter One; 151 s → Chapter Two.
        callback.onPosition(trackIndex = 1, offsetSec = 49.0)
        callback.onPosition(trackIndex = 1, offsetSec = 51.0)
        assertTrue(
            bridge.nowPlaying.any { it.author.startsWith("Chapter Two") },
            "crossing into chapter 2 must refresh the lock screen, pushes=${bridge.nowPlaying}",
        )
    }

    @Test
    fun nowPlayingIsNotRePushedWithinTheSameMinuteAndChapter() = runTest {
        val bridge = FakeBridge()
        prepared(bridge)
        val callback = assertNotNull(bridge.positionCallback)
        bridge.nowPlaying.clear()
        callback.onPosition(trackIndex = 0, offsetSec = 61.0)
        bridge.nowPlaying.clear()
        callback.onPosition(trackIndex = 0, offsetSec = 62.0)
        assertEquals(0, bridge.nowPlaying.size, "same remaining-minute + chapter must not re-push")
    }

    @Test
    fun remoteScrubResolvesAgainstTheBookTimeline() = runTest {
        val bridge = FakeBridge()
        prepared(bridge)
        bridge.seeks.clear()
        assertNotNull(bridge.remoteCommandCallback).onSeekAbsolute(positionSec = 210.0)
        assertEquals(listOf(2 to 10.0), bridge.seeks)
    }

    @Test
    fun remoteNextTrackJumpsToTheFollowingTrackStart() = runTest {
        val bridge = FakeBridge()
        prepared(bridge)
        bridge.reportedTrackIndex = 0
        bridge.seeks.clear()
        assertNotNull(bridge.remoteCommandCallback).onTrackDelta(delta = 1)
        assertEquals(listOf(1 to 0.0), bridge.seeks)
    }

    @Test
    fun remotePreviousTrackIsClampedAtTheFirstTrack() = runTest {
        val bridge = FakeBridge()
        prepared(bridge)
        bridge.reportedTrackIndex = 0
        bridge.seeks.clear()
        assertNotNull(bridge.remoteCommandCallback).onTrackDelta(delta = -1)
        assertEquals(listOf(0 to 0.0), bridge.seeks)
    }

    @Test
    fun cacheSwapReachesTheBridge() = runTest {
        val bridge = FakeBridge()
        val controller = prepared(bridge)
        val local = listOf("file:///t1.mp3", "file:///t2.mp3")
        controller.swapTracksFromIndex(1, local)
        assertEquals(listOf(1 to local), bridge.replacements, "the cache swap must not be a no-op")
    }

    @Test
    fun pauseCancelsTheSleepTimerAndPlayDoesNot() = runTest {
        val bridge = FakeBridge()
        val controller = prepared(bridge)
        controller.setSleepTimer(SleepTimerMode.CountDown(remainingMs = 600_000L))
        controller.play()
        assertTrue(
            controller.sleepTimer.value is SleepTimerMode.CountDown,
            "resuming playback must not retire the sleep timer (Android parity)",
        )
        controller.pause()
        assertEquals(SleepTimerMode.None, controller.sleepTimer.value)
    }

    @Test
    fun setSpeedSurvivesPrepare() = runTest {
        val bridge = FakeBridge()
        val controller = IosAudioPlayerController(bridge, UnconfinedTestDispatcher())
        controller.setSpeed(1.5f)
        controller.prepare(
            trackUrls = urls,
            spans = spans,
            durationSec = 300.0,
            startAtSec = 0.0,
            bookTitle = "Test Book",
            chapters = chapters,
        )
        assertEquals(1.5f, controller.state.value.speed)
    }

    @Test
    fun stopDisposesTheBridge() = runTest {
        val bridge = FakeBridge()
        val controller = prepared(bridge)
        controller.stop()
        assertEquals(1, bridge.disposeCalls)
    }

    // ── §15: the Listening skip/rewind intervals reach the lock screen ───────────

    @Test
    fun theConfiguredSkipIntervalsAreHandedToTheBridge() = runTest {
        val bridge = FakeBridge()
        val controller = prepared(bridge)
        controller.setSkipIntervals(SkipIntervals(forwardSec = 45, backwardSec = 20))
        assertEquals(
            listOf(SkipIntervals(forwardSec = 45, backwardSec = 20)),
            bridge.skipIntervals,
            "the controller must forward the intervals to the Swift command-centre wrapper",
        )
    }

    /**
     * The whole iOS chain the shared ViewModel drives: stored preference → [followSkipIntervals]
     * → [IosAudioPlayerController] → the bridge that owns `MPRemoteCommandCenter`. Before #1071 the
     * chain did not exist and the lock screen was permanently on 30 s / 15 s.
     */
    @Test
    fun aMidBookPreferenceChangeReachesTheBridge() = runTest {
        val bridge = FakeBridge()
        val controller = prepared(bridge)
        val store = FakeListeningPreferencesStore(skip = 45, rewind = 20)

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.followSkipIntervals(store)
        }
        store.setSkipIntervalSeconds(10)

        assertEquals(
            listOf(
                SkipIntervals(forwardSec = 45, backwardSec = 20),
                SkipIntervals(forwardSec = 10, backwardSec = 20),
            ),
            bridge.skipIntervals,
        )
        job.cancel()
    }

    private class FakeListeningPreferencesStore(skip: Int, rewind: Int) : ListeningPreferencesStore {
        override val defaultPlaybackSpeed = MutableStateFlow(ListeningPreferencesStore.DEFAULT_PLAYBACK_SPEED)
        override val skipIntervalSeconds = MutableStateFlow(skip)
        override val rewindIntervalSeconds = MutableStateFlow(rewind)
        override val rewindOnResumeSeconds =
            MutableStateFlow(ListeningPreferencesStore.DEFAULT_REWIND_ON_RESUME_SECONDS)

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
}
