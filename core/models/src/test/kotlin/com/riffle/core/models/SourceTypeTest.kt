package com.riffle.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.riffle.core.models.SourceType

class SourceTypeTest {
    @Test fun `SourceType has ABS LOCAL_FILES CHITANKA GUTENBERG and KOMGA entries`() {
        assertEquals(5, SourceType.entries.size)
        assertEquals(SourceType.ABS, SourceType.valueOf("ABS"))
        assertEquals(SourceType.LOCAL_FILES, SourceType.valueOf("LOCAL_FILES"))
        assertEquals(SourceType.CHITANKA, SourceType.valueOf("CHITANKA"))
        assertEquals(SourceType.GUTENBERG, SourceType.valueOf("GUTENBERG"))
        assertEquals(SourceType.KOMGA, SourceType.valueOf("KOMGA"))
    }

    @Test fun `isUnboundedCatalog identifies the network-only catalogues`() {
        assertFalse(SourceType.ABS.isUnboundedCatalog)
        assertFalse(SourceType.LOCAL_FILES.isUnboundedCatalog)
        assertTrue(SourceType.CHITANKA.isUnboundedCatalog)
        assertTrue(SourceType.GUTENBERG.isUnboundedCatalog)
        assertFalse(SourceType.KOMGA.isUnboundedCatalog)
    }
}
