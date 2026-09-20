package com.riffle.shared.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The spine payload the Swift bridge hands the chapter map.
 *
 * `hrefs` and `positionCounts` must stay index-aligned: `weightSegmentsByChapterLength` looks a
 * segment's spine index up in the first list and reads its length out of the second, so a pair of
 * different lengths silently mis-sizes every rail segment. Truncating to the common length is the
 * safe failure — the tail loses its weights and falls back to 1f rather than borrowing another
 * chapter's.
 */
class SpinePositionsTest {

    @Test
    fun parsesHrefsAndCounts() {
        val parsed = parseSpineJson("""{"hrefs":["one.xhtml","two.xhtml"],"positionCounts":[12,34]}""")
        assertEquals(listOf("one.xhtml", "two.xhtml"), parsed.hrefs)
        assertEquals(listOf(12, 34), parsed.positionCounts)
        assertTrue(parsed.isUsable)
    }

    @Test
    fun anEmptySpineIsNotUsable() {
        val parsed = parseSpineJson("""{"hrefs":[],"positionCounts":[]}""")
        assertEquals(SpinePositions.Empty, parsed)
        assertFalse(parsed.isUsable)
    }

    /** Readium computes positions after the publication opens; the hrefs can arrive first. */
    @Test
    fun hrefsWithoutCountsAreNotUsableYet() {
        val parsed = parseSpineJson("""{"hrefs":["one.xhtml"],"positionCounts":[]}""")
        assertFalse(parsed.isUsable)
    }

    @Test
    fun mismatchedListsAreTruncatedToTheirCommonLength() {
        val parsed = parseSpineJson(
            """{"hrefs":["one.xhtml","two.xhtml","three.xhtml"],"positionCounts":[12,34]}""",
        )
        assertEquals(listOf("one.xhtml", "two.xhtml"), parsed.hrefs)
        assertEquals(listOf(12, 34), parsed.positionCounts)
    }

    @Test
    fun malformedPayloadsDegradeToEmptyRatherThanThrowing() {
        assertEquals(SpinePositions.Empty, parseSpineJson(""))
        assertEquals(SpinePositions.Empty, parseSpineJson("not json"))
        assertEquals(SpinePositions.Empty, parseSpineJson("[]"))
        assertEquals(SpinePositions.Empty, parseSpineJson("""{"hrefs":["one.xhtml"]}"""))
    }
}
