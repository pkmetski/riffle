package com.riffle.app.feature.reader

import org.readium.r2.shared.publication.Locator

/**
 * Stateful gate that implements the PDF reader's initial-locator-seen logic.
 *
 * [org.readium.adapter.pdfium.navigator.PdfiumNavigatorFragment]'s underlying
 * `PdfNavigatorViewModel` seeds its `currentLocator` StateFlow with `Locator.Locations()` (all
 * null) before Pdfium has rendered any page. Without special handling, that seed emission would
 * consume the "initial locator seen" guard, causing the next real Pdfium emission (page 1) to be
 * treated as user navigation and saved to the local position store — bumping `localUpdatedAt` to
 * "now" and making a subsequent LocalWins sync cycle push page 1 over the server's real progress
 * (e.g. Komga page 50 → reset to page 1).
 *
 * The fix: a locator with `position == null` is a seed and does NOT consume the guard.
 */
internal class PdfLocatorGate {
    private var initialLocatorSeen = false

    /**
     * Returns true when the locator represents user-driven navigation and should be persisted;
     * false when it should be swallowed (StateFlow seed or the initial navigator position).
     */
    fun advance(locator: Locator): Boolean {
        if (!initialLocatorSeen) {
            // position == null means this is the all-null seed emission from PdfNavigatorViewModel's
            // StateFlow constructor — skip it without consuming the guard.
            if (locator.locations.position == null) return false
            initialLocatorSeen = true
            return false  // first real-position locator: consume guard, don't save
        }
        return true
    }

    fun reset() { initialLocatorSeen = false }
}
