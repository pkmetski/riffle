package com.riffle.app.feature.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionHandoffLatchTest {

    // Plain dismiss (no Highlight tap): destroy must clear selection immediately, otherwise the
    // pause would leak past the ActionMode dismissal.
    @Test
    fun `no handoff — destroy clears synchronously`() {
        val latch = SelectionHandoffLatch()
        assertTrue(latch.shouldClearOnDestroy())
    }

    // Regression: without this deferral, mode.finish() in onActionItemClicked(Highlight) fires
    // onDestroyActionMode synchronously, which would flip selection-active to false BEFORE the
    // highlight-creation coroutine reaches `currentOnHighlight` (which sets `highlightToEdit`).
    // Auto-Scroll would resume for one or more frames until `highlightToEdit != null` re-latches
    // the pause. The latch defers the clear to the coroutine's `finally`.
    @Test
    fun `highlight handoff — destroy defers clear`() {
        val latch = SelectionHandoffLatch()
        latch.beginHighlightHandoff()
        assertFalse(
            "onDestroyActionMode must NOT clear selection-active while a highlight coroutine is in flight",
            latch.shouldClearOnDestroy(),
        )
        // Coroutine finally runs → latch is clear. A subsequent (unrelated) destroy clears again.
        latch.endHighlightHandoff()
        assertTrue(latch.shouldClearOnDestroy())
    }

    // Coroutine finally fires BEFORE onDestroyActionMode (fast-return path where currentSelection
    // is null): destroy still needs to clear, since the handoff already ended.
    @Test
    fun `handoff ended before destroy — destroy still clears`() {
        val latch = SelectionHandoffLatch()
        latch.beginHighlightHandoff()
        latch.endHighlightHandoff()
        assertTrue(latch.shouldClearOnDestroy())
    }

    // The same ActionMode.Callback instance is reused across successive selections. After a
    // deferred destroy, the next onCreateActionMode → reset() must restore the "no handoff"
    // baseline so a subsequent plain dismiss clears synchronously.
    @Test
    fun `reset from onCreateActionMode restores fresh state`() {
        val latch = SelectionHandoffLatch()
        latch.beginHighlightHandoff()
        // Callback instance reused for the next selection before the coroutine finished.
        latch.reset()
        assertTrue(latch.shouldClearOnDestroy())
    }
}
