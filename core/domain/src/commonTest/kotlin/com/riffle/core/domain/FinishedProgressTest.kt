package com.riffle.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FinishedProgressTest {

    @Test
    fun coverVisitKeepsFinishedBookFinished() {
        // Regression: opening a marked-as-read book and closing it on the cover dropped it to 0%.
        assertTrue(keepsFinishedState(currentProgress = 1.0f, newProgress = 0.0f))
        assertTrue(keepsFinishedState(currentProgress = 1.0f, newProgress = 0.01f))
    }

    @Test
    fun readingPastTheCoverUnfinishesTheBook() {
        // Regression: reading chapter 6 of a marked-as-read book left both progress bars at 100%
        // and the next open started from the beginning instead of resuming.
        assertFalse(keepsFinishedState(currentProgress = 1.0f, newProgress = 0.02f))
        assertFalse(keepsFinishedState(currentProgress = 1.0f, newProgress = 0.4f))
    }

    @Test
    fun unfinishedBookAlwaysTracksProgress() {
        assertFalse(keepsFinishedState(currentProgress = 0.5f, newProgress = 0.0f))
        assertFalse(keepsFinishedState(currentProgress = 0.99f, newProgress = 0.005f))
    }

    @Test
    fun finishedThresholdIsOne() {
        assertTrue(isFinishedReadingProgress(1.0f))
        assertTrue(isFinishedReadingProgress(1.2f))
        assertFalse(isFinishedReadingProgress(0.999f))
    }

    // ── finishAwareEbookProgress ────────────────────────────────────────────────────────────────

    @Test
    fun lastPageOf100PageBookClampsToOne() {
        // Regression: last page of a 100-page book has totalProgression = 99/100 = 0.99, which
        // Riffle pushed to ABS as-is — ABS displayed 99% even though the book was visually done.
        val result = finishAwareEbookProgress(
            totalProgression = 99f / 100f,
            progression = 1f,
            positionCounts = listOf(100),
        )
        assertEquals(1.0f, result)
    }

    @Test
    fun midBookProgressIsUnchanged() {
        val result = finishAwareEbookProgress(
            totalProgression = 0.5f,
            progression = 0.5f,
            positionCounts = listOf(100),
        )
        assertEquals(0.5f, result)
    }

    @Test
    fun lastPageOfMultiSpineBook() {
        // 3-spine book with 10 + 5 + 5 = 20 pages total; last page totalProgression = 19/20 = 0.95
        val result = finishAwareEbookProgress(
            totalProgression = 19f / 20f,
            progression = 0.8f,
            positionCounts = listOf(10, 5, 5),
        )
        assertEquals(1.0f, result)
    }

    @Test
    fun midBookOfMultiSpineBookIsUnchanged() {
        // Same 20-page book, user at page 10 (totalProgression = 0.5)
        val result = finishAwareEbookProgress(
            totalProgression = 0.5f,
            progression = 1f,
            positionCounts = listOf(10, 5, 5),
        )
        assertEquals(0.5f, result)
    }

    @Test
    fun emptyPositionCountsReturnsRaw() {
        // Positions not yet loaded — no clamping should happen
        val result = finishAwareEbookProgress(
            totalProgression = 0.99f,
            progression = 0.99f,
            positionCounts = emptyList(),
        )
        assertEquals(0.99f, result)
    }

    @Test
    fun nullTotalProgressionFallsBackToProgression() {
        // Continuous mode can emit null totalProgression; falls back to within-chapter progression
        val result = finishAwareEbookProgress(
            totalProgression = null,
            progression = 9f / 10f,
            positionCounts = listOf(10),
        )
        assertEquals(1.0f, result)
    }

    @Test
    fun singlePageBookIsAlwaysComplete() {
        // A 1-page book: threshold = 0/1 = 0. Any position (including page-start 0.0) is the last.
        val result = finishAwareEbookProgress(
            totalProgression = 0f,
            progression = 0f,
            positionCounts = listOf(1),
        )
        assertEquals(1.0f, result)
    }

    @Test
    fun exactlyAtLastPageThresholdClampsToOne() {
        // Boundary: totalProgression exactly equals the last-page threshold
        val result = finishAwareEbookProgress(
            totalProgression = 4f / 5f,
            progression = 0f,
            positionCounts = listOf(5),
        )
        assertEquals(1.0f, result)
    }

    @Test
    fun onePageBeforeLastIsNotClamped() {
        // Second-to-last page of a 5-page book: totalProgression = 3/5 = 0.6, threshold = 4/5 = 0.8
        val result = finishAwareEbookProgress(
            totalProgression = 3f / 5f,
            progression = 0.6f,
            positionCounts = listOf(5),
        )
        assertEquals(3f / 5f, result)
    }
}
