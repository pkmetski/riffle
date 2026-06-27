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

    // ── JSON structure ───────────────────────────────────────────────────────

    @Test
    fun `json carries href`() {
        val json = buildContinuousLocatorJson("Text/ch1.xhtml", 0.5f, emptyList(), emptyList())
        assertEquals("Text/ch1.xhtml", json.getString("href"))
    }

    @Test
    fun `json always carries within-resource progression`() {
        val json = buildContinuousLocatorJson("ch1.xhtml", 0.75f, emptyList(), emptyList())
        assertEquals(0.75, json.getJSONObject("locations").getDouble("progression"), 0.001)
    }

    // ── totalProgression: drives both chapter map and time estimates ─────────

    @Test
    fun `totalProgression present when href is in spine — chapter map and time estimates update`() {
        val spineHrefs = listOf("ch1.xhtml", "ch2.xhtml")
        val counts = listOf(100, 100)
        val json = buildContinuousLocatorJson("ch2.xhtml", 0f, spineHrefs, counts)
        val locations = json.getJSONObject("locations")
        assertTrue(
            "totalProgression must be present so railCursorPosition and time estimates update",
            locations.has("totalProgression"),
        )
        // ch2 at 0 % = 50 % through book (two equal-weight resources)
        assertEquals(0.5, locations.getDouble("totalProgression"), 0.001)
    }

    @Test
    fun `totalProgression absent when position counts not yet computed`() {
        // Readium hasn't computed position counts yet — counts arrive later via StateFlow.
        // The JSON is still valid for href/progression; totalProgression arrives on the next
        // position update once counts populate.
        val json = buildContinuousLocatorJson("ch1.xhtml", 0.5f, listOf("ch1.xhtml"), emptyList())
        assertFalse(
            "totalProgression must be absent when position counts are empty",
            json.getJSONObject("locations").has("totalProgression"),
        )
    }

    @Test
    fun `totalProgression absent when href is not in spine`() {
        val json = buildContinuousLocatorJson(
            "front-matter.xhtml", 0.5f, listOf("ch1.xhtml"), listOf(100),
        )
        assertFalse(json.getJSONObject("locations").has("totalProgression"))
    }

    @Test
    fun `fragment hrefs match on the base path`() {
        // ContinuousReaderView reports the spine resource without fragment, but URL inputs may
        // carry one; both sides strip to the base path before matching.
        val spineHrefs = listOf("ch1.xhtml", "ch2.xhtml")
        val counts = listOf(100, 300)
        val json = buildContinuousLocatorJson("ch2.xhtml#start", 0f, spineHrefs, counts)
        val locations = json.getJSONObject("locations")
        assertTrue("fragment-bearing href must match on base path", locations.has("totalProgression"))
        assertEquals(0.25, locations.getDouble("totalProgression"), 0.001)
    }

    @Test
    fun `totalProgression at book start is 0`() {
        val spineHrefs = listOf("ch1.xhtml", "ch2.xhtml", "ch3.xhtml")
        val counts = listOf(10, 30, 60)
        val json = buildContinuousLocatorJson("ch1.xhtml", 0f, spineHrefs, counts)
        assertEquals(0.0, json.getJSONObject("locations").getDouble("totalProgression"), 0.001)
    }

    @Test
    fun `totalProgression at book end is 1`() {
        val spineHrefs = listOf("ch1.xhtml", "ch2.xhtml", "ch3.xhtml")
        val counts = listOf(10, 30, 60)
        val json = buildContinuousLocatorJson("ch3.xhtml", 1f, spineHrefs, counts)
        assertEquals(1.0, json.getJSONObject("locations").getDouble("totalProgression"), 0.001)
    }

    // ── Multi-resource rail segments (the bug this fix closes) ───────────────
    //
    // When a TOC entry is the sole entry for its file and the next TOC entry lives in a later
    // file, `weightSegmentsByChapterLength` collapses the run into one rail segment whose weight
    // covers ALL the spine resources between them (e.g. "Chapter 20" → SOL 376, SOL 380, SOL 384
    // in The Martian). The continuous reader still reports `progression` per-RESOURCE. The fix
    // is to compute totalProgression from the spine's position counts, not the segment weights,
    // so each resource only contributes its own share.

    @Test
    fun `multi-resource chapter — first resource advances proportionally, not segment-wide`() {
        // Three equal-size resources comprise "Chapter 20"; spine has nothing else.
        val spineHrefs = listOf("ch20-a.xhtml", "ch20-b.xhtml", "ch20-c.xhtml")
        val counts = listOf(100, 100, 100)

        // Halfway through the FIRST resource of the 3-resource chapter is 1/6 of the book,
        // NOT halfway through the chapter (= 1/2 of the book) — the bug before this fix.
        val totalProg = buildContinuousLocatorJson("ch20-a.xhtml", 0.5f, spineHrefs, counts)
            .getJSONObject("locations").getDouble("totalProgression").toFloat()
        assertEquals(50f / 300f, totalProg, 0.001f)
    }

    @Test
    fun `multi-resource chapter — middle resource progresses (does not jam)`() {
        // The pre-fix code would return null here (href didn't match any rail segment), freezing
        // the chapter map and time estimates for the entire middle resource of a chapter run.
        val spineHrefs = listOf("ch20-a.xhtml", "ch20-b.xhtml", "ch20-c.xhtml")
        val counts = listOf(100, 100, 100)
        val json = buildContinuousLocatorJson("ch20-b.xhtml", 0.5f, spineHrefs, counts)
        val locations = json.getJSONObject("locations")
        assertTrue(
            "middle resource of a multi-resource chapter must still produce totalProgression",
            locations.has("totalProgression"),
        )
        // Halfway through the 2nd of 3 equal resources = 150/300.
        assertEquals(150.0 / 300.0, locations.getDouble("totalProgression"), 0.001)
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
        val spineHrefs = listOf("a.xhtml", "b.xhtml", "c.xhtml")
        val counts = listOf(10, 30, 60)
        val segments = listOf(
            RailSegment("A", "a.xhtml", 10f),
            RailSegment("B", "b.xhtml", 30f),
            RailSegment("C", "c.xhtml", 60f),
        )
        val totalProg = buildContinuousLocatorJson("b.xhtml", 0.5f, spineHrefs, counts)
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
    fun `chapter-map cursor is 0 when position counts unavailable (initial state, not a freeze)`() {
        // No counts → no totalProgression → railCursorPosition returns 0f by ViewModel contract.
        val json = buildContinuousLocatorJson("ch1.xhtml", 0.9f, emptyList(), emptyList())
        assertFalse(
            "no totalProgression means railCursorPosition resets to 0 — expected initial state",
            json.getJSONObject("locations").has("totalProgression"),
        )
    }
}
