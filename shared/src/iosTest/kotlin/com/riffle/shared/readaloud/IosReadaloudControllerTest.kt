package com.riffle.shared.readaloud

import com.riffle.core.domain.MediaOverlayClip
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.shared.audiobook.BundleAudioExtractor
import com.riffle.shared.library.IosReadaloudHandoff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Drives [IosReadaloudController] — the layer between the shared readaloud session and the Swift
 * AVQueuePlayer wrapper.
 *
 * Regressions pinned here (#1187):
 *  - audio srcs must be mapped to queue indices before bridging;
 *  - position callbacks must update [PlaybackState] with the correct audioSrc;
 *  - skipBy and chapter navigation use ReadaloudTrack helpers, not bridge indices.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IosReadaloudControllerTest {

    private class FakeBridge : IosReadaloudBridge {
        val preparedUrls = mutableListOf<String>()
        var preparedStartIndex = -1
        var preparedStartOffset = -1.0
        val seeks = mutableListOf<Pair<Int, Double>>()
        var playCalls = 0
        var pauseCalls = 0
        var disposeCalls = 0
        var reportedSrcIndex = 0
        var reportedOffset = 0.0

        var positionCallback: IosReadaloudPositionCallback? = null
        var playingCallback: IosReadaloudPlayingCallback? = null

        override fun prepareAudioSrcs(fileUrls: List<String>, startIndex: Int, startOffsetSec: Double) {
            preparedUrls.clear()
            preparedUrls.addAll(fileUrls)
            preparedStartIndex = startIndex
            preparedStartOffset = startOffsetSec
            reportedSrcIndex = startIndex
            reportedOffset = startOffsetSec
        }

        override fun play() {
            playCalls++
        }
        override fun pause() {
            pauseCalls++
        }
        override fun setSpeed(speed: Float) = Unit
        override fun seekToSrc(srcIndex: Int, offsetSec: Double) {
            seeks += srcIndex to offsetSec
            reportedSrcIndex = srcIndex
            reportedOffset = offsetSec
        }
        override fun currentSrcIndex(): Int = reportedSrcIndex
        override fun currentOffsetSec(): Double = reportedOffset
        override fun isPlaying(): Boolean = false
        override fun setPositionCallback(callback: IosReadaloudPositionCallback?) {
            positionCallback = callback
        }
        override fun setPlayingCallback(callback: IosReadaloudPlayingCallback?) {
            playingCallback = callback
        }
        override fun dispose() {
            disposeCalls++
        }
    }

    private class FakeBundleAudioExtractor : BundleAudioExtractor {
        override suspend fun extractedTrackUrls(zipFilePath: String, entryPaths: List<String>): List<String> =
            entryPaths.map { "file:///extracted/$it" }
    }

    // Two distinct audio files, three clips total, two chapters.
    private val clips = listOf(
        MediaOverlayClip("ch01.html#s1", "audio/ch01.mp3", 0.0, 10.0),
        MediaOverlayClip("ch01.html#s2", "audio/ch01.mp3", 10.0, 20.0),
        MediaOverlayClip("ch02.html#s1", "audio/ch02.mp3", 0.0, 15.0),
    )
    private val track = ReadaloudTrack(clips)

    private suspend fun prepared(
        bridge: FakeBridge,
        extractor: BundleAudioExtractor = FakeBundleAudioExtractor(),
        handoff: IosReadaloudHandoff = IosReadaloudHandoff(),
        resumeAudioSrc: String? = null,
        resumeOffsetSec: Double = 0.0,
    ): Pair<IosReadaloudController, FakeBridge> {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val controller = IosReadaloudController(bridge, extractor, handoff, scope)
        controller.prepare("/tmp/bundle.zip", track, resumeAudioSrc, resumeOffsetSec)
        return controller to bridge
    }

    @Test
    fun prepareExtractsAndQueuesFileUrls() = runTest {
        val bridge = FakeBridge()
        prepared(bridge)
        assertEquals(
            listOf("file:///extracted/audio/ch01.mp3", "file:///extracted/audio/ch02.mp3"),
            bridge.preparedUrls,
            "prepare must extract distinct audio files and pass file:// URLs to the bridge",
        )
    }

    @Test
    fun prepareWithResumeAudioSrcSeeksToCorrectIndex() = runTest {
        val bridge = FakeBridge()
        prepared(bridge, resumeAudioSrc = "audio/ch02.mp3", resumeOffsetSec = 5.0)
        assertEquals(1, bridge.preparedStartIndex, "ch02.mp3 is index 1 in the queued playlist")
        assertEquals(5.0, bridge.preparedStartOffset)
    }

    @Test
    fun positionCallbackUpdatesPlaybackStateWithCorrectAudioSrc() = runTest {
        val bridge = FakeBridge()
        val (controller) = prepared(bridge)
        bridge.positionCallback?.onPosition(srcIndex = 1, offsetSec = 3.0)
        assertEquals("audio/ch02.mp3", controller.state.value.currentAudioSrc)
        assertEquals(3.0, controller.state.value.positionSec)
    }

    @Test
    fun positionCallbackUpdatesGlobalPosition() = runTest {
        val bridge = FakeBridge()
        val (controller) = prepared(bridge)
        // ch01.mp3 duration = 20 s; ch02.mp3 starts at global 20 s.
        bridge.positionCallback?.onPosition(srcIndex = 1, offsetSec = 5.0)
        assertEquals(25.0, controller.state.value.positionGlobalSec)
    }

    @Test
    fun rewindCallsSkipByNegative15() = runTest {
        val bridge = FakeBridge()
        val (controller) = prepared(bridge)
        // Drive position to ch01.mp3 offset 18 s via callback so controller state is correct.
        bridge.positionCallback?.onPosition(srcIndex = 0, offsetSec = 18.0)
        bridge.seeks.clear()
        controller.rewind()
        // global 18 - 15 = 3; still ch01.mp3 at offset 3.
        assertEquals(listOf(0 to 3.0), bridge.seeks)
    }

    @Test
    fun forwardCallsSkipByPositive30() = runTest {
        val bridge = FakeBridge()
        val (controller) = prepared(bridge)
        // Position at ch01.mp3 offset 5 s (global 5 s); forward 30 should land in ch02.mp3 at 10 s.
        // ch01.mp3 duration = 20 s. global 5 + 30 = 35; ch02 starts at 20; offset = 15.
        // But total duration = 35, so positionAt(35) clamps to file end → ch02 at 15 s.
        bridge.positionCallback?.onPosition(srcIndex = 0, offsetSec = 5.0)
        bridge.seeks.clear()
        controller.forward()
        // global 5 + 30 = 35; coerced to [0, 35] = 35; ch02 starts at 20; offset = 15.
        assertEquals(listOf(1 to 15.0), bridge.seeks)
    }

    @Test
    fun playFromFragmentSeeksToClipStart() = runTest {
        val bridge = FakeBridge()
        val (controller) = prepared(bridge)
        bridge.seeks.clear()
        controller.playFromFragment("ch02.html#s1")
        // ch02.html#s1 -> audioSrc=audio/ch02.mp3 (index 1), clipBeginSec=0.0
        assertEquals(listOf(1 to 0.0), bridge.seeks)
    }

    @Test
    fun playFromFragmentWithUnknownRefIsNoOp() = runTest {
        val bridge = FakeBridge()
        val (controller) = prepared(bridge)
        bridge.seeks.clear()
        controller.playFromFragment("nonexistent.html#x")
        assertEquals(emptyList(), bridge.seeks)
    }

    @Test
    fun stopDisposesBridge() = runTest {
        val bridge = FakeBridge()
        val (controller) = prepared(bridge)
        controller.stop()
        assertEquals(1, bridge.disposeCalls)
    }

    @Test
    fun stopClearsTrackAndResetsState() = runTest {
        val bridge = FakeBridge()
        val (controller) = prepared(bridge)
        controller.stop()
        assertNull(controller.state.value.currentAudioSrc)
        assertFalse(controller.state.value.isPlaying)
    }

    @Test
    fun nextChapterJumpsToChapterTwoStart() = runTest {
        val bridge = FakeBridge()
        val (controller) = prepared(bridge)
        // Start at ch01.mp3 index 0, offset 5 s → chapter 0.
        bridge.reportedSrcIndex = 0
        bridge.reportedOffset = 5.0
        bridge.seeks.clear()
        controller.nextChapter()
        // Chapter 1 starts at ch02.html#s1 → audioSrc=ch02.mp3 (index 1), clipBeginSec=0.0
        assertEquals(listOf(1 to 0.0), bridge.seeks)
    }

    @Test
    fun previousChapterRestartsCurrentChapterWhenNotNearStart() = runTest {
        val bridge = FakeBridge()
        val (controller) = prepared(bridge)
        // Halfway through ch01 at offset 10 s — not near start (>3 s in).
        bridge.reportedSrcIndex = 0
        bridge.reportedOffset = 10.0
        bridge.seeks.clear()
        controller.previousChapter()
        // Should restart chapter 0 at ch01.html#s1 → index 0, offset 0 s.
        assertEquals(listOf(0 to 0.0), bridge.seeks)
    }

    @Test
    fun prepareRegistersTrackOnHandoff() = runTest {
        val bridge = FakeBridge()
        val handoff = IosReadaloudHandoff()
        prepared(bridge = bridge, handoff = handoff)
        // After prepare, a pre-warm seek for global 0 s resolves to the first audio file.
        handoff.preWarmSeek(0.0)
        val pos = handoff.consumePreWarmedPosition()
        assertEquals("audio/ch01.mp3", pos?.audioSrc, "handoff must have the track set after prepare")
    }
}
