package com.riffle.core.domain

import kotlin.test.Test
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
}
