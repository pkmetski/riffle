package com.riffle.app.feature.reader

/**
 * Char index into the Pdfium text-page char stream. Indices are page-local;
 * cross-page ranges are not modeled (multi-page selection is out of scope for
 * the PDF parity v1 spec).
 */
@JvmInline
value class CharIndex(val value: Int) : Comparable<CharIndex> {
    override fun compareTo(other: CharIndex): Int = value.compareTo(other.value)
}

/**
 * Page-local char range with stable endpoints. [start] is always <= [endExclusive].
 * Construct via [CharRange.of] which normalizes ordering for arbitrary anchor/head pairs.
 */
data class CharRange(val start: CharIndex, val endExclusive: CharIndex) {
    init {
        require(start <= endExclusive) { "start=$start must be <= endExclusive=$endExclusive" }
    }

    val length: Int get() = endExclusive.value - start.value
    val isEmpty: Boolean get() = length == 0

    companion object {
        fun of(anchor: CharIndex, head: CharIndex): CharRange =
            if (anchor <= head) CharRange(anchor, head)
            else CharRange(head, anchor)
    }
}

/**
 * One end of a live selection — which handle the user is dragging.
 * Used by the gesture machine to know whether the drag updates the anchor
 * or the head while the user repositions one of the two drag handles.
 */
enum class SelectionEndpoint { Anchor, Head }

/**
 * State machine for PDF text selection.
 *
 * Transitions:
 *   Idle
 *     --(longPress(idx))-->                  Selecting(anchor=idx, head=idx+1)
 *     --(tapHighlight(annotation))-->        Idle  (caller opens HighlightActionsSheet
 *                                                  separately; this state only models
 *                                                  selection, not pre-existing annotations)
 *   Selecting(anchor, head)
 *     --(dragHandle(end, idx))-->            Selecting with that endpoint updated
 *     --(dragEnd)-->                         Committed(range)
 *     --(clear)-->                           Idle
 *   Committed(range)
 *     --(outsideTap | clear)-->              Idle
 *     --(dragHandle(end, idx))-->            Selecting again (extending an already-
 *                                                  committed selection)
 *
 * Notes:
 * * The machine does not own char-index translation from screen coordinates.
 *   Callers pass already-resolved char indices; resolution lives in
 *   `PdfTextResolver`.
 * * The machine does not own multi-line rect computation. Callers query
 *   `PdfTextResolver.rectsForRange(...)` to render the selection visuals.
 * * The machine is page-scoped. Crossing into another page sends a `clear`
 *   event from the host (`RifflePdfController`), per the out-of-scope
 *   constraint in the design spec.
 */
sealed interface PdfSelectionState {
    object Idle : PdfSelectionState

    /**
     * The user is actively defining a range — either via the initial long-press
     * (anchor and head start at the same word) or via a drag-handle re-position.
     */
    data class Selecting(
        val anchor: CharIndex,
        val head: CharIndex,
        val activeEndpoint: SelectionEndpoint,
    ) : PdfSelectionState {
        val range: CharRange get() = CharRange.of(anchor, head)
    }

    /** A range was committed (drag ended). The action menu is showing. */
    data class Committed(val range: CharRange) : PdfSelectionState
}
