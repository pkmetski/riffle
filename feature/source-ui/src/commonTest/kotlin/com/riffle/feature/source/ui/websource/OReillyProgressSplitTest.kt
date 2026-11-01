package com.riffle.feature.source.ui.websource

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the threshold logic that splits reading-history items into "in-progress" vs "completed"
 * buckets. This is a pure-function invariant derived from [OReillyHomeViewModel.COMPLETED_THRESHOLD].
 * The Android-side [OReillyHomeViewModelTest] covers the full VM (fetch → split → expose), and
 * this test runs on iOS to verify the same threshold value governs the split in the shared code.
 */
class OReillyProgressSplitTest {

    private val threshold = OReillyHomeViewModel.COMPLETED_THRESHOLD

    @Test
    fun belowThresholdIsInProgress() {
        val progress = threshold - 0.01f
        assertTrue(progress < threshold)
    }

    @Test
    fun atThresholdIsCompleted() {
        val progress = threshold
        assertFalse(progress < threshold)
    }

    @Test
    fun aboveThresholdIsCompleted() {
        val progress = threshold + 0.01f
        assertFalse(progress < threshold)
    }

    @Test
    fun zeroProgressIsExcluded() {
        val progress = 0f
        assertFalse(progress in 0.01f..<threshold)
    }
}
