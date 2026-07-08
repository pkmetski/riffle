package com.riffle.app.feature.reader

import com.riffle.core.catalog.CatalogProgress
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.domain.CanonicalReaderPosition
import com.riffle.core.domain.ChapterCharMap
import com.riffle.core.domain.ChapterProgression
import com.riffle.core.domain.Clock
import com.riffle.core.domain.CrossEpubIndex
import com.riffle.core.domain.DefaultPositionTranslator
import com.riffle.core.domain.MediaOverlayClip
import com.riffle.core.domain.WriteResult
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the [WriteResult] contract on the Catalog-backed peer adapters (#302 / #434): a translator
 * boundary that can't place the canonical position is a deliberate [WriteResult.Skipped], not a
 * [WriteResult.Failed] — the row is left for a later cycle, never marked as a sync failure to act
 * on. Network errors remain Failed.
 */
class AbsProgressPeerTest {

    private val absHtml = "<html><body><p>${"x".repeat(100)}</p></body></html>"
    private val translator = DefaultPositionTranslator(
        smilClips = listOf(MediaOverlayClip("f1", "a.mp3", clipBeginSec = 5.0, clipEndSec = 10.0)),
        crossEpubIndex = CrossEpubIndex(listOf(ChapterCharMap(absChars = 100, storytellerChars = 100))),
        fragmentProgressions = mapOf("f1" to ChapterProgression(0, 0.2)),
        absSpineHrefs = listOf("c1.xhtml"),
        absChapterHtml = { if (it == 0) absHtml else null },
        storytellerSpineHrefs = listOf("c1.xhtml"),
        storytellerChapterHtml = { if (it == 0) absHtml else null },
    )
    private val clock = object : Clock {
        override fun nowMs() = 9000L
        override fun nowNs() = 0L
    }

    private fun endpoint(peer: ProgressPeerCapability) = CatalogSyncEndpoint(peer, "i", durationSec = 100.0)

    private fun locator(href: String, progression: Double): String = JSONObject()
        .put("href", href)
        .put("locations", JSONObject().put("progression", progression))
        .toString()

    /** PATCH succeeds — peer echoes the stamp back mirroring ABS's PATCH response. */
    private class OkPeer(private val stamp: Long) : ProgressPeerCapability {
        override suspend fun pushEbookProgress(itemId: String, location: String, progress: Float, isFinished: Boolean, lastUpdateEpochMs: Long) = stamp
        override suspend fun pushAudiobookProgress(itemId: String, currentTimeSec: Double, durationSec: Double, isFinished: Boolean, lastUpdateEpochMs: Long) = stamp
        override suspend fun pullProgress(itemId: String): CatalogProgress? = null
        override suspend fun pullAllProgress(): List<CatalogProgress> = emptyList()
    }

    /** PATCH fails at the network. */
    private class FailPeer : ProgressPeerCapability {
        override suspend fun pushEbookProgress(itemId: String, location: String, progress: Float, isFinished: Boolean, lastUpdateEpochMs: Long): Long? = throw java.io.IOException("boom")
        override suspend fun pushAudiobookProgress(itemId: String, currentTimeSec: Double, durationSec: Double, isFinished: Boolean, lastUpdateEpochMs: Long): Long? = throw java.io.IOException("boom")
        override suspend fun pullProgress(itemId: String): CatalogProgress? = null
        override suspend fun pullAllProgress(): List<CatalogProgress> = emptyList()
    }

    @Test
    fun `ebook peer Ok when network PATCH succeeds and stamp comes back`() = runTest {
        val peer = AbsEbookProgressPeer(endpoint(OkPeer(stamp = 1234L)), translator, clock)
        val result = peer.tryPatch(CanonicalReaderPosition(locator("c1.xhtml", 0.5)))
        assertEquals(WriteResult.Ok(1234L), result)
    }

    @Test
    fun `ebook peer Skipped when canonical can't be translated to a CFI (deliberate no-op)`() = runTest {
        val peer = AbsEbookProgressPeer(endpoint(OkPeer(stamp = 1234L)), translator, clock)
        val result = peer.tryPatch(CanonicalReaderPosition(locator("nonexistent.xhtml", 0.5)))
        assertEquals(WriteResult.Skipped, result)
    }

    @Test
    fun `ebook peer Failed when Catalog push errors`() = runTest {
        val peer = AbsEbookProgressPeer(endpoint(FailPeer()), translator, clock)
        val result = peer.tryPatch(CanonicalReaderPosition(locator("c1.xhtml", 0.5)))
        assertTrue("expected Failed, was $result", result is WriteResult.Failed)
    }

    @Test
    fun `audiobook peer Ok when SMIL placement + PATCH succeed`() = runTest {
        val peer = AbsAudiobookProgressPeer(endpoint(OkPeer(stamp = 9000L)), translator, clock)
        val result = peer.tryPatch(CanonicalReaderPosition(locator("c1.xhtml", 0.2)))
        assertEquals(WriteResult.Ok(9000L), result)
    }

    @Test
    fun `audiobook peer Failed when Catalog push errors`() = runTest {
        val peer = AbsAudiobookProgressPeer(endpoint(FailPeer()), translator, clock)
        val result = peer.tryPatch(CanonicalReaderPosition(locator("c1.xhtml", 0.2)))
        assertTrue("expected Failed, was $result", result is WriteResult.Failed)
    }
}
