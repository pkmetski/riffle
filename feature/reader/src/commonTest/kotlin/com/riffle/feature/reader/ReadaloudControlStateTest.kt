package com.riffle.feature.reader

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadaloudControlStateTest {
    @Test fun storytellerBookIsVisibleAndEnabled() {
        assertEquals(
            ReadaloudControlState(visible = true, enabled = true),
            readaloudControlState(isStoryteller = true, isMatchedAbs = false, bundlePresent = false),
        )
    }

    @Test fun unmatchedAbsBookHidesTheControl() {
        assertEquals(
            ReadaloudControlState(visible = false, enabled = false),
            readaloudControlState(isStoryteller = false, isMatchedAbs = false, bundlePresent = false),
        )
    }

    @Test fun matchedAbsWithoutBundleIsVisibleAndEnabled() {
        // ADR 0040: the control is tappable even without a bundle — tapping streams when eligible,
        // else prompts the download. No longer gated on a present bundle.
        assertEquals(
            ReadaloudControlState(visible = true, enabled = true),
            readaloudControlState(isStoryteller = false, isMatchedAbs = true, bundlePresent = false),
        )
    }

    @Test fun matchedAbsWithBundleIsVisibleAndEnabled() {
        assertEquals(
            ReadaloudControlState(visible = true, enabled = true),
            readaloudControlState(isStoryteller = false, isMatchedAbs = true, bundlePresent = true),
        )
    }
}
