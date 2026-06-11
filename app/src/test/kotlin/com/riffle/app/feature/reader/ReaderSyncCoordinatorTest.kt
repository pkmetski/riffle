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
import com.riffle.core.network.NetworkSyncSessionResult
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSyncCoordinatorTest {

    // Identity cross-EPUB index → Storyteller and ABS progressions coincide. The SMIL maps both ways:
    // clip f1 (5–10s) narrates progression 0.2, clip f9 (90–100s) narrates progression 0.9. Single
    // audio file, so absolute == per-file here (the multi-file offsetting is tested in the translator).
    private val translator = CanonicalPositionTranslator(
        smilClips = listOf(
            MediaOverlayClip("f1", "a.mp3", clipBeginSec = 5.0, clipEndSec = 10.0),
            MediaOverlayClip("f9", "a.mp3", clipBeginSec = 90.0, clipEndSec = 100.0),
        ),
        index = CrossEpubIndex(listOf(ChapterCharMap(absChars = 100, storytellerChars = 100))),
        fragmentProgressions = mapOf("f1" to ChapterProgression(0, 0.2), "f9" to ChapterProgression(0, 0.9)),
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

    private fun locator(progression: Double) = JSONObject()
        .put("href", "c1.xhtml")
        .put("locations", JSONObject().put("progression", progression))
        .toString()

    // A minimal in-memory ABS server modelled on the REAL one: a write stores the record stamped with
    // a fresh, monotonically-increasing server `lastUpdate`, but the PATCH response is "OK" with NO
    // timestamp (Success(0)) — exactly as ABS replies. The stored stamp is observable only via a
    // follow-up GET. This is what makes the timestamp-adoption / feedback-loop logic real: code that
    // relies on the PATCH returning a usable stamp will (correctly) fail here.
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
            progress = progress.copy(ebookLocation = payload.ebookLocation, ebookProgress = payload.ebookProgress, lastUpdate = nextStamp())
            return NetworkSyncSessionResult.Success(0L) // ABS replies "OK" — no timestamp in the body
        }
        override suspend fun syncAudiobookProgress(baseUrl: String, libraryItemId: String, payload: NetworkAudiobookProgressPayload, token: String, insecureAllowed: Boolean): NetworkSyncSessionResult {
            audioPatch = payload; audioPatchItemId = libraryItemId
            progress = progress.copy(currentTime = payload.currentTime, duration = payload.duration, lastUpdate = nextStamp())
            return NetworkSyncSessionResult.Success(0L) // ABS replies "OK" — no timestamp in the body
        }
        override suspend fun getProgress(baseUrl: String, libraryItemId: String, token: String, insecureAllowed: Boolean): NetworkGetProgressResult {
            getProgressItemIds += libraryItemId; return NetworkGetProgressResult.Success(progress)
        }
    }

    private fun coordinator(abs: FakeAbs, ebookEp: AbsSyncEndpoint = absEp, audioEp: AbsSyncEndpoint? = absEp) =
        ReaderSyncCoordinator(state, bridge, abs, ebookEp, audioEp)

    // ── reading-only / cross-device ebook ────────────────────────────────────────────

    @Test
    fun `a newer audiobook position wins the cycle and the reader jumps to it`() = runTest {
        // The two ABS peers reconcile (no Storyteller). A fresher audiobook position (95s → 0.9) wins
        // over an older local reading position and jumps the reader (ADR 0029).
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", currentTime = 95.0, duration = 100.0, lastUpdate = 5_000L))

        val result = coordinator(abs).runCycle(locator(0.2), localUpdatedAt = 1_000L)

        assertNotNull(result.jumpLocatorJson)
        assertEquals(0.9, JSONObject(result.jumpLocatorJson!!).getJSONObject("locations").getDouble("progression"), 1e-9)
        assertTrue(result.canonicalLastUpdate >= 5_000L)
    }

    @Test
    fun `when local is newest there is no jump and every ABS peer gets the reading position`() = runTest {
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", lastUpdate = 0L))

        val result = coordinator(abs).runCycle(locator(0.3), localUpdatedAt = 9_000L)

        assertNull(result.jumpLocatorJson)
        assertNotNull("ABS ebook received the first write", abs.ebookPatch)
        assertTrue(abs.ebookPatch!!.ebookLocation.startsWith("epubcfi("))
        // Reading advances the audiobook too: the page (0.3) translates through the bundle SMIL to the
        // fragment narrated at 0.2 → its absolute clip start (5s). The raw audio clock is never used.
        assertNotNull("the cycle writes the audiobook from the page", abs.audioPatch)
        assertEquals(5.0, abs.audioPatch!!.currentTime, 1e-9)
    }

    // ── inbound audiobook (the feature) ─────────────────────────────────────────────

    @Test
    fun `a genuinely newer audiobook listened elsewhere wins and jumps the reader to the bundle page`() = runTest {
        // Another device left the audiobook at 95s (→ progression 0.9) with a fresh timestamp; the
        // local reading position is at the start and older. Inbound audiobook must win and move the
        // reader to the bundle-translated page — "server updates reflected locally".
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", currentTime = 95.0, duration = 100.0, lastUpdate = 9_999L))

        val result = coordinator(abs).runCycle(locator(0.0), localUpdatedAt = 1_000L)

        assertNotNull("a newer audiobook must move the reader", result.jumpLocatorJson)
        assertEquals(0.9, JSONObject(result.jumpLocatorJson!!).getJSONObject("locations").getDouble("progression"), 1e-9)
        // The win propagates to the ebook, whose write the server stamps even later; the cycle adopts
        // that stamp so the propagated ebook doesn't bounce back next cycle.
        assertTrue("the winning timestamp is at least the audiobook's", result.canonicalLastUpdate >= 9_999L)
        assertNotNull("the ebook is moved to the listened position", abs.ebookPatch)
        // The ebook progress is computed from the chapter char weights (0.9 here), NOT cleared to 0 —
        // a remote-sourced position has no totalProgression on it.
        assertEquals("ebook progress must not be cleared", 0.9, abs.ebookPatch!!.ebookProgress.toDouble(), 1e-6)
    }

    @Test
    fun `our own audiobook push does not read back as a newer remote (feedback loop closed)`() = runTest {
        // The exact erase mechanism: we push the audiobook (here for a near-end reading position), then
        // a cycle runs. Without the timestamp-recording fix the audiobook reads back newer than the
        // page and drives the ebook. With it, the caller records the push's server timestamp as
        // localUpdatedAt, so the read comes back EQUAL and the reading position wins.
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", lastUpdate = 1_000L))
        val coordinator = coordinator(abs)

        val pushStamp = coordinator.pushAudiobookProgress(locator(0.9)) // → audio 90s
        assertNotNull(pushStamp)
        // The caller (the ViewModel) records pushStamp as the local timestamp; simulate that here.
        val result = coordinator.runCycle(locator(0.0), localUpdatedAt = pushStamp!!)

        assertNull("our own push must not jump the reader", result.jumpLocatorJson)
        // The ebook keeps the reading position (start), never the audio's near-end.
        assertEquals("ebook stays at the reading position", 0.0, abs.ebookPatch?.ebookProgress?.toDouble() ?: 0.0, 1e-9)
    }

    @Test
    fun `a page written outbound must not read back as a newer server position next cycle`() = runTest {
        // The "server always overwrites local" bug, ebook-specific: ABS stamps our ebook write with a
        // server time LATER than the local page-turn time. If we don't adopt that timestamp, the next
        // cycle reads our own write back as "newer" and bounces the reader to the position it just
        // wrote. The cycle must fold the server's returned write timestamp into canonicalLastUpdate.
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", lastUpdate = 0L)) // server stamps writes 1000, 2000, …
        val coordinator = coordinator(abs)

        // Cycle 1: local (ts 500) is newer than the empty server, so the page is written out; ABS
        // stamps that write 1000 (> 500).
        val r1 = coordinator.runCycle(locator(0.3), localUpdatedAt = 500L)
        assertNull(r1.jumpLocatorJson)

        // The ViewModel records the cycle's returned timestamp as the new local timestamp.
        val newLocal = maxOf(500L, r1.canonicalLastUpdate)
        // Cycle 2: same reading position. Our own just-written ebook must NOT win and jump.
        val r2 = coordinator.runCycle(locator(0.3), localUpdatedAt = newLocal)
        assertNull("the just-written ebook must not read back as newer and bounce the reader", r2.jumpLocatorJson)
    }

    // ── responsive audiobook-follow push (from the reading position) ─────────────────

    @Test
    fun `pushAudiobookProgress writes only the audiobook item from the page and returns the server timestamp`() = runTest {
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", lastUpdate = 0L))
        val ebookEp = AbsSyncEndpoint("http://abs", "t", false, "ebook-item")
        val audioEp = AbsSyncEndpoint("http://abs", "t", false, "audiobook-item", durationSec = 39214.0)
        val coordinator = coordinator(abs, ebookEp, audioEp)

        val stamp = coordinator.pushAudiobookProgress(locator(0.9)) // page 0.9 → fragment f9 → 90s

        assertNotNull("a successful push returns the server timestamp", stamp)
        assertEquals(90.0, abs.audioPatch!!.currentTime, 1e-9)
        assertEquals(39214.0, abs.audioPatch!!.duration, 1e-9)
        assertEquals("audiobook-item", abs.audioPatchItemId)
        // Derived from the page, never the raw audio clock; the ebook is never touched.
        assertNull(abs.ebookPatch)
        assertNull(abs.ebookPatchItemId)
    }

    @Test
    fun `pushAudiobookProgress is a no-op when there is no matched audiobook`() = runTest {
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", lastUpdate = 0L))
        val ebookEp = AbsSyncEndpoint("http://abs", "t", false, "ebook-item")
        val coordinator = coordinator(abs, ebookEp, audioEp = null)

        assertNull(coordinator.pushAudiobookProgress(locator(0.9)))
        assertNull(abs.audioPatch)
    }

    // ── audio-led cycle (the audiobook player drives it; ADR 0029) ───────────────────

    @Test
    fun `audio-led — a newer position elsewhere wins and the player seeks to its audio second`() = runTest {
        // Another device left the audiobook at 95s (→ progression 0.9) with a fresh timestamp; the
        // local listen is near the start (5s) and stale. The audio-led cycle must surface
        // jumpToAudioSec so the player seeks there — 0.9 maps back through the SMIL to the f9 clip (90s).
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", currentTime = 95.0, duration = 100.0, lastUpdate = 9_999L))
        val coordinator = coordinator(abs)

        val r = coordinator.runAudioLedCycle(currentAudioSec = 5.0, localUpdatedAt = 1_000L)

        assertNotNull("a newer remote must seek the player", r.jumpToAudioSec)
        assertEquals(90.0, r.jumpToAudioSec!!, 1e-9)
        assertTrue(r.canonicalLastUpdate >= 9_999L)
    }

    @Test
    fun `audio-led — when the listen is newest there is no seek and both ABS peers get the listen position`() = runTest {
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", lastUpdate = 0L))
        val coordinator = coordinator(abs)

        // Listening at 90s (→ fragment f9 → progression 0.9), local is the freshest.
        val r = coordinator.runAudioLedCycle(currentAudioSec = 90.0, localUpdatedAt = 9_000L)

        assertNull("our own listen must not seek the player", r.jumpToAudioSec)
        assertNotNull("the ebook is advanced from the listen position", abs.ebookPatch)
        assertTrue(abs.ebookPatch!!.ebookLocation.startsWith("epubcfi("))
        assertNotNull("the audiobook is written from the listen position", abs.audioPatch)
        assertEquals(90.0, abs.audioPatch!!.currentTime, 1e-9)
    }

    @Test
    fun `audio-led — our own write does not read back as newer and re-seek next cycle (feedback closed)`() = runTest {
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", lastUpdate = 0L))
        val coordinator = coordinator(abs)

        val r1 = coordinator.runAudioLedCycle(currentAudioSec = 90.0, localUpdatedAt = 500L)
        assertNull(r1.jumpToAudioSec)
        // The ViewModel adopts the returned timestamp; our just-written position must not bounce back.
        val r2 = coordinator.runAudioLedCycle(currentAudioSec = 90.0, localUpdatedAt = maxOf(500L, r1.canonicalLastUpdate))
        assertNull("our own just-written position must not re-seek the player", r2.jumpToAudioSec)
    }

    // ── split libraries (distinct ebook / audiobook items) ──────────────────────────

    @Test
    fun `split libraries route ebook progress and the push to their own item ids`() = runTest {
        val abs = FakeAbs(NetworkServerProgress(ebookLocation = "", lastUpdate = 0L))
        val ebookEp = AbsSyncEndpoint("http://abs", "t", false, "ebook-item")
        val audioEp = AbsSyncEndpoint("http://abs", "t", false, "audiobook-item", durationSec = 100.0)
        val coordinator = coordinator(abs, ebookEp, audioEp)

        coordinator.runCycle(locator(0.3), localUpdatedAt = 9_000L)
        coordinator.pushAudiobookProgress(locator(0.3))

        assertEquals("ebook progress goes to the ebook item", "ebook-item", abs.ebookPatchItemId)
        assertTrue("the cycle reads the ebook item", "ebook-item" in abs.getProgressItemIds)
        assertTrue("the cycle reconciles the audiobook item", "audiobook-item" in abs.getProgressItemIds)
        assertEquals("audiobook writes target the audiobook item", "audiobook-item", abs.audioPatchItemId)
    }
}
