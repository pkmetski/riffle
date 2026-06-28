package com.riffle.app.feature.reader

/**
 * Pure-Kotlin state machine driving PDF text selection. JVM-testable; knows
 * nothing about Pdfium, Compose, or the View hierarchy. Callers feed it
 * gesture events with already-resolved char indices (resolved by the host
 * via `PdfTextResolver`) and observe [state].
 *
 * Single-threaded — must be invoked from the UI thread.
 */
class PdfSelectionGestureMachine {

    private var current: PdfSelectionState = PdfSelectionState.Idle
    val state: PdfSelectionState get() = current

    /**
     * Long-press resolved to a word range. Starts a fresh selection. If
     * a selection is already in flight or committed, it is discarded
     * (long-press always starts new — matches Android system text selection).
     */
    fun onLongPressSelectedWord(word: CharRange) {
        current = PdfSelectionState.Selecting(
            anchor = word.start,
            head = word.endExclusive,
            activeEndpoint = SelectionEndpoint.Head,
        )
    }

    /**
     * The user grabbed a handle and is dragging. Re-positions the named
     * endpoint to [target]. No-op if not currently Selecting or Committed.
     * If called from Committed, transitions to Selecting (extend).
     */
    fun onHandleDragMove(endpoint: SelectionEndpoint, target: CharIndex) {
        val (anchor, head) = when (val s = current) {
            is PdfSelectionState.Selecting -> s.anchor to s.head
            is PdfSelectionState.Committed -> s.range.start to s.range.endExclusive
            PdfSelectionState.Idle -> return
        }
        current = when (endpoint) {
            SelectionEndpoint.Anchor -> PdfSelectionState.Selecting(target, head, endpoint)
            SelectionEndpoint.Head   -> PdfSelectionState.Selecting(anchor, target, endpoint)
        }
    }

    /**
     * The user released the handle. If a non-empty range is in flight,
     * commit it; otherwise return to Idle.
     */
    fun onHandleDragEnd() {
        current = when (val s = current) {
            is PdfSelectionState.Selecting -> {
                val range = s.range
                if (range.isEmpty) PdfSelectionState.Idle
                else PdfSelectionState.Committed(range)
            }
            else -> current
        }
    }

    /**
     * Any tap outside the selection rects (caller hit-tests). Clears.
     */
    fun onOutsideTap() {
        current = PdfSelectionState.Idle
    }

    /**
     * Programmatic clear — e.g., on page change, navigation away, color-
     * picker confirm, escape key.
     */
    fun clear() {
        current = PdfSelectionState.Idle
    }
}
