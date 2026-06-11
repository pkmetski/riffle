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
        val coordinator = PositionSaveCoordinator<String>(
            savePosition = {},
            updateProgress = { updateCount++ },
        )

        repeat(100) { coordinator.onChanged("cfi-$it") }

        assertEquals(0, updateCount)
    }

    @Test
    fun `onClose calls updateProgress exactly once`() = runTest {
        var updateCount = 0
        val coordinator = PositionSaveCoordinator<String>(
            savePosition = {},
            updateProgress = { updateCount++ },
        )

        coordinator.onClose("cfi", 0.5f)

        assertEquals(1, updateCount)
    }

    @Test
    fun `onChanged calls savePosition for each change`() = runTest {
        var saveCount = 0
        val coordinator = PositionSaveCoordinator<String>(
            savePosition = { saveCount++ },
            updateProgress = {},
        )

        repeat(5) { coordinator.onChanged("cfi-$it") }

        assertEquals(5, saveCount)
    }

    @Test
    fun `onClose calls savePosition exactly once`() = runTest {
        var saveCount = 0
        val coordinator = PositionSaveCoordinator<String>(
            savePosition = { saveCount++ },
            updateProgress = {},
        )

        coordinator.onClose("cfi", 0.75f)

        assertEquals(1, saveCount)
    }

    // The audiobook player constructs the coordinator without a savePosition (it resumes from ABS,
    // so there is no local position to store). The cold-path progress write must still fire, and the
    // omitted savePosition must be a safe no-op on both paths.
    @Test
    fun `without savePosition, onClose still updates progress and onChanged is a safe no-op`() = runTest {
        var updateCount = 0
        val coordinator = PositionSaveCoordinator<Double>(
            updateProgress = { updateCount++ },
        )

        repeat(10) { coordinator.onChanged(it.toDouble()) }
        assertEquals(0, updateCount)

        coordinator.onClose(123.0, 0.42f)
        assertEquals(1, updateCount)
    }
}
