package com.riffle.app.feature.reader

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PositionSaveCoordinatorTest {

    // Regression guard: updateProgress must never be called from the hot path (onChanged).
    // Every call triggers a Room library_items write → InvalidationTracker fires → all active
    // library Flow observers re-query, causing Compose recompositions at scroll framerate.
    @Test
    fun `onChanged never calls updateProgress regardless of how many times it fires`() = runTest {
        var updateCount = 0
        val coordinator = PositionSaveCoordinator(
            savePosition = {},
            updateProgress = { updateCount++ },
        )

        repeat(100) { coordinator.onChanged("cfi-$it") }

        assertEquals(0, updateCount)
    }

    @Test
    fun `onClose calls updateProgress exactly once`() = runTest {
        var updateCount = 0
        val coordinator = PositionSaveCoordinator(
            savePosition = {},
            updateProgress = { updateCount++ },
        )

        coordinator.onClose("cfi", 0.5f)

        assertEquals(1, updateCount)
    }

    @Test
    fun `onChanged calls savePosition for each change`() = runTest {
        var saveCount = 0
        val coordinator = PositionSaveCoordinator(
            savePosition = { saveCount++ },
            updateProgress = {},
        )

        repeat(5) { coordinator.onChanged("cfi-$it") }

        assertEquals(5, saveCount)
    }

    @Test
    fun `onClose calls savePosition exactly once`() = runTest {
        var saveCount = 0
        val coordinator = PositionSaveCoordinator(
            savePosition = { saveCount++ },
            updateProgress = {},
        )

        coordinator.onClose("cfi", 0.75f)

        assertEquals(1, saveCount)
    }
}
