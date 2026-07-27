package com.riffle.app.feature.annotationsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebdavUiCopyTest {
    @Test
    fun `WebDAV UI identifies Komga without Komga-specific controls`() {
        assertEquals("WebDAV annotation sync for Komga", WebdavUiCopy.SECTION_TITLE)
        assertEquals("WebDAV annotation sync for Komga", WebdavUiCopy.SCREEN_TITLE)
        assertEquals("Configure WebDAV", WebdavUiCopy.CONFIGURE_TITLE)
        assertEquals("Add WebDAV", WebdavUiCopy.ADD_TITLE)
        assertEquals("Edit WebDAV", WebdavUiCopy.EDIT_TITLE)
        assertTrue(WebdavUiCopy.HELP_TEXT.contains("Available for Komga sources"))
        assertTrue(WebdavUiCopy.NOT_CONFIGURED_STATUS.contains("available for Komga sources"))
    }
}
