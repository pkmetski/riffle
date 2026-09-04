package com.riffle.app.feature.library

import com.riffle.core.models.SourceType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadDestinationVisibilityTest {
    @Test
    fun `web source item with an import destination can upload`() {
        assertTrue(canUploadWebSourceItem(SourceType.CHITANKA, hasImportDestination = true))
    }

    @Test
    fun `web source item without configured destination cannot upload`() {
        assertFalse(canUploadWebSourceItem(SourceType.GUTENBERG, hasImportDestination = false))
    }

    @Test
    fun `server source item cannot upload through this action`() {
        assertFalse(canUploadWebSourceItem(SourceType.ABS, hasImportDestination = true))
    }

    @Test
    fun `radio-es podcast item with an import destination can upload`() {
        assertTrue(canUploadWebSourceItem(SourceType.RADIO_ES, hasImportDestination = true))
    }

    @Test
    fun `radio-es item without configured destination cannot upload`() {
        assertFalse(canUploadWebSourceItem(SourceType.RADIO_ES, hasImportDestination = false))
    }
}
