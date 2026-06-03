package com.riffle.app.feature.reader

/** Whether the reader's readaloud control is shown, and whether it can be tapped. */
data class ReadaloudControlState(val visible: Boolean, val enabled: Boolean)

/**
 * Spec §6: Storyteller books always show an enabled control; a matched ABS book shows the
 * control but only enables it once the synced bundle is present; an unmatched ABS book shows
 * no control at all.
 */
fun readaloudControlState(
    isStoryteller: Boolean,
    isMatchedAbs: Boolean,
    bundlePresent: Boolean,
): ReadaloudControlState = when {
    isStoryteller -> ReadaloudControlState(visible = true, enabled = true)
    isMatchedAbs -> ReadaloudControlState(visible = true, enabled = bundlePresent)
    else -> ReadaloudControlState(visible = false, enabled = false)
}
