package com.riffle.app.feature.settings

/** Counts version-row taps and fires [onUnlock] every [requiredTaps] taps. */
class DeveloperOptionsTapCounter(
    private val requiredTaps: Int = 7,
    private val onUnlock: () -> Unit,
) {
    private var count = 0

    fun onTap() {
        count++
        if (count >= requiredTaps) {
            count = 0
            onUnlock()
        }
    }
}
