package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfSelectionGestureMachineTest {

    private val machine = PdfSelectionGestureMachine()

    private fun ci(i: Int) = CharIndex(i)
    private fun range(start: Int, endExclusive: Int) =
        CharRange(ci(start), ci(endExclusive))

    @Test
    fun `starts idle`() {
        assertSame(PdfSelectionState.Idle, machine.state)
    }

    @Test
    fun `long-press on a word transitions to Selecting with that word's range`() {
        machine.onLongPressSelectedWord(range(10, 15))
        val s = machine.state as PdfSelectionState.Selecting
        assertEquals(ci(10), s.anchor)
        assertEquals(ci(15), s.head)
        assertEquals(SelectionEndpoint.Head, s.activeEndpoint)
        assertEquals(range(10, 15), s.range)
    }

    @Test
    fun `dragging the head endpoint forward extends the selection`() {
        machine.onLongPressSelectedWord(range(10, 15))
        machine.onHandleDragMove(SelectionEndpoint.Head, ci(20))
        val s = machine.state as PdfSelectionState.Selecting
        assertEquals(range(10, 20), s.range)
    }

    @Test
    fun `dragging the anchor backward extends the selection`() {
        machine.onLongPressSelectedWord(range(10, 15))
        machine.onHandleDragMove(SelectionEndpoint.Anchor, ci(5))
        val s = machine.state as PdfSelectionState.Selecting
        assertEquals(range(5, 15), s.range)
    }

    @Test
    fun `dragging the anchor past the head flips the range orientation`() {
        machine.onLongPressSelectedWord(range(10, 15))
        machine.onHandleDragMove(SelectionEndpoint.Anchor, ci(20))
        val s = machine.state as PdfSelectionState.Selecting
        // anchor=20, head=15 -> CharRange.of normalizes to (15, 20)
        assertEquals(range(15, 20), s.range)
    }

    @Test
    fun `drag-end with a non-empty range commits`() {
        machine.onLongPressSelectedWord(range(10, 15))
        machine.onHandleDragMove(SelectionEndpoint.Head, ci(25))
        machine.onHandleDragEnd()
        val s = machine.state as PdfSelectionState.Committed
        assertEquals(range(10, 25), s.range)
    }

    @Test
    fun `drag-end with an empty range returns to Idle`() {
        machine.onLongPressSelectedWord(range(10, 10))
        machine.onHandleDragEnd()
        assertSame(PdfSelectionState.Idle, machine.state)
    }

    @Test
    fun `outside tap from Committed clears`() {
        machine.onLongPressSelectedWord(range(10, 15))
        machine.onHandleDragEnd()
        assertTrue(machine.state is PdfSelectionState.Committed)
        machine.onOutsideTap()
        assertSame(PdfSelectionState.Idle, machine.state)
    }

    @Test
    fun `dragging a handle from Committed re-enters Selecting (extend)`() {
        machine.onLongPressSelectedWord(range(10, 15))
        machine.onHandleDragEnd()
        machine.onHandleDragMove(SelectionEndpoint.Head, ci(25))
        val s = machine.state as PdfSelectionState.Selecting
        assertEquals(range(10, 25), s.range)
        assertEquals(SelectionEndpoint.Head, s.activeEndpoint)
    }

    @Test
    fun `clear from any state returns to Idle`() {
        machine.onLongPressSelectedWord(range(10, 15))
        machine.clear()
        assertSame(PdfSelectionState.Idle, machine.state)

        machine.onLongPressSelectedWord(range(20, 30))
        machine.onHandleDragEnd()
        machine.clear()
        assertSame(PdfSelectionState.Idle, machine.state)
    }

    @Test
    fun `drag from Idle is a no-op`() {
        machine.onHandleDragMove(SelectionEndpoint.Head, ci(50))
        assertSame(PdfSelectionState.Idle, machine.state)
    }

    @Test
    fun `outside tap from Idle is a no-op`() {
        machine.onOutsideTap()
        assertSame(PdfSelectionState.Idle, machine.state)
    }

    @Test
    fun `drag-end from Idle is a no-op`() {
        machine.onHandleDragEnd()
        assertSame(PdfSelectionState.Idle, machine.state)
    }

    @Test
    fun `long-press while Committed restarts the selection`() {
        machine.onLongPressSelectedWord(range(10, 15))
        machine.onHandleDragEnd()
        machine.onLongPressSelectedWord(range(50, 55))
        val s = machine.state as PdfSelectionState.Selecting
        assertEquals(range(50, 55), s.range)
    }
}
