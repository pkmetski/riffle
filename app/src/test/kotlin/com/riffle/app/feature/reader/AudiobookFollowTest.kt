package com.riffle.app.feature.reader

import com.riffle.core.domain.CanonicalPositionTranslator
import com.riffle.core.domain.ChapterProgression
import com.riffle.core.domain.MediaOverlayClip
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkGetProgressResult
import com.riffle.core.network.NetworkServerProgress
import com.riffle.core.network.NetworkSyncSessionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    private fun translator() = CanonicalPositionTranslator(clips, fragmentProgressions = fragmentProgressions)

    private class FakeApi(val stamp: Long = 4242L) : AbsSessionApi {
        var sentSeconds: Double? = null
        override suspend fun syncEbookProgress(baseUrl: String, libraryItemId: String, payload: NetworkEbookProgressPayload, token: String, insecureAllowed: Boolean) =
            NetworkSyncSessionResult.Success(0L)
        override suspend fun syncAudiobookProgress(baseUrl: String, libraryItemId: String, payload: NetworkAudiobookProgressPayload, token: String, insecureAllowed: Boolean): NetworkSyncSessionResult {
            sentSeconds = payload.currentTime; return NetworkSyncSessionResult.Success(stamp)
        }
        override suspend fun getProgress(baseUrl: String, libraryItemId: String, token: String, insecureAllowed: Boolean) =
            NetworkGetProgressResult.Success(NetworkServerProgress(ebookLocation = "", currentTime = 0.0, lastUpdate = stamp))
    }

    private fun follow(api: AbsSessionApi) = AudiobookFollow(
        absApi = api,
        endpoint = AbsSyncEndpoint("http://abs", "tok", false, "audio-item", durationSec = 3600.0),
        translator = translator(),
        serverId = "s1",
        audioItemId = "audio-item",
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
}
