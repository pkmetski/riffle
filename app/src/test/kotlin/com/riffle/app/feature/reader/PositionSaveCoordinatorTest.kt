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

        coordinator.onClose(0.5f)

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
    fun `onClose does NOT call savePosition — position was already written by onChanged (#528)`() = runTest {
        // Prior contract also saved the position on close, which regressed cross-device sync:
        // if the ServerLocator UI-jump hadn't landed yet, onClose saved the reader's stale
        // in-memory locator over the fresh server-adopted value in the store, and the next
        // sync-cycle pushed the stale locator back. onChanged now covers all position writes;
        // onClose only updates the readingProgress display value.
        var saveCount = 0
        val coordinator = PositionSaveCoordinator<String>(
            savePosition = { saveCount++ },
            updateProgress = {},
        )

        coordinator.onClose(0.75f)

        assertEquals(0, saveCount)
    }

    // The audiobook player constructs the coordinator without a savePosition. The cold-path
    // progress write must still fire, and the omitted savePosition must be a safe no-op.
    @Test
    fun `without savePosition, onClose still updates progress and onChanged is a safe no-op`() = runTest {
        var updateCount = 0
        val coordinator = PositionSaveCoordinator<Double>(
            updateProgress = { updateCount++ },
        )

        repeat(10) { coordinator.onChanged(it.toDouble()) }
        assertEquals(0, updateCount)

        coordinator.onClose(0.42f)
        assertEquals(1, updateCount)
    }
}
