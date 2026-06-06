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
    }

    @Test
    fun `split libraries route ebook and audiobook progress to their own item ids`() = runTest {
        // Separate ABS items: an ebook item and an audiobook item, matched to the same bundle.
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", lastUpdate = 0L))
        val st = FakeStoryteller(NetworkStorytellerPositionResult.NoPosition)
        val ebookEp = AbsSyncEndpoint("http://abs", "t", false, "ebook-item")
        val audioEp = AbsSyncEndpoint("http://abs", "t", false, "audiobook-item")
        val coordinator = ThreePeerReaderSyncCoordinator(state, bridge, abs, st, ebookEp, audioEp, stEp)

        coordinator.runCycle(locator(0.3), localUpdatedAt = 9_000L)

        // The ebook remote writes ebook progress to the ebook item; the audio remote reads/targets
        // the separate audiobook item (its PATCH is gated on SMIL, separately tested).
        assertEquals("ebook-item", abs.ebookPatchItemId)
        assertTrue("audio remote targets the audiobook item", "audiobook-item" in abs.getProgressItemIds)
        assertTrue("ebook remote targets the ebook item", "ebook-item" in abs.getProgressItemIds)
    }
}
