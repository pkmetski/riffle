@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader.session

import org.readium.r2.shared.publication.Locator

/**
 * Owns the background-return restore loop: the [pendingReturnLocator] anchor and its
 * [returnRestoreAttempts] retry budget. On each onPositionChanged the intake calls
 * [onPositionEmitted]; if a spurious chapter-top emission arrives while a restore is in flight,
 * the origin locator is re-fired through [refire] until either the reader settles at the origin,
 * the user navigates away, or the budget runs out.
 *
 * Split out of [PositionOrchestrator] as part of #376 to isolate the retry state machine from the
 * canonical position stream and from remote-win jump policy.
 */
class ResumeRestorer(
    private val refire: (Locator) -> Unit,
) {

    /**
     * The locator to restore on onReaderResumed. Armed in onReaderClosed from the last known
     * locator; the footnote-popup URL-tap path pre-arms it earlier via [setReturnAnchor].
     */
    private var pendingReturnLocator: Locator? = null

    /**
     * How many remaining onPositionChanged → chapter-top emissions to suppress while restoring
     * after a background return.
     */
    private var returnRestoreAttempts = 0

    /**
     * Retry watcher — invoked from the position intake before it updates the position streams.
     *
     * Defensive re-restore: Readium's post-resume column-snap can emit a chapter-top position
     * AFTER the resume navigateTo, sometimes more than once — including a delayed clobber that
     * arrives AFTER a first emission has already landed at the captured origin. Stay armed
     * (within the attempt budget) as long as we're parked at-or-near the origin; only disarm when
     * the user clearly navigates AWAY (different href, or progression past origin). An emission
     * AT origin means this round took, not that future emissions can't re-clobber us, so don't
     * clear pending on equality.
     */
    fun onPositionEmitted(locator: Locator) {
        val remaining = returnRestoreAttempts
        if (remaining <= 0) return
        val pending = pendingReturnLocator
        if (pending == null) {
            returnRestoreAttempts = 0
            return
        }
        val originHref = pending.href.toString()
        val originProg = pending.locations.progression ?: 0.0
        val incomingHref = locator.href.toString()
        val incomingProg = locator.locations.progression ?: 0.0
        when {
            incomingHref == originHref && incomingProg < originProg - 0.01 -> {
                returnRestoreAttempts = remaining - 1
                refire(pending)
            }
            incomingHref != originHref || incomingProg > originProg + 0.01 -> {
                returnRestoreAttempts = 0
                pendingReturnLocator = null
            }
        }
    }

    /**
     * Sets the pending return anchor. Does NOT overwrite an existing non-null value — the
     * footnote-popup URL-tap path pre-arms with the pre-popup origin, and onReaderClosed must
     * not clobber that with the popup-nudged lastLocator.
     */
    fun setReturnAnchor(locator: Locator?) {
        if (pendingReturnLocator == null) pendingReturnLocator = locator
    }

    /**
     * Forcibly overwrites the return anchor. Used by captureFootnotePopupLinkOrigin which
     * explicitly captures the pre-popup origin.
     */
    fun forceSetReturnAnchor(locator: Locator?) {
        pendingReturnLocator = locator
    }

    /** Consume the pending return anchor and clear it. Returns null if none was set. */
    fun consumeReturnAnchor(): Locator? {
        val current = pendingReturnLocator
        pendingReturnLocator = null
        return current
    }

    /** Read the pending return anchor without clearing it. */
    fun peekReturnAnchor(): Locator? = pendingReturnLocator

    /**
     * Arms the resume restore: stores [origin] as the pending return locator (so [onPositionEmitted]
     * can access it) and fires it through [refire] so the navigator jumps there. Budget = 5.
     */
    fun armReturnRestore(origin: Locator) {
        pendingReturnLocator = origin
        returnRestoreAttempts = 5
        refire(origin)
    }

    /** Reset all per-book state. Called from [PositionOrchestrator.bindBook]. */
    fun reset() {
        pendingReturnLocator = null
        returnRestoreAttempts = 0
    }
}
