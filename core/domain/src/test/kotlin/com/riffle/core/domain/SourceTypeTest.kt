package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTypeTest {
    @Test fun `SourceType has ABS LOCAL_FILES CHITANKA and GUTENBERG entries`() {
        assertEquals(4, SourceType.entries.size)
        assertEquals(SourceType.ABS, SourceType.valueOf("ABS"))
        assertEquals(SourceType.LOCAL_FILES, SourceType.valueOf("LOCAL_FILES"))
        assertEquals(SourceType.CHITANKA, SourceType.valueOf("CHITANKA"))
        assertEquals(SourceType.GUTENBERG, SourceType.valueOf("GUTENBERG"))
    }

    @Test fun `isUnboundedCatalog identifies the network-only catalogues`() {
        assertFalse(SourceType.ABS.isUnboundedCatalog)
        assertFalse(SourceType.LOCAL_FILES.isUnboundedCatalog)
        assertTrue(SourceType.CHITANKA.isUnboundedCatalog)
        assertTrue(SourceType.GUTENBERG.isUnboundedCatalog)
    }
}
