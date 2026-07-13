package com.riffle.app.feature.reader

import com.riffle.app.testing.TestApplicationScope
import com.riffle.core.catalog.AudiobookProgressPeerCapability
import com.riffle.core.catalog.CatalogProgress
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.domain.BookSyncState
import com.riffle.core.domain.ChapterCharMap
import com.riffle.core.domain.ChapterProgression
import com.riffle.core.domain.Clock
import com.riffle.core.domain.CrossEpubIndex
import com.riffle.core.domain.DefaultPositionTranslator
import com.riffle.core.domain.MediaOverlayClip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSyncCoordinatorTest {

    private val absHtml = "<html><body><p>${"x".repeat(100)}</p></body></html>"
    private val translator = DefaultPositionTranslator(
        smilClips = listOf(
            MediaOverlayClip("f1", "a.mp3", clipBeginSec = 5.0, clipEndSec = 10.0),
            MediaOverlayClip("f9", "a.mp3", clipBeginSec = 90.0, clipEndSec = 100.0),
        ),
        crossEpubIndex = CrossEpubIndex(listOf(ChapterCharMap(absChars = 100, storytellerChars = 100))),
        fragmentProgressions = mapOf("f1" to ChapterProgression(0, 0.2), "f9" to ChapterProgression(0, 0.9)),
        absSpineHrefs = listOf("c1.xhtml"),
        absChapterHtml = { if (it == 0) absHtml else null },
        storytellerSpineHrefs = listOf("c1.xhtml"),
        storytellerChapterHtml = { if (it == 0) absHtml else null },
    )

    private val state = BookSyncState(
        isMatched = true, hasEbookPeer = true, hasAudioPeer = true, prerequisitesCached = true,
    )

    private fun locator(progression: Double) = JSONObject()
        .put("href", "c1.xhtml")
        .put("locations", JSONObject().put("progression", progression))
        .toString()

    /**
     * An in-memory Catalog peer that mimics ABS's PATCH semantics: writes bump a monotonic server
     * `lastUpdate` and pushEbook/pushAudio return that fresh stamp — the same shape the ABS session
     * API returns in production after PR #434.
     */
    private class FakePeer(initial: CatalogProgress) : ProgressPeerCapability, AudiobookProgressPeerCapability {
        var progress = initial
        private var clock = initial.lastUpdate
        var ebookLocation: String? = null
        var ebookProgress: Float? = null
        var audioCurrentTime: Double? = null
        var audioDuration: Double? = null

        private fun nextStamp(): Long { clock += 1_000; return clock }

        override suspend fun pushEbookProgress(
            itemId: String, location: String, progress: Float, isFinished: Boolean?, lastUpdateEpochMs: Long,
        ): Long {
            ebookLocation = location
            ebookProgress = progress
            val stamp = nextStamp()
            this.progress = this.progress.copy(ebookLocation = location, ebookProgress = progress, lastUpdate = stamp)
            return stamp
        }

        override suspend fun pushAudiobookProgress(
            itemId: String, currentTimeSec: Double, durationSec: Double, isFinished: Boolean?, lastUpdateEpochMs: Long,
        ): Long {
            audioCurrentTime = currentTimeSec
            audioDuration = durationSec
            val stamp = nextStamp()
            this.progress = this.progress.copy(audioCurrentTime = currentTimeSec, audioDuration = durationSec, lastUpdate = stamp)
            return stamp
        }

        override suspend fun pullProgress(itemId: String): CatalogProgress? = progress
        override suspend fun pullAllProgress(): List<CatalogProgress> = emptyList()
    }

    private val clock = object : Clock {
        override fun nowMs() = 0L
        override fun nowNs() = 0L
    }

    private fun coordinator(
        peer: FakePeer,
        ebookEp: CatalogEbookEndpoint = CatalogEbookEndpoint(peer, "abs-item"),
        audioEp: CatalogAudioEndpoint? = CatalogAudioEndpoint(peer = peer, itemId = "abs-item", durationSec = 100.0),
    ) = ReaderSyncCoordinator(state, translator, clock, ebookEp, audioEp)

    @Test
    fun `a newer audiobook position wins the cycle and the reader jumps to it`() = runTest {
        val peer = FakePeer(CatalogProgress("abs-item", ebookLocation = "", audioCurrentTime = 95.0, audioDuration = 100.0, lastUpdate = 5_000L))
        val result = coordinator(peer).runCycle(locator(0.2), localUpdatedAt = 1_000L)

        assertNotNull(result.jumpLocatorJson)
        assertEquals(0.9, JSONObject(result.jumpLocatorJson!!).getJSONObject("locations").getDouble("progression"), 1e-9)
        assertTrue(result.canonicalLastUpdate >= 5_000L)
    }

    @Test
    fun `when local is newest there is no jump and every peer gets the reading position`() = runTest {
        val peer = FakePeer(CatalogProgress("abs-item", ebookLocation = "", lastUpdate = 0L))
        val result = coordinator(peer).runCycle(locator(0.3), localUpdatedAt = 9_000L)

        assertNull(result.jumpLocatorJson)
        assertNotNull("ebook peer received a write", peer.ebookLocation)
        assertTrue(peer.ebookLocation!!.startsWith("epubcfi("))
        assertNotNull("the cycle writes the audiobook from the page", peer.audioCurrentTime)
        assertEquals(5.0, peer.audioCurrentTime!!, 1e-9)
    }

    @Test
    fun `a genuinely newer audiobook listened elsewhere wins and jumps the reader to the bundle page`() = runTest {
        val peer = FakePeer(CatalogProgress("abs-item", ebookLocation = "", audioCurrentTime = 95.0, audioDuration = 100.0, lastUpdate = 9_999L))
        val result = coordinator(peer).runCycle(locator(0.0), localUpdatedAt = 1_000L)

        assertNotNull("a newer audiobook must move the reader", result.jumpLocatorJson)
        assertEquals(0.9, JSONObject(result.jumpLocatorJson!!).getJSONObject("locations").getDouble("progression"), 1e-9)
        assertTrue("the winning timestamp is at least the audiobook's", result.canonicalLastUpdate >= 9_999L)
        assertNotNull("the ebook is moved to the listened position", peer.ebookLocation)
        assertEquals("ebook progress must not be cleared", 0.9, peer.ebookProgress?.toDouble() ?: 0.0, 1e-6)
    }

    @Test
    fun `our own audiobook push does not read back as a newer remote (feedback loop closed)`() = runTest {
        val peer = FakePeer(CatalogProgress("abs-item", ebookLocation = "", lastUpdate = 1_000L))
        val coordinator = coordinator(peer)

        val pushStamp = coordinator.pushAudiobookProgress(locator(0.9))
        assertNotNull(pushStamp)
        val result = coordinator.runCycle(locator(0.0), localUpdatedAt = pushStamp!!)

        assertNull("our own push must not jump the reader", result.jumpLocatorJson)
        assertEquals("ebook stays at the reading position", 0.0, peer.ebookProgress?.toDouble() ?: 0.0, 1e-9)
    }

    @Test
    fun `a page written outbound must not read back as a newer position next cycle`() = runTest {
        val peer = FakePeer(CatalogProgress("abs-item", ebookLocation = "", lastUpdate = 0L))
        val coordinator = coordinator(peer)

        val r1 = coordinator.runCycle(locator(0.3), localUpdatedAt = 500L)
        assertNull(r1.jumpLocatorJson)

        val newLocal = maxOf(500L, r1.canonicalLastUpdate)
        val r2 = coordinator.runCycle(locator(0.3), localUpdatedAt = newLocal)
        assertNull("the just-written ebook must not read back as newer and bounce the reader", r2.jumpLocatorJson)
    }

    @Test
    fun `pushAudiobookProgress writes only the audiobook item from the page and returns the source stamp`() = runTest {
        val peer = FakePeer(CatalogProgress("abs-item", ebookLocation = "", lastUpdate = 0L))
        val ebookEp = CatalogEbookEndpoint(peer, "ebook-item")
        val audioEp = CatalogAudioEndpoint(peer = peer, itemId = "audiobook-item", durationSec = 39214.0)
        val coordinator = coordinator(peer, ebookEp, audioEp)

        val stamp = coordinator.pushAudiobookProgress(locator(0.9))

        assertNotNull("a successful push returns the server timestamp", stamp)
        assertEquals(90.0, peer.audioCurrentTime!!, 1e-9)
        assertEquals(39214.0, peer.audioDuration!!, 1e-9)
        assertNull(peer.ebookLocation)
    }

    @Test
    fun `pushAudiobookProgress is a no-op when there is no matched audiobook`() = runTest {
        val peer = FakePeer(CatalogProgress("abs-item", ebookLocation = "", lastUpdate = 0L))
        val ebookEp = CatalogEbookEndpoint(peer, "ebook-item")
        val coordinator = coordinator(peer, ebookEp, audioEp = null)

        assertNull(coordinator.pushAudiobookProgress(locator(0.9)))
        assertNull(peer.audioCurrentTime)
    }

    @Test
    fun `audio-led — a newer position elsewhere wins and the player seeks to its audio second`() = runTest {
        val peer = FakePeer(CatalogProgress("abs-item", ebookLocation = "", audioCurrentTime = 95.0, audioDuration = 100.0, lastUpdate = 9_999L))
        val coordinator = coordinator(peer)

        val r = coordinator.runAudioLedCycle(currentAudioSec = 5.0, localUpdatedAt = 1_000L)

        assertNotNull("a newer remote must seek the player", r.jumpToAudioSec)
        assertEquals(90.0, r.jumpToAudioSec!!, 1e-9)
        assertTrue(r.canonicalLastUpdate >= 9_999L)
    }

    @Test
    fun `audio-led — when the listen is newest there is no seek and both peers get the listen position`() = runTest {
        val peer = FakePeer(CatalogProgress("abs-item", ebookLocation = "", lastUpdate = 0L))
        val coordinator = coordinator(peer)

        val r = coordinator.runAudioLedCycle(currentAudioSec = 90.0, localUpdatedAt = 9_000L)

        assertNull("our own listen must not seek the player", r.jumpToAudioSec)
        assertNotNull("the ebook is advanced from the listen position", peer.ebookLocation)
        assertTrue(peer.ebookLocation!!.startsWith("epubcfi("))
        assertNotNull("the audiobook is written from the listen position", peer.audioCurrentTime)
        assertEquals(90.0, peer.audioCurrentTime!!, 1e-9)
    }

    @Test
    fun `audio-led — our own write does not read back as newer and re-seek next cycle (feedback closed)`() = runTest {
        val peer = FakePeer(CatalogProgress("abs-item", ebookLocation = "", lastUpdate = 0L))
        val coordinator = coordinator(peer)

        val r1 = coordinator.runAudioLedCycle(currentAudioSec = 90.0, localUpdatedAt = 500L)
        assertNull(r1.jumpToAudioSec)
        val r2 = coordinator.runAudioLedCycle(currentAudioSec = 90.0, localUpdatedAt = maxOf(500L, r1.canonicalLastUpdate))
        assertNull("our own just-written position must not re-seek the player", r2.jumpToAudioSec)
    }

    @Test
    fun `closing readaloud flushes the exact narrated sentence to the source even when the reader is torn down at once`() = runTest {
        val peer = FakePeer(CatalogProgress("abs-item", ebookLocation = "", lastUpdate = 0L))
        val coordinator = coordinator(peer)
        val flusher = ProgressFlushScope(TestApplicationScope(CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())))

        val viewModelScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        viewModelScope.launch { flusher.flush { coordinator.pushAudiobookForFragment("f9", null) } }
        advanceTimeBy(1)
        viewModelScope.cancel()

        advanceUntilIdle()
        assertNotNull("the audiobook position must reach the source despite teardown", peer.audioCurrentTime)
        assertEquals("the precise narrated sentence (f9 clip start) is synced", 90.0, peer.audioCurrentTime!!, 1e-9)
    }

    @Test
    fun `the audiobook player's stop flush reaches the source through the survivable scope after onCleared`() = runTest {
        val peer = FakePeer(CatalogProgress("abs-item", ebookLocation = "", lastUpdate = 0L))
        val coordinator = coordinator(peer)
        val flusher = ProgressFlushScope(TestApplicationScope(CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())))

        val viewModelScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        viewModelScope.launch { flusher.flush { coordinator.runAudioLedCycle(currentAudioSec = 90.0, localUpdatedAt = 9_000L) } }
        advanceTimeBy(1)
        viewModelScope.cancel()

        advanceUntilIdle()
        assertNotNull("the listen position must reach the source despite teardown", peer.audioCurrentTime)
        assertEquals(90.0, peer.audioCurrentTime!!, 1e-9)
    }
}
