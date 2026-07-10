package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceTypeTest {
    @Test fun `SourceType has ABS LOCAL_FILES and CHITANKA entries`() {
        assertEquals(3, SourceType.entries.size)
        assertEquals(SourceType.ABS, SourceType.valueOf("ABS"))
        assertEquals(SourceType.LOCAL_FILES, SourceType.valueOf("LOCAL_FILES"))
        assertEquals(SourceType.CHITANKA, SourceType.valueOf("CHITANKA"))
    }
}
