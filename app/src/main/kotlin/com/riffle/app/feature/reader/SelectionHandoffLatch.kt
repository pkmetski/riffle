package com.riffle.app.feature.reader

/**
 * Two-state latch that bridges the frame-scale gap between the Readium selection ActionMode's
 * synchronous `onDestroyActionMode` and the asynchronous "highlight-actions popup opens" event
 * driven by [com.riffle.app.feature.reader.session.AnnotationSession].
 *
 * When the user taps the "Highlight" item, `onActionItemClicked` launches a coroutine that
 * eventually calls the highlight-creation flow (a Room write + StateFlow update), then finishes
 * the ActionMode. `mode.finish()` synchronously fires `onDestroyActionMode`, which would clear
 * the reader's `selectionActive` signal before the popup has had a chance to latch the pause via
 * `highlightToEdit != null`. Auto-Scroll would then resume for one or more frames while the popup
 * is materialising — the exact state the pause was introduced to prevent.
 *
 * Usage: call [beginHighlightHandoff] from the highlight branch of `onActionItemClicked`, call
 * [shouldClearOnDestroy] from `onDestroyActionMode`, and call [endHighlightHandoff] from the
 * coroutine's `finally`. Call [reset] from `onCreateActionMode` to be safe against a callback
 * instance being reused across successive selections without a pairing coroutine run.
 */
internal class SelectionHandoffLatch {
    private var pending: Boolean = false

    /** Fresh state — no highlight tap seen since the last reset. */
    fun reset() {
        pending = false
    }

    /** Called from the Highlight menu-item branch before mode.finish() launches the coroutine. */
    fun beginHighlightHandoff() {
        pending = true
    }

    /**
     * Called from the highlight-creation coroutine's `finally`. Always safe — clears the latch so
     * a subsequent onDestroyActionMode without a highlight tap goes through the normal clear path.
     */
    fun endHighlightHandoff() {
        pending = false
    }

    /**
     * Returns true if `onDestroyActionMode` should synchronously clear the selection-active signal
     * (no handoff is in flight). Returns false if a highlight tap started a coroutine whose
     * `finally` will call [endHighlightHandoff] and the clear itself. Clears the latch on true
     * so the callback instance is usable across successive selections.
     */
    fun shouldClearOnDestroy(): Boolean {
        val clearNow = !pending
        if (clearNow) pending = false
        return clearNow
    }
}
