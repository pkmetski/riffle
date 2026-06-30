package com.riffle.app.feature.reader

import com.riffle.core.network.NetworkResult

import com.riffle.core.domain.DefaultPositionTranslator
import com.riffle.core.domain.CanonicalReaderPosition
import com.riffle.core.domain.ChapterCharMap
import com.riffle.core.domain.ChapterProgression
import com.riffle.core.domain.CrossEpubIndex
import com.riffle.core.domain.MediaOverlayClip
import com.riffle.core.domain.WriteResult
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkServerProgress
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Pins the new [WriteResult] contract on the ABS peer adapters (#302): a translator boundary that
 * can't place the canonical position is a deliberate [WriteResult.Skipped], not a [WriteResult.Failed]
 * — the row is left for a later cycle, never marked as a sync failure to act on. Network errors
 * remain Failed.
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
    private val ep = AbsSyncEndpoint("http://abs", "t", false, "i", durationSec = 100.0)

    private fun locator(href: String, progression: Double): String = JSONObject()
        .put("href", href)
        .put("locations", JSONObject().put("progression", progression))
        .toString()

    /** PATCH succeeds and the GET stamp read-back is what gets adopted by the cycle. */
    private class OkAbs(private val stamp: Long) : AbsSessionApi {
        override suspend fun syncEbookProgress(baseUrl: String, libraryItemId: String, payload: NetworkEbookProgressPayload, token: String, insecureAllowed: Boolean) =
            NetworkResult.Success(stamp)
        override suspend fun syncAudiobookProgress(baseUrl: String, libraryItemId: String, payload: NetworkAudiobookProgressPayload, token: String, insecureAllowed: Boolean) =
            NetworkResult.Success(stamp)
        override suspend fun getProgress(baseUrl: String, libraryItemId: String, token: String, insecureAllowed: Boolean) =
            NetworkResult.Success(NetworkServerProgress(ebookLocation = "", lastUpdate = stamp))
    }

    /** PATCH fails at the network. */
    private class PatchFailAbs : AbsSessionApi {
        override suspend fun syncEbookProgress(baseUrl: String, libraryItemId: String, payload: NetworkEbookProgressPayload, token: String, insecureAllowed: Boolean) =
            NetworkResult.Offline(IOException("boom"))
        override suspend fun syncAudiobookProgress(baseUrl: String, libraryItemId: String, payload: NetworkAudiobookProgressPayload, token: String, insecureAllowed: Boolean) =
            NetworkResult.Offline(IOException("boom"))
        override suspend fun getProgress(baseUrl: String, libraryItemId: String, token: String, insecureAllowed: Boolean) =
            NetworkResult.Offline(IOException("boom"))
    }

    /** PATCH OK but the follow-up GET (the stamp read-back) fails. */
    private class StampReadbackFailAbs : AbsSessionApi {
        override suspend fun syncEbookProgress(baseUrl: String, libraryItemId: String, payload: NetworkEbookProgressPayload, token: String, insecureAllowed: Boolean) =
            NetworkResult.Success(0L)
        override suspend fun syncAudiobookProgress(baseUrl: String, libraryItemId: String, payload: NetworkAudiobookProgressPayload, token: String, insecureAllowed: Boolean) =
            NetworkResult.Success(0L)
        override suspend fun getProgress(baseUrl: String, libraryItemId: String, token: String, insecureAllowed: Boolean) =
            NetworkResult.Offline(IOException("stamp read-back failed"))
    }

    @Test
    fun `ebook peer Ok when network PATCH succeeds and stamp comes back`() = runTest {
        val peer = AbsEbookProgressPeer(OkAbs(stamp = 1234L), ep, translator)
        val result = peer.tryPatch(CanonicalReaderPosition(locator("c1.xhtml", 0.5)))
        assertEquals(WriteResult.Ok(1234L), result)
    }

    @Test
    fun `ebook peer Skipped when canonical can't be translated to a CFI (deliberate no-op)`() = runTest {
        // href not in the spine → translator.canonicalToAbsCfi returns null → Skipped (not Failed): the
        // peer made no network attempt; the row is not dirty, the cycle will retry next pass.
        val peer = AbsEbookProgressPeer(OkAbs(stamp = 1234L), ep, translator)
        val result = peer.tryPatch(CanonicalReaderPosition(locator("nonexistent.xhtml", 0.5)))
        assertEquals(WriteResult.Skipped, result)
    }

    @Test
    fun `ebook peer Failed when ABS PATCH errors`() = runTest {
        val peer = AbsEbookProgressPeer(PatchFailAbs(), ep, translator)
        val result = peer.tryPatch(CanonicalReaderPosition(locator("c1.xhtml", 0.5)))
        assertTrue("expected Failed, was $result", result is WriteResult.Failed)
    }

    @Test
    fun `ebook peer Failed when stamp read-back fails (PATCH stored, stamp unknown)`() = runTest {
        // ABS itself returns Success with no stamp; the read-back GET fails. The cycle needs the stamp
        // to break the feedback loop, so report Failed — not Ok with a fake stamp.
        val peer = AbsEbookProgressPeer(StampReadbackFailAbs(), ep, translator)
        val result = peer.tryPatch(CanonicalReaderPosition(locator("c1.xhtml", 0.5)))
        assertTrue("expected Failed, was $result", result is WriteResult.Failed)
    }

    @Test
    fun `audiobook peer Ok when SMIL placement + PATCH + stamp succeed`() = runTest {
        val peer = AbsAudiobookProgressPeer(OkAbs(stamp = 9000L), ep, translator)
        // progression 0.2 places inside the SMIL clip f1 (5–10s) — translates to a valid audio second
        val result = peer.tryPatch(CanonicalReaderPosition(locator("c1.xhtml", 0.2)))
        assertEquals(WriteResult.Ok(9000L), result)
    }

    @Test
    fun `audiobook peer Failed when ABS PATCH errors`() = runTest {
        val peer = AbsAudiobookProgressPeer(PatchFailAbs(), ep, translator)
        val result = peer.tryPatch(CanonicalReaderPosition(locator("c1.xhtml", 0.2)))
        assertTrue("expected Failed, was $result", result is WriteResult.Failed)
    }
}
