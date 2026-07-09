package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceTypeTest {
    @Test fun `SourceType has ABS and LOCAL_FILES entries`() {
        assertEquals(2, SourceType.entries.size)
        assertEquals(SourceType.ABS, SourceType.valueOf("ABS"))
        assertEquals(SourceType.LOCAL_FILES, SourceType.valueOf("LOCAL_FILES"))
    }
}
