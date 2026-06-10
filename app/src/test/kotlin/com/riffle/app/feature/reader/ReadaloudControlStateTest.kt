package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadaloudControlStateTest {
    @Test fun storyteller_book_is_visible_and_enabled() {
        assertEquals(
            ReadaloudControlState(visible = true, enabled = true),
            readaloudControlState(isStoryteller = true, isMatchedAbs = false, bundlePresent = false),
        )
    }

    @Test fun unmatched_abs_book_hides_the_control() {
        assertEquals(
            ReadaloudControlState(visible = false, enabled = false),
            readaloudControlState(isStoryteller = false, isMatchedAbs = false, bundlePresent = false),
        )
    }

    @Test fun matched_abs_without_bundle_is_visible_and_enabled() {
        // ADR 0028: the control is tappable even without a bundle — tapping streams when eligible,
        // else prompts the download. No longer gated on a present bundle.
        assertEquals(
            ReadaloudControlState(visible = true, enabled = true),
            readaloudControlState(isStoryteller = false, isMatchedAbs = true, bundlePresent = false),
        )
    }

    @Test fun matched_abs_with_bundle_is_visible_and_enabled() {
        assertEquals(
            ReadaloudControlState(visible = true, enabled = true),
            readaloudControlState(isStoryteller = false, isMatchedAbs = true, bundlePresent = true),
        )
    }
}
