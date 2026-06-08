package com.riffle.app.feature.reader

import com.riffle.core.domain.BookSyncState
import com.riffle.core.domain.CanonicalPositionTranslator
import com.riffle.core.domain.ChapterCharMap
import com.riffle.core.domain.ChapterProgression
import com.riffle.core.domain.CrossEpubIndex
import com.riffle.core.domain.MediaOverlayClip
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkGetProgressResult
import com.riffle.core.network.NetworkServerProgress
import com.riffle.core.network.NetworkStorytellerPositionResult
import com.riffle.core.network.NetworkStorytellerPutResult
import com.riffle.core.network.NetworkSyncSessionResult
import com.riffle.core.network.StorytellerPositionApi
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreePeerReaderSyncCoordinatorTest {

    // Identity cross-EPUB index → Storyteller and ABS progressions coincide. The SMIL maps audio
    // seconds to a real near-end text position so inbound-audiobook behaviour is exercised: clip f9
    // (90–100s) narrates progression 0.9.
    private val translator = CanonicalPositionTranslator(
        smilClips = listOf(MediaOverlayClip("f9", "a.mp3", clipBeginSec = 90.0, clipEndSec = 100.0)),
        index = CrossEpubIndex(listOf(ChapterCharMap(absChars = 100, storytellerChars = 100))),
        fragmentProgressions = mapOf("f9" to ChapterProgression(0, 0.9)),
    )
    private val absHtml = "<html><body><p>${"x".repeat(100)}</p></body></html>"
    private val bridge = ReaderPositionBridge(
        absSpineHrefs = listOf("c1.xhtml"),
        absChapterHtml = { if (it == 0) absHtml else null },
        storytellerSpineHrefs = listOf("c1.xhtml"),
        storytellerChapterHtml = { if (it == 0) absHtml else null },
        translator = translator,
    )

    private val state = BookSyncState(
        isMatched = true, hasAbsEbookTarget = true, hasAbsAudioTarget = true, prerequisitesCached = true,
    )
    private val absEp = AbsSyncEndpoint("http://abs", "t", false, "abs-item", durationSec = 100.0)
    private val stEp = StorytellerSyncEndpoint("http://st", "t", false, "st-book")

    private fun locator(progression: Double) = JSONObject()
        .put("href", "c1.xhtml")
        .put("locations", JSONObject().put("progression", progression))
        .toString()

    // A minimal in-memory ABS server: a write updates the stored record and stamps it with a fresh,
    // monotonically-increasing lastUpdate (which it returns), and a read returns the current record —
    // so a push is visible to the next cycle exactly as on the real server (the feedback path).
    private class FakeAbs(initial: NetworkServerProgress) : AbsSessionApi {
        var progress = initial
        private var clock = initial.lastUpdate
        var ebookPatch: NetworkEbookProgressPayload? = null
        var ebookPatchItemId: String? = null
        var audioPatch: NetworkAudiobookProgressPayload? = null
        var audioPatchItemId: String? = null
        val getProgressItemIds = mutableListOf<String>()

        private fun nextStamp(): Long { clock += 1_000; return clock }

        override suspend fun syncEbookProgress(baseUrl: String, libraryItemId: String, payload: NetworkEbookProgressPayload, token: String, insecureAllowed: Boolean): NetworkSyncSessionResult {
            ebookPatch = payload; ebookPatchItemId = libraryItemId
            val stamp = nextStamp()
            progress = progress.copy(ebookLocation = payload.ebookLocation, ebookProgress = payload.ebookProgress, lastUpdate = stamp)
            return NetworkSyncSessionResult.Success(stamp)
        }
        override suspend fun syncAudiobookProgress(baseUrl: String, libraryItemId: String, payload: NetworkAudiobookProgressPayload, token: String, insecureAllowed: Boolean): NetworkSyncSessionResult {
            audioPatch = payload; audioPatchItemId = libraryItemId
            val stamp = nextStamp()
            progress = progress.copy(currentTime = payload.currentTime, duration = payload.duration, lastUpdate = stamp)
            return NetworkSyncSessionResult.Success(stamp)
        }
        override suspend fun getProgress(baseUrl: String, libraryItemId: String, token: String, insecureAllowed: Boolean): NetworkGetProgressResult {
            getProgressItemIds += libraryItemId; return NetworkGetProgressResult.Success(progress)
        }
    }

    private class FakeStoryteller(
        private val get: NetworkStorytellerPositionResult,
    ) : StorytellerPositionApi {
        var put: String? = null
        override suspend fun getPosition(baseUrl: String, bookId: String, token: String, insecureAllowed: Boolean) = get
        override suspend fun putPosition(baseUrl: String, bookId: String, locatorJson: String, timestampMillis: Long, token: String, insecureAllowed: Boolean): NetworkStorytellerPutResult {
            put = locatorJson; return NetworkStorytellerPutResult.Success
        }
    }

    private fun coordinator(abs: FakeAbs, st: FakeStoryteller, ebookEp: AbsSyncEndpoint = absEp, audioEp: AbsSyncEndpoint? = absEp) =
        ThreePeerReaderSyncCoordinator(state, bridge, abs, st, ebookEp, audioEp, stEp)

    // ── reading-only / cross-device ebook+Storyteller ───────────────────────────────

    @Test
    fun `a newer Storyteller position wins the cycle and the reader jumps to it`() = runTest {
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", lastUpdate = 0L))
        val st = FakeStoryteller(NetworkStorytellerPositionResult.Success(locator(0.6), 5_000L))

        val result = coordinator(abs, st).runCycle(locator(0.2), localUpdatedAt = 1_000L)

        assertNotNull(result.jumpLocatorJson)
        assertEquals(0.6, JSONObject(result.jumpLocatorJson!!).getJSONObject("locations").getDouble("progression"), 1e-9)
        assertEquals(5_000L, result.canonicalLastUpdate)
        assertNull("the winner is not patched", st.put)
    }

    @Test
    fun `when local is newest there is no jump and the ebook and Storyteller get the page`() = runTest {
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", lastUpdate = 0L))
        val st = FakeStoryteller(NetworkStorytellerPositionResult.NoPosition)

        val result = coordinator(abs, st).runCycle(locator(0.3), localUpdatedAt = 9_000L)

        assertNull(result.jumpLocatorJson)
        assertNotNull("ABS ebook received the first write", abs.ebookPatch)
        assertTrue(abs.ebookPatch!!.ebookLocation.startsWith("epubcfi("))
        assertNotNull("Storyteller received the first write", st.put)
        // The audiobook is inbound-only: the cycle reads it but never writes it.
        assertTrue("the cycle reads the audiobook inbound", "abs-item" in abs.getProgressItemIds)
        assertNull("the cycle must not write the audiobook", abs.audioPatch)
    }

    // ── inbound audiobook (the feature) ─────────────────────────────────────────────

    @Test
    fun `a genuinely newer audiobook listened elsewhere wins and jumps the reader to the bundle page`() = runTest {
        // Another device left the audiobook at 95s (→ progression 0.9) with a fresh timestamp; the
        // local reading position is at the start and older. Inbound audiobook must win and move the
        // reader to the bundle-translated page — "server updates reflected locally".
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", currentTime = 95.0, duration = 100.0, lastUpdate = 9_999L))
        val st = FakeStoryteller(NetworkStorytellerPositionResult.NoPosition)

        val result = coordinator(abs, st).runCycle(locator(0.0), localUpdatedAt = 1_000L)

        assertNotNull("a newer audiobook must move the reader", result.jumpLocatorJson)
        assertEquals(0.9, JSONObject(result.jumpLocatorJson!!).getJSONObject("locations").getDouble("progression"), 1e-9)
        assertEquals(9_999L, result.canonicalLastUpdate)
        // The win propagates to the ebook so the location is reflected there too.
        assertNotNull("the ebook is moved to the listened position", abs.ebookPatch)
    }

    @Test
    fun `our own audiobook push does not read back as a newer remote (feedback loop closed)`() = runTest {
        // The exact erase mechanism: we push the live audio (95s → near-end) to the audiobook, then a
        // cycle runs. Without the timestamp-recording fix the audiobook reads back newer than the page
        // and drives the ebook to the audio position. With it, the caller records the push's server
        // timestamp as localUpdatedAt, so the read comes back EQUAL and the reading position wins.
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", lastUpdate = 1_000L))
        val st = FakeStoryteller(NetworkStorytellerPositionResult.NoPosition)
        val coordinator = coordinator(abs, st)

        val pushStamp = coordinator.pushAudiobookSeconds(95.0) // audio ran ahead of the page
        assertNotNull(pushStamp)
        // The caller (the ViewModel) records pushStamp as the local timestamp; simulate that here.
        val result = coordinator.runCycle(locator(0.0), localUpdatedAt = pushStamp!!)

        assertNull("our own push must not jump the reader", result.jumpLocatorJson)
        // The ebook keeps the reading position (start), never the audio's near-end.
        assertEquals("ebook stays at the reading position", 0.0, abs.ebookPatch?.ebookProgress?.toDouble() ?: 0.0, 1e-9)
    }

    // ── push-only outbound ──────────────────────────────────────────────────────────

    @Test
    fun `pushAudiobookSeconds writes only the audiobook item and returns the server timestamp`() = runTest {
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", lastUpdate = 0L))
        val st = FakeStoryteller(NetworkStorytellerPositionResult.NoPosition)
        val ebookEp = AbsSyncEndpoint("http://abs", "t", false, "ebook-item")
        val audioEp = AbsSyncEndpoint("http://abs", "t", false, "audiobook-item", durationSec = 39214.0)
        val coordinator = coordinator(abs, st, ebookEp, audioEp)

        val stamp = coordinator.pushAudiobookSeconds(1443.0)

        assertNotNull("a successful push returns the server timestamp", stamp)
        assertEquals(1443.0, abs.audioPatch!!.currentTime, 1e-9)
        assertEquals(39214.0, abs.audioPatch!!.duration, 1e-9)
        assertEquals("audiobook-item", abs.audioPatchItemId)
        // Decoupled: the ebook is never touched, so it can never be erased by the audio.
        assertNull(abs.ebookPatch)
        assertNull(abs.ebookPatchItemId)
    }

    @Test
    fun `pushAudiobookSeconds is a no-op when there is no matched audiobook`() = runTest {
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", lastUpdate = 0L))
        val st = FakeStoryteller(NetworkStorytellerPositionResult.NoPosition)
        val ebookEp = AbsSyncEndpoint("http://abs", "t", false, "ebook-item")
        val coordinator = coordinator(abs, st, ebookEp, audioEp = null)

        assertNull(coordinator.pushAudiobookSeconds(1443.0))
        assertNull(abs.audioPatch)
    }

    // ── split libraries (distinct ebook / audiobook items) ──────────────────────────

    @Test
    fun `split libraries route ebook progress and the push to their own item ids`() = runTest {
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", lastUpdate = 0L))
        val st = FakeStoryteller(NetworkStorytellerPositionResult.NoPosition)
        val ebookEp = AbsSyncEndpoint("http://abs", "t", false, "ebook-item")
        val audioEp = AbsSyncEndpoint("http://abs", "t", false, "audiobook-item", durationSec = 100.0)
        val coordinator = coordinator(abs, st, ebookEp, audioEp)

        coordinator.runCycle(locator(0.3), localUpdatedAt = 9_000L)
        coordinator.pushAudiobookSeconds(42.0)

        assertEquals("ebook progress goes to the ebook item", "ebook-item", abs.ebookPatchItemId)
        assertTrue("the cycle reads the ebook item", "ebook-item" in abs.getProgressItemIds)
        assertTrue("the cycle reads the audiobook item inbound", "audiobook-item" in abs.getProgressItemIds)
        assertEquals("the push targets the audiobook item", "audiobook-item", abs.audioPatchItemId)
    }
}
