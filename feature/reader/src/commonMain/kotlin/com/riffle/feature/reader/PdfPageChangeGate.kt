package com.riffle.feature.reader

/**
 * Stateful gate that skips the first [onPageChanged] call after [reset] so the platform
 * navigator's "initial-position restore" event (fired when the navigator jumps to the saved page
 * on open) is not treated as user navigation and written back to the position store.
 *
 * On Android the equivalent was [PdfLocatorGate] which also skipped the all-null seed locator
 * from PdfiumNavigatorFragment. That seed no longer reaches the shared VM — the Android screen
 * filters `position == null` locators before forwarding to [PdfReaderViewModel.onPageChanged].
 */
internal class PdfPageChangeGate {
    private var initialRestoreSeen = false

    /** Returns true if the event represents real user navigation and should be persisted. */
    fun advance(): Boolean {
        if (!initialRestoreSeen) {
            initialRestoreSeen = true
            return false
        }
        return true
    }

    fun reset() { initialRestoreSeen = false }
}
