package com.riffle.app.feature.reader

import com.riffle.core.catalog.AudiobookProgressPeerCapability
import com.riffle.core.catalog.CatalogProgress
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.domain.ChapterProgression
import com.riffle.core.common.Clock
import com.riffle.core.domain.DefaultPositionTranslator
import com.riffle.core.domain.MediaOverlayClip
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundle-SMIL-only [AudiobookFollow] (ADR 0031): readaloud→audiobook works from the bundle alone
 * (no cross-EPUB index). Verifies fragment↔seconds translation and the Catalog push, over a fake peer.
 */
class AudiobookFollowTest {

    private val clips = listOf(
        MediaOverlayClip("c1.html#s1", "a.mp3", clipBeginSec = 0.0, clipEndSec = 5.0),
        MediaOverlayClip("c1.html#s2", "a.mp3", clipBeginSec = 5.0, clipEndSec = 10.0),
        MediaOverlayClip("c2.html#s1", "b.mp3", clipBeginSec = 0.0, clipEndSec = 4.0),
    )
    private val fragmentProgressions = mapOf(
        "c1.html#s1" to ChapterProgression(0, 0.0),
        "c1.html#s2" to ChapterProgression(0, 0.5),
        "c2.html#s1" to ChapterProgression(1, 0.0),
    )
    private fun translator() = DefaultPositionTranslator(clips, fragmentProgressions = fragmentProgressions)

    private class FakePeer(val stamp: Long = 4242L) : ProgressPeerCapability, AudiobookProgressPeerCapability {
        var sentSeconds: Double? = null
        override suspend fun pushEbookProgress(itemId: String, location: String, progress: Float, isFinished: Boolean?, lastUpdateEpochMs: Long) = 0L
        override suspend fun pushAudiobookProgress(itemId: String, currentTimeSec: Double, durationSec: Double, isFinished: Boolean?, lastUpdateEpochMs: Long): Long {
            sentSeconds = currentTimeSec
            return stamp
        }
        override suspend fun pullProgress(itemId: String): CatalogProgress? = null
        override suspend fun pullAllProgress(): List<CatalogProgress> = emptyList()
    }

    private val quotes = mapOf(
        "s2" to com.riffle.core.domain.SentenceQuote(before = "before words", highlight = "the narrated sentence", after = "after words"),
    )

    private val clock = object : Clock {
        override fun nowMs() = 1_000L
        override fun nowNs() = 0L
    }

    private fun follow(peer: FakePeer) = AudiobookFollow(
        endpoint = CatalogAudioEndpoint(peer = peer, itemId = "audio-item", durationSec = 3600.0),
        translator = translator(),
        clock = clock,
        sourceId = "s1",
        audioItemId = "audio-item",
        ebookItemId = "ebook-item",
        quotes = quotes,
    )

    @Test
    fun `secondsForFragment returns the sentence's absolute clip-begin`() {
        val f = follow(FakePeer())
        assertEquals(5.0, f.secondsForFragment("c1.html#s2")!!, 0.0001)
        assertEquals(10.0, f.secondsForFragment("c2.html#s1")!!, 0.0001)
    }

    @Test
    fun `secondsForFragment is null for an unknown fragment`() {
        assertNull(follow(FakePeer()).secondsForFragment("c1.html#nope"))
    }

    @Test
    fun `fragmentForAudioSeconds maps an absolute second back to the narrated sentence`() {
        val f = follow(FakePeer())
        assertEquals("c1.html#s2", f.fragmentForAudioSeconds(7.0))
    }

    @Test
    fun `pushFragment sends the fragment's seconds through the Catalog and returns the source stamp`() = runTest {
        val peer = FakePeer(stamp = 9999L)
        val stamp = follow(peer).pushFragment("c1.html#s2")
        assertEquals(9999L, stamp)
        assertEquals(5.0, peer.sentSeconds!!, 0.0001)
    }

    @Test
    fun `pushFragment does nothing for an unresolvable fragment`() = runTest {
        val peer = FakePeer()
        assertNull(follow(peer).pushFragment("c1.html#nope"))
        assertNull(peer.sentSeconds)
    }

    @Test
    fun `ebookLocatorForAudioSeconds yields a TEXT-anchored locator (index-free audiobook to ebook)`() {
        val json = follow(FakePeer()).ebookLocatorForAudioSeconds(7.0)
        assertNotNull(json)
        assertTrue("must carry the sentence text for anchoring", json!!.contains("the narrated sentence"))
    }

    @Test
    fun `ebookLocatorForAudioSeconds is null when the sentence has no quote`() {
        assertNull(follow(FakePeer()).ebookLocatorForAudioSeconds(2.0))
    }

    @Test
    fun `readaloudAnchorForAudioSeconds yields the narrated sentence (index-free)`() {
        val anchor = follow(FakePeer()).readaloudAnchorForAudioSeconds(7.0)
        assertNotNull(anchor)
        assertEquals("c1.html#s2", anchor!!.fragmentRef)
        assertEquals("c1.html", anchor.href)
    }

    @Test
    fun `readaloudAnchorForAudioSeconds resolves even without a quote (the sentence ref is the anchor)`() {
        val anchor = follow(FakePeer()).readaloudAnchorForAudioSeconds(2.0)
        assertNotNull(anchor)
        assertEquals("c1.html#s1", anchor!!.fragmentRef)
    }

    @Test
    fun `readaloudAnchorForAudioSeconds is null when the second has no narrated sentence`() {
        assertNull(follow(FakePeer()).readaloudAnchorForAudioSeconds(99_999.0))
    }
}
