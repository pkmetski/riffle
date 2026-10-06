package com.riffle.feature.library

import com.riffle.core.catalog.BookFormat
import com.riffle.core.domain.AUDIOBOOK_FINISHED_EPS_SEC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun `snaps to 1f when position is within epsilon of duration`() {
        // Regression: the old private copy did plain division without the epsilon guard,
        // so a position 0.5s before the end rendered as ~99% instead of 100%.
        val duration = 3600.0
        assertEquals(
            1f,
            importAudioProgress(
                positionSec = duration - AUDIOBOOK_FINISHED_EPS_SEC + 0.1,
                durationSec = duration,
                fallback = null,
            )!!,
            0f,
        )
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
