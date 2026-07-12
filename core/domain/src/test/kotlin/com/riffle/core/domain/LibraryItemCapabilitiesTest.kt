package com.riffle.core.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the text-anchored formats for [LibraryItem.canAnnotate]. CBZ (comic archives, e.g. Komga's
 * Comics library) has no selectable text — a library dominated by CBZ must not sprout an
 * Annotations tab that never fills. Reverting the property to `isReadable` (which is true for CBZ)
 * flips these red.
 */
class LibraryItemCapabilitiesTest {

    private fun item(format: EbookFormat) = LibraryItem(
        id = "i",
        libraryId = "l",
        title = "t",
        author = "a",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = format,
    )

    @Test fun `EPUB can be annotated`() {
        assertTrue(item(EbookFormat.Epub).canAnnotate)
    }

    @Test fun `PDF can be annotated`() {
        assertTrue(item(EbookFormat.Pdf).canAnnotate)
    }

    @Test fun `CBZ cannot be annotated (raster comic archive)`() {
        assertFalse(item(EbookFormat.Cbz).canAnnotate)
    }

    @Test fun `Unsupported cannot be annotated`() {
        assertFalse(item(EbookFormat.Unsupported).canAnnotate)
    }

    // Sanity: canAnnotate is stricter than isReadable — a CBZ is readable (opens in the reader)
    // but not annotable. Regression against a future refactor that conflates the two.
    @Test fun `CBZ is readable but not annotable`() {
        val cbz = item(EbookFormat.Cbz)
        assertTrue(cbz.isReadable)
        assertFalse(cbz.canAnnotate)
    }
}
