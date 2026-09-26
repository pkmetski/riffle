package com.riffle.feature.reader

/**
 * Sequences programmatic landings against the deferred scroll compensation of a chapter slot
 * that sits above the viewport (#1109).
 *
 * When a deferred behind-neighbour reports its first real height, the controller applies the
 * height to the slot immediately but can only compensate the outer scroll on the NEXT layout
 * pass (NestedScrollView clips an immediate `scrollBy` to the still-placeholder-sized child).
 * Between those two moments the slot tops the controller derives from `measuredHeights` are
 * stale by the not-yet-applied delta. A landing whose target Y was computed in that window and
 * executed after the compensation lands short by exactly that delta — the annotated phrase ends
 * up thousands of px below the viewport, which is what the CI phone harness reported for
 * continuous-mode annotation focus. Pure sequencing, shared so an iOS continuous reader with
 * the same deferred-neighbour layout can reuse it.
 *
 * [begin] / [end] bracket one pending compensation per slot key; [runWhenSettled] runs [block]
 * right away when nothing is pending and otherwise holds the LATEST request until the last
 * pending compensation has been applied (or its slot evicted). Older held requests are dropped:
 * they were computed for geometry that no longer exists, and the newest one supersedes them.
 */
class AboveSlotCompensationGate {
    private val pendingKeys = mutableSetOf<Any>()
    private var deferred: (() -> Unit)? = null

    val pending: Int get() = pendingKeys.size

    fun begin(key: Any) {
        pendingKeys.add(key)
    }

    /**
     * [key]'s compensation was applied, or its slot was evicted before it could be. Idempotent.
     * Releases the held request once no compensation remains pending.
     */
    fun end(key: Any) {
        if (!pendingKeys.remove(key) || pendingKeys.isNotEmpty()) return
        val block = deferred ?: return
        deferred = null
        block()
    }

    fun runWhenSettled(block: () -> Unit) {
        if (pendingKeys.isNotEmpty()) deferred = block else block()
    }

    /** Drop the held request without touching the pending set (the user took over). */
    fun cancelDeferred() {
        deferred = null
    }

    /** Forget everything (window rebuilt). */
    fun reset() {
        pendingKeys.clear()
        deferred = null
    }
}
