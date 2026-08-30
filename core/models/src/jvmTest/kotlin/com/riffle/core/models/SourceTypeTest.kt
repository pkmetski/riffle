package com.riffle.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.riffle.core.models.SourceType

class SourceTypeTest {
    @Test fun `SourceType has ABS LOCAL_FILES CHITANKA GUTENBERG KOMGA and RADIO_ES entries`() {
        assertEquals(6, SourceType.entries.size)
        assertEquals(SourceType.ABS, SourceType.valueOf("ABS"))
        assertEquals(SourceType.LOCAL_FILES, SourceType.valueOf("LOCAL_FILES"))
        assertEquals(SourceType.CHITANKA, SourceType.valueOf("CHITANKA"))
        assertEquals(SourceType.GUTENBERG, SourceType.valueOf("GUTENBERG"))
        assertEquals(SourceType.KOMGA, SourceType.valueOf("KOMGA"))
        assertEquals(SourceType.RADIO_ES, SourceType.valueOf("RADIO_ES"))
    }

    @Test fun `isUnboundedCatalog identifies the network-only catalogues`() {
        assertFalse(SourceType.ABS.isUnboundedCatalog)
        assertFalse(SourceType.LOCAL_FILES.isUnboundedCatalog)
        assertTrue(SourceType.CHITANKA.isUnboundedCatalog)
        assertTrue(SourceType.GUTENBERG.isUnboundedCatalog)
        assertFalse(SourceType.KOMGA.isUnboundedCatalog)
        assertTrue(SourceType.RADIO_ES.isUnboundedCatalog)
    }
}
