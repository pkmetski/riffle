package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceTypeTest {
    @Test fun `SourceType has ABS entry`() {
        assertEquals(1, SourceType.entries.size)
        assertEquals(SourceType.ABS, SourceType.valueOf("ABS"))
    }
}
