package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceStorageModelTest {

    private fun source(type: SourceType) = Source(
        id = "s",
        url = SourceUrl.parse("https://example.test")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "",
        type = type,
    )

    // ─── hasCacheTier ────────────────────────────────────────────────────────────────────────────
    //
    // ABS sources have a remote catalog, so cache-on-open is the two-tier storage model from
    // ADR 0011. When LocalFiles lands (#437 / #438), it must return false here so cache-on-open is
    // skipped and the Downloads Screen collapses to a single section. A flip on ABS would be a
    // silent regression — the whole two-tier design assumes true.

    @Test fun `ABS has a cache tier`() {
        assertTrue(SourceType.ABS.hasCacheTier())
    }

    // ─── showCachedSectionFor ────────────────────────────────────────────────────────────────────

    @Test fun `showCachedSectionFor ABS active source returns true`() {
        assertTrue(showCachedSectionFor(source(SourceType.ABS)))
    }

    @Test fun `showCachedSectionFor null active source defaults to true`() {
        // A null active source is a transient UI state (bootstrapping / just cleared). Defaulting
        // to true preserves the pre-#436 two-section layout so the screen doesn't flicker sections
        // in/out during activation.
        assertTrue(showCachedSectionFor(null))
    }

    // Guard so a future SourceType addition can't silently reuse the ABS branch. The `when` below
    // is exhaustive on SourceType — a new value forces the compiler to fail this file, prompting
    // an explicit cache-tier decision alongside the enum change.
    @Test fun `every SourceType has an explicit cache-tier decision`() {
        for (type in SourceType.entries) {
            val expected = when (type) {
                SourceType.ABS -> true
                SourceType.LOCAL_FILES -> false
                SourceType.CHITANKA -> false
            }
            assertEquals("SourceType.$type disagrees with hasCacheTier()", expected, type.hasCacheTier())
        }
    }

    // ─── hasReadaloud ────────────────────────────────────────────────────────────────────────────
    //
    // Only ABS carries Storyteller-matched readaloud bundles today. Chitanka and LocalFiles have
    // no readaloud concept at all, so the Downloads Screen's "Readaloud (streaming)" section is
    // meaningless there and gets hidden by [showReadaloudSectionFor].

    @Test fun `ABS has readaloud`() {
        assertTrue(SourceType.ABS.hasReadaloud())
    }

    @Test fun `Chitanka has no readaloud`() {
        assertEquals(false, SourceType.CHITANKA.hasReadaloud())
    }

    @Test fun `LocalFiles has no readaloud`() {
        assertEquals(false, SourceType.LOCAL_FILES.hasReadaloud())
    }

    @Test fun `showReadaloudSectionFor ABS active source returns true`() {
        assertTrue(showReadaloudSectionFor(source(SourceType.ABS)))
    }

    @Test fun `showReadaloudSectionFor Chitanka active source returns false`() {
        assertEquals(false, showReadaloudSectionFor(source(SourceType.CHITANKA)))
    }

    @Test fun `showReadaloudSectionFor null active source defaults to true`() {
        // Parity with [showCachedSectionFor] — null is transient, keep the section header rather
        // than flicker it in/out during source activation.
        assertTrue(showReadaloudSectionFor(null))
    }

    @Test fun `every SourceType has an explicit readaloud decision`() {
        for (type in SourceType.entries) {
            val expected = when (type) {
                SourceType.ABS -> true
                SourceType.LOCAL_FILES -> false
                SourceType.CHITANKA -> false
            }
            assertEquals("SourceType.$type disagrees with hasReadaloud()", expected, type.hasReadaloud())
        }
    }
}
