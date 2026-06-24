package com.riffle.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TombstoneCompactorTest {

    @Test
    fun `drops records with riffle deleted true and keeps the rest`() {
        val input = """
            [
              {"id":"a","riffle:deleted":"false"},
              {"id":"b","riffle:deleted":"true"},
              {"id":"c"}
            ]
        """.trimIndent()
        val result = TombstoneCompactor.compact(input)
        assertEquals(1, result.removed)
        assertEquals(2, result.kept)
        // Removed entry should not appear in output; survivors must.
        assert(!result.newContent.contains("\"id\":\"b\""))
        assert(result.newContent.contains("\"id\":\"a\""))
        assert(result.newContent.contains("\"id\":\"c\""))
    }

    @Test
    fun `case-insensitive tombstone flag`() {
        val input = """[{"id":"a","riffle:deleted":"TRUE"},{"id":"b"}]"""
        val result = TombstoneCompactor.compact(input)
        assertEquals(1, result.removed)
        assertEquals(1, result.kept)
    }

    @Test
    fun `no-op when zero tombstones — returns the same string instance`() {
        val input = """[{"id":"a"},{"id":"b","riffle:deleted":"false"}]"""
        val result = TombstoneCompactor.compact(input)
        assertEquals(0, result.removed)
        assertEquals(2, result.kept)
        assertSame(input, result.newContent)
    }

    @Test
    fun `empty array result when all records were tombstones`() {
        val input = """[{"id":"a","riffle:deleted":"true"}]"""
        val result = TombstoneCompactor.compact(input)
        assertEquals(1, result.removed)
        assertEquals(0, result.kept)
        assertEquals("[]", result.newContent)
    }

    @Test
    fun `malformed JSON returns the original content unmodified`() {
        val input = "not json {{"
        val result = TombstoneCompactor.compact(input)
        assertEquals(0, result.removed)
        assertSame(input, result.newContent)
    }

    @Test
    fun `single-object body (legacy bare-object format) is tolerated`() {
        val keep = """{"id":"alive"}"""
        val result = TombstoneCompactor.compact(keep)
        assertEquals(0, result.removed)
        assertSame(keep, result.newContent)

        val drop = """{"id":"dead","riffle:deleted":"true"}"""
        val result2 = TombstoneCompactor.compact(drop)
        assertEquals(1, result2.removed)
        assertEquals("[]", result2.newContent)
    }
}
