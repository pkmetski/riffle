package com.riffle.app.feature.reader

import com.riffle.core.network.NetworkResult

import com.riffle.core.domain.DefaultPositionTranslator
import com.riffle.core.domain.ChapterProgression
import com.riffle.core.domain.MediaOverlayClip
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkServerProgress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundle-SMIL-only [AudiobookFollow] (ADR 0031): readaloud→audiobook works from the bundle alone
 * (no cross-EPUB index). Verifies fragment↔seconds translation and the ABS push, over a fake API.
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

    private class FakeApi(val stamp: Long = 4242L) : AbsSessionApi {
        var sentSeconds: Double? = null
        override suspend fun syncEbookProgress(baseUrl: String, libraryItemId: String, payload: NetworkEbookProgressPayload, token: String, insecureAllowed: Boolean) =
            NetworkResult.Success(0L)
        override suspend fun syncAudiobookProgress(baseUrl: String, libraryItemId: String, payload: NetworkAudiobookProgressPayload, token: String, insecureAllowed: Boolean): NetworkResult<Long> {
            sentSeconds = payload.currentTime; return NetworkResult.Success(stamp)
        }
        override suspend fun getProgress(baseUrl: String, libraryItemId: String, token: String, insecureAllowed: Boolean) =
            NetworkResult.Success(NetworkServerProgress(ebookLocation = "", currentTime = 0.0, lastUpdate = stamp))
    }

    private val quotes = mapOf(
        "s2" to com.riffle.core.domain.SentenceQuote(before = "before words", highlight = "the narrated sentence", after = "after words"),
    )
    private fun follow(api: AbsSessionApi) = AudiobookFollow(
        absApi = api,
        endpoint = AbsSyncEndpoint("http://abs", "tok", false, "audio-item", durationSec = 3600.0),
        translator = translator(),
        sourceId = "s1",
        audioItemId = "audio-item",
        ebookItemId = "ebook-item",
        quotes = quotes,
    )

    // c2's clips live at 4..8s absolute (after a.mp3's 0..10) — concatenation is exercised.

    @Test
    fun `secondsForFragment returns the sentence's absolute clip-begin`() {
        val f = follow(FakeApi())
        assertEquals(5.0, f.secondsForFragment("c1.html#s2")!!, 0.0001)
        assertEquals(10.0, f.secondsForFragment("c2.html#s1")!!, 0.0001) // 0.0 + a.mp3 duration 10
    }

    @Test
    fun `secondsForFragment is null for an unknown fragment`() {
        assertNull(follow(FakeApi()).secondsForFragment("c1.html#nope"))
    }

    @Test
    fun `fragmentForAudioSeconds maps an absolute second back to the narrated sentence`() {
        val f = follow(FakeApi())
        assertEquals("c1.html#s2", f.fragmentForAudioSeconds(7.0))   // 7s is within s2's 5..10
    }

    @Test
    fun `pushFragment sends the fragment's seconds to ABS and returns the server stamp`() = runTest {
        val api = FakeApi(stamp = 9999L)
        val stamp = follow(api).pushFragment("c1.html#s2")
        assertEquals(9999L, stamp)
        assertEquals(5.0, api.sentSeconds!!, 0.0001)
    }

    @Test
    fun `pushFragment does nothing for an unresolvable fragment`() = runTest {
        val api = FakeApi()
        assertNull(follow(api).pushFragment("c1.html#nope"))
        assertNull(api.sentSeconds)
    }

    @Test
    fun `ebookLocatorForAudioSeconds yields a TEXT-anchored locator (index-free audiobook to ebook)`() {
        // 7s -> sentence s2; its quote text anchors the locator so it resolves on the ABS EPUB
        // by text search — no cross-EPUB index needed (ADR 0031).
        val json = follow(FakeApi()).ebookLocatorForAudioSeconds(7.0)
        assertNotNull(json)
        assertTrue("must carry the sentence text for anchoring", json!!.contains("the narrated sentence"))
    }

    @Test
    fun `ebookLocatorForAudioSeconds is null when the sentence has no quote`() {
        // 2s -> sentence s1, which has no quote entry -> cannot text-anchor -> null (no coarse write).
        assertNull(follow(FakeApi()).ebookLocatorForAudioSeconds(2.0))
    }

    @Test
    fun `readaloudAnchorForAudioSeconds yields the narrated sentence (index-free)`() {
        // 7s -> sentence s2; the readaloud resume is the sentence ref, so reopening the reader and
        // pressing Play lands on the listened sentence rather than a stale one (ADR 0031).
        val anchor = follow(FakeApi()).readaloudAnchorForAudioSeconds(7.0)
        assertNotNull(anchor)
        assertEquals("c1.html#s2", anchor!!.fragmentRef)
        assertEquals("c1.html", anchor.href)
    }

    @Test
    fun `readaloudAnchorForAudioSeconds resolves even without a quote (the sentence ref is the anchor)`() {
        // 2s -> sentence s1, which has NO quote. Unlike ebookLocatorForAudioSeconds (which needs the
        // quote to text-anchor), the readaloud resume only needs the sentence ref to start narration,
        // so it must still resolve — else the audiobook can't move the readaloud resume here.
        val anchor = follow(FakeApi()).readaloudAnchorForAudioSeconds(2.0)
        assertNotNull(anchor)
        assertEquals("c1.html#s1", anchor!!.fragmentRef)
    }

    @Test
    fun `readaloudAnchorForAudioSeconds is null when the second has no narrated sentence`() {
        // A second past the last clip maps to no fragment -> nothing to anchor.
        assertNull(follow(FakeApi()).readaloudAnchorForAudioSeconds(99_999.0))
    }
}
