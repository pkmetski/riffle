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
import kotlin.test.assertNull

/**
 * Pins the narrationProgress computation in [IosReadaloudPlayerController] (#1237).
 *
 * Before the fix, narrationProgress was always null (MutableStateFlow(null)); now it is derived
 * from the active clip's progressAt — this test would have failed against the old stub.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IosReadaloudPlayerControllerTest {

    private class FakeBridge : IosReadaloudBridge {
        var positionCallback: IosReadaloudPositionCallback? = null
        var playingCallback: IosReadaloudPlayingCallback? = null

        override fun prepareAudioSrcs(fileUrls: List<String>, startIndex: Int, startOffsetSec: Double) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun seekToSrc(srcIndex: Int, offsetSec: Double) = Unit
        override fun currentSrcIndex(): Int = 0
        override fun currentOffsetSec(): Double = 0.0
        override fun isPlaying(): Boolean = false
        override fun setPositionCallback(callback: IosReadaloudPositionCallback?) {
            positionCallback = callback
        }
        override fun setPlayingCallback(callback: IosReadaloudPlayingCallback?) {
            playingCallback = callback
        }
        override fun dispose() = Unit
    }

    private class FakeBundleAudioExtractor : BundleAudioExtractor {
        override suspend fun extractedTrackUrls(zipFilePath: String, entryPaths: List<String>): List<String> =
            entryPaths.map { "file:///extracted/$it" }
    }

    private val clips = listOf(
        MediaOverlayClip("ch01.html#s1", "audio/ch01.mp3", clipBeginSec = 0.0, clipEndSec = 10.0),
        MediaOverlayClip("ch01.html#s2", "audio/ch01.mp3", clipBeginSec = 10.0, clipEndSec = 20.0),
    )
    private val track = ReadaloudTrack(clips)

    private suspend fun openedController(bridge: FakeBridge): IosReadaloudPlayerController {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val iosController = IosReadaloudController(bridge, FakeBundleAudioExtractor(), IosReadaloudHandoff(), scope)
        val controller = IosReadaloudPlayerController(iosController, scope)
        controller.open("book-1", "/tmp/bundle.zip", track)
        return controller
    }

    @Test
    fun narrationProgressIsNullBeforeOpen() {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val bridge = FakeBridge()
        val iosController = IosReadaloudController(bridge, FakeBundleAudioExtractor(), IosReadaloudHandoff(), scope)
        val controller = IosReadaloudPlayerController(iosController, scope)
        assertNull(controller.narrationProgress.value, "narrationProgress must be null when no track is open")
    }

    @Test
    fun narrationProgressComputesMidClipFraction() = runTest {
        val bridge = FakeBridge()
        val controller = openedController(bridge)
        bridge.positionCallback?.onPosition(srcIndex = 0, offsetSec = 5.0)
        val progress = controller.narrationProgress.value
        assertEquals("ch01.html#s1", progress?.fragmentRef, "fragmentRef must match the active clip")
        assertEquals(0.5, progress?.fraction ?: -1.0, 0.001, "fraction must be 0.5 at 5 s into a 0–10 s clip")
    }

    @Test
    fun narrationProgressComputesStartOfClip() = runTest {
        val bridge = FakeBridge()
        val controller = openedController(bridge)
        bridge.positionCallback?.onPosition(srcIndex = 0, offsetSec = 0.0)
        val progress = controller.narrationProgress.value
        assertEquals("ch01.html#s1", progress?.fragmentRef)
        assertEquals(0.0, progress?.fraction ?: -1.0, 0.001, "fraction must be 0.0 at the start of a clip")
    }

    @Test
    fun narrationProgressAdvancesToNextClipOnBoundary() = runTest {
        val bridge = FakeBridge()
        val controller = openedController(bridge)
        bridge.positionCallback?.onPosition(srcIndex = 0, offsetSec = 10.0)
        val progress = controller.narrationProgress.value
        assertEquals("ch01.html#s2", progress?.fragmentRef, "position at 10 s is the start of the second clip")
        assertEquals(0.0, progress?.fraction ?: -1.0, 0.001)
    }
}
