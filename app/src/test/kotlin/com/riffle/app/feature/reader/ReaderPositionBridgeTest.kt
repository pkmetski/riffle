package com.riffle.app.feature.reader

import com.riffle.core.domain.CanonicalPositionTranslator
import com.riffle.core.domain.ChapterCharMap
import com.riffle.core.domain.ChapterProgression
import com.riffle.core.domain.CrossEpubIndex
import com.riffle.core.domain.MediaOverlayClip
import com.riffle.core.domain.OpenedSide
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderPositionBridgeTest {

    // ABS chapter 0 is twice as long (in readable chars) as the Storyteller chapter, so a
    // character offset maps to half the progression on the ABS side.
    private val index = CrossEpubIndex(listOf(ChapterCharMap(absChars = 200, storytellerChars = 100)))
    private val clips = listOf(MediaOverlayClip("c1.xhtml#f1", "a.mp3", clipBeginSec = 0.0, clipEndSec = 5.0))
    private val fragmentProgressions = mapOf("c1.xhtml#f1" to ChapterProgression(0, 0.25))
    private val translator = CanonicalPositionTranslator(clips, index, fragmentProgressions)

    private val absHtml = "<html><body><p>${"x".repeat(200)}</p></body></html>"

    private fun bridge(side: OpenedSide) = ReaderPositionBridge(
        displayedSide = side,
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
        // Readaloud side: canonical is the Storyteller EPUB, so the fragment progression passes through.
        val canonical = bridge(OpenedSide.READALOUD).audioSecondsToCanonical(2.0)

        assertNotNull(canonical)
        assertEquals(0.25, progressionOf(canonical!!), 1e-9)
    }

    @Test
    fun `a canonical Storyteller position round-trips to a Storyteller locator unchanged`() {
        val bridge = bridge(OpenedSide.READALOUD)
        val canonical = JSONObject()
            .put("href", "c1.xhtml")
            .put("locations", JSONObject().put("progression", 0.4))
            .toString()

        val stLocator = bridge.canonicalToStorytellerLocator(canonical)

        assertNotNull(stLocator)
        assertEquals(0.4, progressionOf(stLocator!!), 1e-9)
    }

    @Test
    fun `a Storyteller-side canonical position converts to an ABS CFI and back, preserving the offset`() {
        val bridge = bridge(OpenedSide.READALOUD)
        // Storyteller progression 0.25 → char offset 25 → ABS progression 25/200 = 0.125.
        val canonical = JSONObject()
            .put("href", "c1.xhtml")
            .put("locations", JSONObject().put("progression", 0.25))
            .toString()

        val cfi = bridge.canonicalToAbsCfi(canonical)
        assertNotNull(cfi)

        // Round-tripping the CFI back lands at the same Storyteller-domain progression (±1 char).
        val back = bridge.absCfiToCanonical(cfi!!)
        assertNotNull(back)
        assertEquals(0.25, progressionOf(back!!), 0.02)
    }

    @Test
    fun `an unmappable fragment yields no canonical position`() {
        assertNull(bridge(OpenedSide.READALOUD).audioSecondsToCanonical(99.0))
    }
}
