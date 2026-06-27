package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalReaderPositionTest {

    @Test fun parses_a_full_locator() {
        val p = CanonicalReaderPosition(
            """{"href":"ch5.xhtml","type":"application/xhtml+xml","locations":{"progression":0.42,"totalProgression":0.18}}"""
        )
        assertEquals("ch5.xhtml", p.href)
        assertEquals(0.42, p.chapterProgression!!, 1e-9)
        assertEquals(0.18, p.totalProgression!!, 1e-9)
    }

    /** A canonical reconstructed from a remote (audio / Storyteller) carries no book-wide
     *  progression — the bridge then weights it from chapter character counts. The accessor
     *  must surface that as `null` rather than 0 (which would clear the server's progress bar). */
    @Test fun totalProgression_is_null_when_absent() {
        val p = CanonicalReaderPosition(
            """{"href":"ch5.xhtml","locations":{"progression":0.42}}"""
        )
        assertNull(p.totalProgression)
        assertEquals(0.42, p.chapterProgression!!, 1e-9)
        assertEquals("ch5.xhtml", p.href)
    }

    @Test fun chapterProgression_is_null_when_locations_is_missing() {
        val p = CanonicalReaderPosition("""{"href":"ch5.xhtml"}""")
        assertNull(p.chapterProgression)
        assertNull(p.totalProgression)
        assertEquals("ch5.xhtml", p.href)
    }

    @Test fun href_is_null_when_empty_string_value() {
        val p = CanonicalReaderPosition("")
        assertNull(p.href)
        assertNull(p.chapterProgression)
        assertNull(p.totalProgression)
    }

    @Test fun returns_null_on_malformed_json() {
        val p = CanonicalReaderPosition("not json at all")
        assertNull(p.href)
        assertNull(p.chapterProgression)
        assertNull(p.totalProgression)
    }

    @Test fun returns_null_when_root_is_not_an_object() {
        val p = CanonicalReaderPosition("[1,2,3]")
        assertNull(p.href)
        assertNull(p.chapterProgression)
    }

    @Test fun returns_null_when_href_is_empty_string() {
        val p = CanonicalReaderPosition("""{"href":"","locations":{"progression":0.5}}""")
        assertNull(p.href)
        assertEquals(0.5, p.chapterProgression!!, 1e-9)
    }

    /** Lazy parse: repeated reads of the same accessor don't re-parse the JSON. We can't
     *  observe parser count directly; instead exercise the path many times to confirm no
     *  exception escapes and the value is stable. */
    @Test fun accessors_are_stable_across_repeated_reads() {
        val p = CanonicalReaderPosition(
            """{"href":"ch5.xhtml","locations":{"progression":0.42,"totalProgression":0.18}}"""
        )
        repeat(50) {
            assertEquals("ch5.xhtml", p.href)
            assertEquals(0.42, p.chapterProgression!!, 1e-9)
            assertEquals(0.18, p.totalProgression!!, 1e-9)
        }
    }

    /** Equality and hashCode are still defined solely by the wire value — the lazy field
     *  must not leak into the data-class contract. Two equal-string positions stay equal. */
    @Test fun equality_is_by_wire_value() {
        val a = CanonicalReaderPosition("""{"href":"ch5.xhtml"}""")
        val b = CanonicalReaderPosition("""{"href":"ch5.xhtml"}""")
        // Touch the parsed field on one side only.
        a.href
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
