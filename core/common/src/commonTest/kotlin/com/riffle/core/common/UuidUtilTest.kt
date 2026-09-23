package com.riffle.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UuidUtilTest {

    @Test
    fun `randomUuidString returns 36-char hyphenated uuid`() {
        val id = randomUuidString()
        assertEquals(36, id.length)
        assertTrue(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun `two calls return distinct ids`() {
        val a = randomUuidString()
        val b = randomUuidString()
        assertTrue(a != b, "Expected distinct UUIDs, got $a twice")
    }
}
