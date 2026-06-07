package com.riffle.app.feature.reader

import com.riffle.core.domain.BookSyncState
import com.riffle.core.domain.CanonicalPositionTranslator
import com.riffle.core.domain.ChapterCharMap
import com.riffle.core.domain.CrossEpubIndex
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

    // Identity cross-EPUB index → Storyteller and ABS progressions coincide; keeps the assertions
    // about routing/winner selection independent of the (separately-tested) translation math.
    private val translator = CanonicalPositionTranslator(
        smilClips = emptyList(),
        index = CrossEpubIndex(listOf(ChapterCharMap(absChars = 100, storytellerChars = 100))),
    )
    private val absHtml = "<html><body><p>${"x".repeat(100)}</p></body></html>"
    private val bridge = ReaderPositionBridge(
        absSpineHrefs = listOf("c1.xhtml"),
        absChapterHtml = { if (it == 0) absHtml else null },
        storytellerSpineHrefs = listOf("c1.xhtml"),
        storytellerChapterHtml = { null },
        translator = translator,
    )

    private val state = BookSyncState(
        isMatched = true, hasAbsEbookTarget = true, hasAbsAudioTarget = true, prerequisitesCached = true,
    )
    private val absEp = AbsSyncEndpoint("http://abs", "t", false, "abs-item")
    private val stEp = StorytellerSyncEndpoint("http://st", "t", false, "st-book")

    private fun locator(progression: Double) = JSONObject()
        .put("href", "c1.xhtml")
        .put("locations", JSONObject().put("progression", progression))
        .toString()

    private class FakeAbs(
        var progress: NetworkServerProgress,
    ) : AbsSessionApi {
        var ebookPatch: NetworkEbookProgressPayload? = null
        var ebookPatchItemId: String? = null
        var audioPatch: NetworkAudiobookProgressPayload? = null
        var audioPatchItemId: String? = null
        override suspend fun syncEbookProgress(baseUrl: String, libraryItemId: String, payload: NetworkEbookProgressPayload, token: String, insecureAllowed: Boolean): NetworkSyncSessionResult {
            ebookPatch = payload; ebookPatchItemId = libraryItemId; return NetworkSyncSessionResult.Success(0L)
        }
        override suspend fun syncAudiobookProgress(baseUrl: String, libraryItemId: String, payload: NetworkAudiobookProgressPayload, token: String, insecureAllowed: Boolean): NetworkSyncSessionResult {
            audioPatch = payload; audioPatchItemId = libraryItemId; return NetworkSyncSessionResult.Success(0L)
        }
        val getProgressItemIds = mutableListOf<String>()
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

    @Test
    fun `a newer Storyteller position wins the cycle and the reader jumps to it`() = runTest {
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", lastUpdate = 0L)) // empty ABS record
        val st = FakeStoryteller(NetworkStorytellerPositionResult.Success(locator(0.6), 5_000L))
        val coordinator = ThreePeerReaderSyncCoordinator(state, bridge, abs, st, absEp, absEp, stEp)

        val result = coordinator.runCycle(locator(0.2), localUpdatedAt = 1_000L)

        assertNotNull(result.jumpLocatorJson)
        assertEquals(0.6, JSONObject(result.jumpLocatorJson!!).getJSONObject("locations").getDouble("progression"), 1e-9)
        assertEquals(5_000L, result.canonicalLastUpdate)
        assertNull("the winner is not patched", st.put)
    }

    @Test
    fun `when local is newest there is no jump and every peer is pushed the local position`() = runTest {
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", lastUpdate = 0L))
        val st = FakeStoryteller(NetworkStorytellerPositionResult.NoPosition)
        val coordinator = ThreePeerReaderSyncCoordinator(state, bridge, abs, st, absEp, absEp, stEp)

        val result = coordinator.runCycle(locator(0.3), localUpdatedAt = 9_000L)

        assertNull(result.jumpLocatorJson)
        assertNotNull("ABS ebook received the first write", abs.ebookPatch)
        assertTrue(abs.ebookPatch!!.ebookLocation.startsWith("epubcfi("))
        assertNotNull("Storyteller received the first write", st.put)
        // Book-only reading (no audio push): the reconcile cycle must never write the audiobook.
        assertNull("the cycle must not touch the audiobook record", abs.audioPatch)
    }

    @Test
    fun `split libraries route ebook progress and the push-only audiobook to their own item ids`() = runTest {
        // Separate ABS items: an ebook item and an audiobook item, matched to the same bundle.
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", lastUpdate = 0L))
        val st = FakeStoryteller(NetworkStorytellerPositionResult.NoPosition)
        val ebookEp = AbsSyncEndpoint("http://abs", "t", false, "ebook-item")
        val audioEp = AbsSyncEndpoint("http://abs", "t", false, "audiobook-item", durationSec = 100.0)
        val coordinator = ThreePeerReaderSyncCoordinator(state, bridge, abs, st, ebookEp, audioEp, stEp)

        coordinator.runCycle(locator(0.3), localUpdatedAt = 9_000L)
        coordinator.pushAudiobookSeconds(42.0)

        // The reconcile cycle writes ebook progress to the ebook item; it never reads or writes the
        // audiobook item (push-only). The audiobook item is written only by the push.
        assertEquals("ebook-item", abs.ebookPatchItemId)
        assertTrue("ebook remote targets the ebook item", "ebook-item" in abs.getProgressItemIds)
        assertTrue("the cycle never reads the audiobook item", "audiobook-item" !in abs.getProgressItemIds)
        assertEquals("the push targets the audiobook item", "audiobook-item", abs.audioPatchItemId)
    }

    @Test
    fun `pushAudiobookSeconds writes only the audiobook item, never the ebook`() = runTest {
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", lastUpdate = 0L))
        val st = FakeStoryteller(NetworkStorytellerPositionResult.NoPosition)
        val ebookEp = AbsSyncEndpoint("http://abs", "t", false, "ebook-item")
        val audioEp = AbsSyncEndpoint("http://abs", "t", false, "audiobook-item", durationSec = 39214.0)
        val coordinator = ThreePeerReaderSyncCoordinator(state, bridge, abs, st, ebookEp, audioEp, stEp)

        val ok = coordinator.pushAudiobookSeconds(1443.0)

        assertTrue(ok)
        assertEquals(1443.0, abs.audioPatch!!.currentTime, 1e-9)
        assertEquals(39214.0, abs.audioPatch!!.duration, 1e-9)
        assertEquals("audiobook-item", abs.audioPatchItemId)
        // Decoupled: the ebook is never touched, so it can never be erased by the audio.
        assertNull(abs.ebookPatch)
        assertNull(abs.ebookPatchItemId)
    }

    @Test
    fun `an audiobook ahead of the reading position must NOT drive the ebook to the audio position`() = runTest {
        // THE bug ("ebook set to almost-end where it should be beginning"): the audio is ahead of the
        // page (readaloud started behind, or a prior push wrote the live audio time). If the audiobook
        // is a reconciled inbound peer, its fresh record wins the cycle and propagates to the ebook —
        // erasing the reading position. The invariant: the audio must never drive the ebook.
        // A SMIL/index so the audiobook's currentTime maps to a real near-end canonical.
        val translator = CanonicalPositionTranslator(
            smilClips = listOf(
                com.riffle.core.domain.MediaOverlayClip("f9", "a.mp3", clipBeginSec = 90.0, clipEndSec = 100.0),
            ),
            index = CrossEpubIndex(listOf(ChapterCharMap(absChars = 100, storytellerChars = 100))),
            fragmentProgressions = mapOf("f9" to com.riffle.core.domain.ChapterProgression(0, 0.9)),
        )
        val bridge = ReaderPositionBridge(
            absSpineHrefs = listOf("c1.xhtml"),
            absChapterHtml = { if (it == 0) absHtml else null },
            storytellerSpineHrefs = listOf("c1.xhtml"),
            storytellerChapterHtml = { if (it == 0) absHtml else null },
            translator = translator,
        )
        // Combined item: ebook empty, audiobook at 95s (near-end) with a fresh timestamp.
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", currentTime = 95.0, duration = 100.0, lastUpdate = 9_999L))
        val st = FakeStoryteller(NetworkStorytellerPositionResult.NoPosition)
        val coordinator = ThreePeerReaderSyncCoordinator(state, bridge, abs, st, absEp, absEp, stEp)

        // The reader is at the beginning, last saved earlier than the audiobook record.
        val result = coordinator.runCycle(locator(0.0), localUpdatedAt = 1_000L)

        assertNull("the reader must not jump to the audiobook position", result.jumpLocatorJson)
        // The ebook, if written, must hold the reading position (beginning) — never the audio's near-end.
        assertEquals("ebook progress must stay at the reading position", 0.0, abs.ebookPatch?.ebookProgress?.toDouble() ?: 0.0, 1e-9)
    }

    @Test
    fun `pushAudiobookSeconds is a no-op when there is no matched audiobook`() = runTest {
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", lastUpdate = 0L))
        val st = FakeStoryteller(NetworkStorytellerPositionResult.NoPosition)
        val ebookEp = AbsSyncEndpoint("http://abs", "t", false, "ebook-item")
        val coordinator = ThreePeerReaderSyncCoordinator(state, bridge, abs, st, ebookEp, absAudioEndpoint = null, storytellerEndpoint = stEp)

        assertEquals(false, coordinator.pushAudiobookSeconds(1443.0))
        assertNull(abs.audioPatch)
    }
}
