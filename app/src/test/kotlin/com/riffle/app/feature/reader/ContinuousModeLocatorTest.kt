package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the continuous-mode position-update chain that drives the chapter-map rail cursor
 * and reading-time estimates.
 *
 * Paginated and vertical modes receive a fully-populated Locator (including totalProgression)
 * from Readium automatically. Continuous mode must construct the equivalent JSON via
 * buildContinuousLocatorJson. If totalProgression is absent, EpubReaderViewModel's
 * _currentLocatorTotalProgression never updates, leaving the chapter map frozen at 0 and
 * reading-time estimates blank — the regression this file guards.
 *
 * buildContinuousLocator (the Locator-parsing wrapper) is not tested here because Locator.fromJSON
 * calls android.net.Uri which cannot be mocked in JVM tests; only the JSON it receives is ours.
 */
class ContinuousModeLocatorTest {

    private fun seg(href: String, weight: Float) = RailSegment(title = href, href = href, weight = weight)

    // ── JSON structure ───────────────────────────────────────────────────────

    @Test
    fun `json carries href`() {
        val json = buildContinuousLocatorJson("Text/ch1.xhtml", 0.5f, emptyList())
        assertEquals("Text/ch1.xhtml", json.getString("href"))
    }

    @Test
    fun `json always carries within-chapter progression`() {
        val json = buildContinuousLocatorJson("ch1.xhtml", 0.75f, emptyList())
        assertEquals(0.75, json.getJSONObject("locations").getDouble("progression"), 0.001)
    }

    // ── totalProgression: drives both chapter map and time estimates ─────────

    @Test
    fun `totalProgression present when segments match — chapter map and time estimates update`() {
        val segments = listOf(seg("ch1.xhtml", 1f), seg("ch2.xhtml", 1f))
        val json = buildContinuousLocatorJson("ch2.xhtml", 0f, segments)
        val locations = json.getJSONObject("locations")
        assertTrue("totalProgression must be present so railCursorPosition and time estimates update",
            locations.has("totalProgression"))
        // ch2 at 0 % = 50 % through book (two equal-weight chapters)
        assertEquals(0.5, locations.getDouble("totalProgression"), 0.001)
    }

    @Test
    fun `totalProgression absent when segments are empty (positions not yet computed)`() {
        // Readium hasn't computed position counts yet — segments arrive later via StateFlow.
        // The JSON is still valid for href/progression; totalProgression arrives on the next
        // position update once segments populate.
        val json = buildContinuousLocatorJson("ch1.xhtml", 0.5f, emptyList())
        assertFalse("totalProgression must be absent when segments are empty",
            json.getJSONObject("locations").has("totalProgression"))
    }

    @Test
    fun `totalProgression absent when href matches no segment`() {
        val segments = listOf(seg("ch1.xhtml", 1f))
        val json = buildContinuousLocatorJson("front-matter.xhtml", 0.5f, segments)
        assertFalse(json.getJSONObject("locations").has("totalProgression"))
    }

    @Test
    fun `TOC fragment href in segment matches bare spine href from continuous reader`() {
        // TOC-derived segments often store hrefs with #fragment (e.g. "ch2.xhtml#section1"),
        // while ContinuousReaderView reports the spine resource without fragment.
        // This was the original root cause: exact matching always returned null.
        val segments = listOf(
            RailSegment(title = "ch1", href = "ch1.xhtml#intro", weight = 1f),
            RailSegment(title = "ch2", href = "ch2.xhtml#start", weight = 3f),
        )
        val json = buildContinuousLocatorJson("ch2.xhtml", 0f, segments)
        val locations = json.getJSONObject("locations")
        assertTrue("fragment-bearing segment must match bare spine href",
            locations.has("totalProgression"))
        // ch2 starts at cumulativeWeight(1) / total(4) = 0.25
        assertEquals(0.25, locations.getDouble("totalProgression"), 0.001)
    }

    @Test
    fun `totalProgression at book start is 0`() {
        val segments = listOf(seg("ch1.xhtml", 10f), seg("ch2.xhtml", 30f), seg("ch3.xhtml", 60f))
        val json = buildContinuousLocatorJson("ch1.xhtml", 0f, segments)
        assertEquals(0.0, json.getJSONObject("locations").getDouble("totalProgression"), 0.001)
    }

    @Test
    fun `totalProgression at book end is 1`() {
        val segments = listOf(seg("ch1.xhtml", 10f), seg("ch2.xhtml", 30f), seg("ch3.xhtml", 60f))
        val json = buildContinuousLocatorJson("ch3.xhtml", 1f, segments)
        assertEquals(1.0, json.getJSONObject("locations").getDouble("totalProgression"), 0.001)
    }

    // ── Chapter-map cursor position driven by totalProgression ───────────────
    //
    // EpubReaderViewModel.railCursorPosition combines activeRailSegmentIndex, railSegments, and
    // currentLocatorTotalProgression. The ViewModel can't be constructed in JVM tests (Readium
    // needs android.net.Uri), so we verify the math chain:
    //   buildContinuousLocatorJson → totalProgression → weightedRailCursorPosition
    //
    // weightedRailCursorPosition is already covered by RailSegmentGeneratorTest. These tests
    // verify that continuous-mode positions produce the totalProgression that lands the cursor
    // inside the correct segment's bounds.

    @Test
    fun `chapter-map cursor lands inside the active segment when reader is in continuous mode`() {
        val segments = listOf(
            RailSegment("A", "a.xhtml", 10f),
            RailSegment("B", "b.xhtml", 30f),
            RailSegment("C", "c.xhtml", 60f),
        )
        val totalProg = buildContinuousLocatorJson("b.xhtml", 0.5f, segments)
            .getJSONObject("locations").getDouble("totalProgression").toFloat()

        // totalProg = (10 + 30*0.5) / 100 = 25/100 = 0.25
        assertEquals(0.25f, totalProg, 0.001f)

        // Compute the rail cursor the same way EpubReaderViewModel.railCursorPosition does.
        val totalWeight = 100f
        val weightBefore = 10f   // weight of A
        val segWeight = 30f      // weight of B
        val withinSeg = ((totalProg * totalWeight - weightBefore) / segWeight).coerceIn(0f, 1f)
        val cursor = weightedRailCursorPosition(1, segments, withinSeg)

        // B covers [10/100, 40/100] = [0.1, 0.4]. At 50 % through B the cursor sits at 0.25.
        assertEquals(0.25f, cursor, 0.001f)
    }

    @Test
    fun `chapter-map cursor is 0 when no segments available (initial state, not a freeze)`() {
        // No segments → no totalProgression → railCursorPosition returns 0f by ViewModel contract.
        val json = buildContinuousLocatorJson("ch1.xhtml", 0.9f, emptyList())
        assertFalse("no totalProgression means railCursorPosition resets to 0 — expected initial state",
            json.getJSONObject("locations").has("totalProgression"))
    }
}
