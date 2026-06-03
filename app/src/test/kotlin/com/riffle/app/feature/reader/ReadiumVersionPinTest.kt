package com.riffle.app.feature.reader

import com.riffle.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Flags any future Readium engine bump so it can't sail through unverified.
 *
 * Readium 3.2.0 reworked EPUB decoration positioning and mispositions the readaloud synced highlight
 * in a multi-column reflowable layout — the highlight silently vanishes on books like The Martian
 * (rendered in two columns). We diagnosed this on the API-25 harness and pinned the engine to 3.0.0,
 * which renders the highlight correctly in both single- and multi-column. See commit f063aeb.
 *
 * There is no automated render test that reliably catches this on the API-25 harness (a bare WebView
 * can't reproduce Readium's real reflowable column+scroll layout, and a full-navigator render test
 * gets SIGSEGV-suppressed on API 25 — like OrientationChangeTest). So this version pin is the guard:
 * Dependabot will keep re-opening the Readium bump, and this test turns its CI red until a human
 * RE-VERIFIES the readaloud highlight on a 2-column reflowable book (e.g. The Martian) and then
 * deliberately raises both the version in libs.versions.toml and the expectation here.
 */
class ReadiumVersionPinTest {

    @Test
    fun readiumIsPinnedToTheVersionWhereTheReadaloudHighlightRendersCorrectly() {
        assertEquals(
            "Readium was bumped from the pinned 3.0.0. Before accepting this, RE-VERIFY the readaloud " +
                "highlight renders on a 2-column reflowable book (The Martian) — 3.2.0+ mispositions it " +
                "in multi-column and the highlight vanishes. If the new version is verified good, update " +
                "this expectation and the comment in gradle/libs.versions.toml.",
            "3.0.0",
            BuildConfig.READIUM_VERSION,
        )
    }
}
