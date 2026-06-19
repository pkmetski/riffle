package com.riffle.app.feature.reader

/** Whether the reader's readaloud control is shown, and whether it can be tapped. */
data class ReadaloudControlState(val visible: Boolean, val enabled: Boolean)

/**
 * Storyteller books always show an enabled control; a matched ABS book also shows an enabled control
 * (ADR 0028: tapping streams when eligible, else prompts the bundle download — the reader no longer
 * gates on a present bundle); an unmatched ABS book shows no control at all. [bundlePresent] is
 * retained for callers but no longer gates enablement.
 */
fun readaloudControlState(
    isStoryteller: Boolean,
    isMatchedAbs: Boolean,
    @Suppress("UNUSED_PARAMETER") bundlePresent: Boolean,
): ReadaloudControlState = when {
    isStoryteller -> ReadaloudControlState(visible = true, enabled = true)
    isMatchedAbs -> ReadaloudControlState(visible = true, enabled = true)
    else -> ReadaloudControlState(visible = false, enabled = false)
}
