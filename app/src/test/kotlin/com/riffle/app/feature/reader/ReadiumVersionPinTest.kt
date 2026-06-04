package com.riffle.app.feature.reader

import com.riffle.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Flags any future Readium engine bump so it can't sail through unverified.
 *
 * History: we briefly pinned to 3.0.0 believing 3.2.0+ "broke the readaloud highlight." That was a
 * misdiagnosis — the highlight died because the reader renders the ABS ebook and Readium strips the
 * media-overlay sentence spans from the served HTML (independent of engine version). The real fix is
 * the text-anchored highlight (see [ReadaloudLocatorTest] / ReadaloudTextQuotes): the decoration
 * carries the sentence text and Readium falls back to a TextQuoteAnchor search — and 3.3.0's
 * reflowable JS contains the identical fallback, so the highlight renders the same. With that fix
 * plus the single-column reflowable default (FormattingPreferencesMapper), 3.3.0 is the pinned engine.
 *
 * What still IS version-sensitive: reflowable column defaults and decoration *positioning*. There's no
 * automated render test that reliably catches a regression there on the API-25 harness (a bare WebView
 * can't reproduce Readium's real column+scroll layout; a full-navigator render test gets
 * SIGSEGV-suppressed on API 25). So this pin is the guard: a future bump turns CI red until a human
 * RE-VERIFIES, on a real readaloud book, that the synced highlight renders and the page stays
 * single-column, then deliberately raises both the version in libs.versions.toml and the expectation
 * here.
 */
class ReadiumVersionPinTest {

    @Test
    fun readiumIsPinnedToTheVersionWhereTheReadaloudHighlightIsVerified() {
        assertEquals(
            "Readium was bumped from the pinned 3.3.0. Before accepting this, RE-VERIFY on a real " +
                "readaloud book (e.g. The Martian / Project Hail Mary): the synced highlight renders AND " +
                "the reflowable page stays single-column. If verified good, update this expectation and " +
                "the comment in gradle/libs.versions.toml.",
            "3.3.0",
            BuildConfig.READIUM_VERSION,
        )
    }
}
