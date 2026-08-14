package com.riffle.app.feature.library

import com.riffle.core.catalog.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryItemImportProgressTest {

    @Test
    fun `uses the stored audiobook position instead of the stale library fraction`() {
        assertEquals(
            0.25f,
            importAudioProgress(positionSec = 900.0, durationSec = 3600.0, fallback = 0.1f)!!,
            0.0001f,
        )
    }

    @Test
    fun `falls back to library progress when no audiobook position exists`() {
        assertEquals(0.1f, importAudioProgress(null, 3600.0, 0.1f)!!, 0.0001f)
    }

    @Test
    fun `uses translated CFI for EPUB uploads`() {
        assertEquals("epubcfi(/6/4)", importEbookLocation(BookFormat.Epub, "epubcfi(/6/4)"))
    }

    @Test
    fun `keeps numeric EPUB progress when no CFI can be translated`() {
        assertEquals("", importEbookLocation(BookFormat.Epub, null))
    }

    @Test
    fun `does not attach an ebook location to non EPUB uploads`() {
        assertNull(importEbookLocation(BookFormat.Pdf, "epubcfi(/6/4)"))
    }
}
