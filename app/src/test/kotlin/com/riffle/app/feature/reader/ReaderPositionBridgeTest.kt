package com.riffle.app.feature.reader

import com.riffle.core.domain.CanonicalPositionTranslator
import com.riffle.core.domain.ChapterCharMap
import com.riffle.core.domain.ChapterProgression
import com.riffle.core.domain.CrossEpubIndex
import com.riffle.core.domain.MediaOverlayClip
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderPositionBridgeTest {

    // ABS chapter 0 is twice as long (in readable chars) as the Storyteller chapter, so a
    // character offset maps to half the progression on the ABS side. The canonical frame is the
    // ABS EPUB (ADR 0026), so a Storyteller-domain progression scales by storytellerChars/absChars.
    private val index = CrossEpubIndex(listOf(ChapterCharMap(absChars = 200, storytellerChars = 100)))
    private val clips = listOf(MediaOverlayClip("c1.xhtml#f1", "a.mp3", clipBeginSec = 0.0, clipEndSec = 5.0))
    private val fragmentProgressions = mapOf("c1.xhtml#f1" to ChapterProgression(0, 0.25))
    private val translator = CanonicalPositionTranslator(clips, index, fragmentProgressions)

    private val absHtml = "<html><body><p>${"x".repeat(200)}</p></body></html>"

    private val bridge = ReaderPositionBridge(
        absSpineHrefs = listOf("c1.xhtml"),
        absChapterHtml = { if (it == 0) absHtml else null },
        storytellerSpineHrefs = listOf("c1.xhtml"),
        storytellerChapterHtml = { null },
        translator = translator,
    )

    private fun progressionOf(locatorJson: String): Double =
        JSONObject(locatorJson).getJSONObject("locations").getDouble("progression")

    @Test
    fun `audio seconds convert to the canonical position of the narrated fragment`() {
        // Audio 2.0s → narrated fragment at Storyteller progression 0.25 → char 25/100 → ABS
        // canonical 25/200 = 0.125 (canonical is the ABS EPUB).
        val canonical = bridge.audioSecondsToCanonical(2.0)

        assertNotNull(canonical)
        assertEquals(0.125, progressionOf(canonical!!), 1e-9)
    }

    @Test
    fun `a canonical position converts to a Storyteller locator via the cross-EPUB index`() {
        // Canonical (ABS) progression 0.4 → char 80/200 → Storyteller 80/100 = 0.8.
        val canonical = JSONObject()
            .put("href", "c1.xhtml")
            .put("locations", JSONObject().put("progression", 0.4))
            .toString()

        val stLocator = bridge.canonicalToStorytellerLocator(canonical)

        assertNotNull(stLocator)
        assertEquals(0.8, progressionOf(stLocator!!), 1e-9)
    }

    @Test
    fun `a canonical position converts to an ABS CFI and back, preserving the offset`() {
        // Canonical is the ABS EPUB, so this is an ABS-domain round trip (no cross-EPUB scaling).
        val canonical = JSONObject()
            .put("href", "c1.xhtml")
            .put("locations", JSONObject().put("progression", 0.25))
            .toString()

        val cfi = bridge.canonicalToAbsCfi(canonical)
        assertNotNull(cfi)

        val back = bridge.absCfiToCanonical(cfi!!)
        assertNotNull(back)
        assertEquals(0.25, progressionOf(back!!), 0.02)
    }

    @Test
    fun `an unmappable fragment yields no canonical position`() {
        assertNull(bridge.audioSecondsToCanonical(99.0))
    }

    @Test
    fun `book progress uses the locator's totalProgression when present`() {
        val locator = JSONObject()
            .put("href", "c1.xhtml")
            .put("locations", JSONObject().put("progression", 0.4).put("totalProgression", 0.33))
            .toString()

        assertEquals(0.33f, bridge.canonicalBookProgress(locator), 1e-6f)
    }

    @Test
    fun `book progress is computed from chars for a remote-sourced canonical (no totalProgression)`() {
        // A canonical reconstructed from a remote carries only within-chapter progression. With one
        // chapter, book progress equals that progression — and is NOT cleared to 0.
        val remoteCanonical = bridge.audioSecondsToCanonical(2.0)!! // progression 0.125, no totalProgression
        assertEquals(0.125f, bridge.canonicalBookProgress(remoteCanonical), 1e-6f)
    }

    // The two EPUBs are spine-aligned by index but carry different chapter hrefs (ADR 0026): a
    // "Play from here" selection arrives as the rendered ABS href, but the player's clips are keyed
    // by the Storyteller bundle href. This maps one to the other so the right chapter's clip is found.
    private val splitHrefBridge = ReaderPositionBridge(
        absSpineHrefs = listOf("xhtml/chapter1.xhtml", "xhtml/chapter2.xhtml"),
        absChapterHtml = { null },
        storytellerSpineHrefs = listOf("text/part0001.xhtml", "text/part0002.xhtml"),
        storytellerChapterHtml = { null },
        translator = translator,
    )

    @Test
    fun `displayedHrefToBundleHref maps the ABS chapter href to the spine-aligned bundle href`() {
        assertEquals("text/part0002.xhtml", splitHrefBridge.displayedHrefToBundleHref("xhtml/chapter2.xhtml"))
        assertEquals("text/part0001.xhtml", splitHrefBridge.displayedHrefToBundleHref("/xhtml/chapter1.xhtml"))
    }

    @Test
    fun `displayedHrefToBundleHref is null for an href outside the ABS spine`() {
        assertNull(splitHrefBridge.displayedHrefToBundleHref("xhtml/nope.xhtml"))
    }
}
