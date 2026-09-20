package com.riffle.feature.reader

import com.riffle.core.common.TimeRemaining
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.models.TocEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chapter-map arithmetic both readers run.
 *
 * These live in `commonTest`, so CI executes them on `iosSimulatorArm64` as well as the JVM —
 * which is the point: the iOS reader has no ViewModel of its own and calls [chapterMapUiState]
 * directly, so this suite is the coverage for what iOS's chapter map draws.
 */
class ChapterMapDerivationsTest {

    private val threeEqualSegments = listOf(
        RailSegment("One", "one.xhtml", weight = 10f),
        RailSegment("Two", "two.xhtml", weight = 10f),
        RailSegment("Three", "three.xhtml", weight = 10f),
    )

    @Test
    fun totalRailWeightSumsSegmentWeights() {
        assertEquals(30f, totalRailWeight(threeEqualSegments))
        assertEquals(0f, totalRailWeight(emptyList()))
    }

    /**
     * The cursor is driven by whole-book progression, converted into a within-segment fraction.
     * Half-way through the book with three equal segments is the middle of segment 1, which the
     * weighted layout puts at 0.5 of the rail.
     */
    @Test
    fun cursorConvertsWholeBookProgressionIntoTheActiveSegment() {
        assertEquals(0.5f, railCursorPositionForTotalProgression(1, threeEqualSegments, 0.5f))
        assertEquals(0f, railCursorPositionForTotalProgression(0, threeEqualSegments, 0f))
        assertEquals(1f, railCursorPositionForTotalProgression(2, threeEqualSegments, 1f))
    }

    /**
     * The bug this replaced: using chapter progression made the cursor snap backwards when a new
     * spine resource loaded inside the same segment. With whole-book progression the cursor for a
     * position 2/3 of the way through the book sits at 2/3 of the rail no matter which segment is
     * reported active, because the within-segment fraction clamps rather than restarting.
     */
    @Test
    fun cursorNeverLeavesTheActiveSegmentsBounds() {
        // Whole-book 0.9 but the navigator still reports segment 0 active: the cursor clamps to
        // the end of segment 0 (1/3 of the rail) instead of running off into segment 2.
        assertEquals(1f / 3f, railCursorPositionForTotalProgression(0, threeEqualSegments, 0.9f))
        // …and symmetrically it cannot fall behind the active segment's start.
        assertEquals(2f / 3f, railCursorPositionForTotalProgression(2, threeEqualSegments, 0.1f))
    }

    @Test
    fun cursorIsZeroWithoutAPosition() {
        assertEquals(0f, railCursorPositionForTotalProgression(1, threeEqualSegments, null))
        assertEquals(0f, railCursorPositionForTotalProgression(0, emptyList(), 0.5f))
    }

    /**
     * 30 positions at 60 s each is a 1800 s book. A quarter in, 1350 s is left; the active
     * (middle) chapter ends at 2/3 of the book, so (2/3 − 1/4) × 1800 = 750 s of it remains.
     */
    @Test
    fun timeEstimatesScaleWithTheStoredReadingSpeed() {
        val chapter = estimatedChapterTimeRemaining(threeEqualSegments, 1, 0.25f, 60.0)
        val book = estimatedBookTimeRemaining(threeEqualSegments, 0.25f, 60.0)
        assertEquals(TimeRemaining.Estimated(750), chapter)
        assertEquals(TimeRemaining.Estimated(1350), book)
    }

    @Test
    fun timeEstimatesNeverGoNegativePastTheChapterEnd() {
        // Reading beyond where the active chapter ends must read 0, not a negative countdown.
        assertEquals(TimeRemaining.Estimated(0), estimatedChapterTimeRemaining(threeEqualSegments, 0, 0.9f, 60.0))
        assertEquals(TimeRemaining.Estimated(0), estimatedBookTimeRemaining(threeEqualSegments, 1f, 60.0))
    }

    @Test
    fun timeEstimatesAreAbsentWithoutSegmentsOrAPosition() {
        assertNull(estimatedChapterTimeRemaining(emptyList(), 0, 0.5f, 60.0))
        assertNull(estimatedBookTimeRemaining(emptyList(), 0.5f, 60.0))
        assertNull(estimatedChapterTimeRemaining(threeEqualSegments, 0, null, 60.0))
        assertNull(estimatedBookTimeRemaining(threeEqualSegments, null, 60.0))
    }

    // ---- chapterMapVisible ---------------------------------------------------------------

    @Test
    fun overlayHidesOnlyWhenAllFiveSwitchesAreOff() {
        val allOff = FormattingPreferences(
            showChapterMap = false,
            coloredChapterMap = false,
            showCurrentChapterLabel = false,
            showReadingProgressLabels = false,
            showReadingTimeEstimate = false,
        )
        assertFalse(chapterMapVisible(allOff))
        assertTrue(chapterMapVisible(allOff.copy(showChapterMap = true)))
        assertTrue(chapterMapVisible(allOff.copy(showCurrentChapterLabel = true)))
        assertTrue(chapterMapVisible(allOff.copy(showReadingProgressLabels = true)))
        assertTrue(chapterMapVisible(allOff.copy(showReadingTimeEstimate = true)))
        // coloredChapterMap alone is a sub-setting of the rail and cannot summon the overlay.
        assertFalse(chapterMapVisible(allOff.copy(coloredChapterMap = true)))
    }

    // ---- chapterMapUiState ---------------------------------------------------------------

    private val toc = listOf(
        TocEntry(title = "One", href = "one.xhtml"),
        TocEntry(title = "Two", href = "two.xhtml"),
        TocEntry(title = "Three", href = "three.xhtml"),
    )
    private val spineHrefs = listOf("one.xhtml", "two.xhtml", "three.xhtml")

    @Test
    fun uiStateWeightsSegmentsByChapterLength() {
        // Chapter two is four times the length of its siblings, so it must occupy four times the
        // rail. An unweighted rail would give all three 1f and draw them equal — which is what
        // iOS would render if the spine never reached the generator.
        val state = chapterMapUiState(
            tocEntries = toc,
            bookTitle = "Book",
            spineHrefs = spineHrefs,
            positionCounts = listOf(5, 20, 5),
            currentHref = "two.xhtml",
            chapterProgression = 0f,
            totalProgression = 0.2f,
            speedSecPerPosition = 60.0,
        )
        assertEquals(listOf(5f, 20f, 5f), state.segments.map { it.weight })
        assertEquals(1, state.activeIndex)
    }

    @Test
    fun uiStateStillBuildsBeforeReadiumHasComputedPositions() {
        val state = chapterMapUiState(
            tocEntries = toc,
            bookTitle = "Book",
            spineHrefs = emptyList(),
            positionCounts = emptyList(),
            currentHref = "two.xhtml",
            chapterProgression = 0.5f,
            totalProgression = 0.5f,
            speedSecPerPosition = 60.0,
        )
        assertEquals(3, state.segments.size)
        assertEquals(listOf(1f, 1f, 1f), state.segments.map { it.weight })
        assertEquals(1, state.activeIndex)
    }

    /** The percentage readout prefers Readium's whole-book value and falls back to the cursor. */
    @Test
    fun uiStateLabelProgressPrefersWholeBookProgression() {
        fun stateAt(total: Float?) = chapterMapUiState(
            tocEntries = toc,
            bookTitle = "Book",
            spineHrefs = spineHrefs,
            positionCounts = listOf(10, 10, 10),
            currentHref = "two.xhtml",
            chapterProgression = 0.5f,
            totalProgression = total,
            speedSecPerPosition = 60.0,
        )
        assertEquals(0.5f, stateAt(0.5f).labelProgress)
        val noTotal = stateAt(null)
        assertEquals(noTotal.cursorPosition, noTotal.labelProgress)
    }

    @Test
    fun uiStateCarriesBothTimeEstimates() {
        val state = chapterMapUiState(
            tocEntries = toc,
            bookTitle = "Book",
            spineHrefs = spineHrefs,
            positionCounts = listOf(10, 10, 10),
            currentHref = "two.xhtml",
            chapterProgression = 0f,
            totalProgression = 0.25f,
            speedSecPerPosition = 60.0,
        )
        assertNotNull(state.chapterTimeRemaining)
        assertNotNull(state.bookTimeRemaining)
        assertEquals(TimeRemaining.Estimated(750), state.chapterTimeRemaining)
        assertEquals(TimeRemaining.Estimated(1350), state.bookTimeRemaining)
    }

    @Test
    fun uiStateIsEmptyWithoutATableOfContents() {
        val state = chapterMapUiState(
            tocEntries = emptyList(),
            bookTitle = "Book",
            spineHrefs = spineHrefs,
            positionCounts = listOf(10, 10, 10),
            currentHref = "one.xhtml",
            chapterProgression = 0f,
            totalProgression = 0f,
            speedSecPerPosition = 60.0,
        )
        assertEquals(ChapterMapUiState.Empty, state)
    }
}
