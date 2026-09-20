package com.riffle.feature.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the downloads-row size string and media-type badge.
 *
 * iOS carried a private integer-division `formatBytes` that stopped at GB. The values below are
 * what Android's `String.format(Locale.US, "%.1f %s")` produced; several of them read differently
 * under the old iOS version, which is what makes this a regression test rather than a
 * characterisation one — [fiveMillionBytesKeepsItsDecimal] in particular read "4 MB" there.
 */
class DownloadsFormattingTest {

    @Test fun bytesUnderAKilobyteAreRawBytes() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
    }

    @Test fun fiveMillionBytesKeepsItsDecimal() {
        // Old iOS: "4 MB" (integer division). Android: "4.8 MB".
        assertEquals("4.8 MB", formatBytes(5_000_000))
    }

    @Test fun kilobytesKeepADecimal() {
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
    }

    @Test fun hundredUnitsAndUpDropTheDecimal() {
        // %.0f above 100 so the string never outgrows the row.
        assertEquals("312 MB", formatBytes(327_155_712))
        assertEquals("100 KB", formatBytes(102_400))
    }

    @Test fun gigabytes() {
        assertEquals("1.0 GB", formatBytes(1024L * 1024 * 1024))
        assertEquals("1.2 GB", formatBytes(1_288_490_189L))
    }

    @Test fun terabytesGetTheirOwnUnit() {
        // The old iOS version had no TB case and rendered this as a four-digit GB count.
        assertEquals("2.0 TB", formatBytes(2L * 1024 * 1024 * 1024 * 1024))
    }

    @Test fun mediaTypeLabelsAreOrderedByDisplayOrderNotSetIteration() {
        // Built audiobook-first on purpose: a LinkedHashSet preserves insertion order, which is
        // exactly what the iOS badge used to render.
        val types = linkedSetOf(LocalMediaType.Audiobook, LocalMediaType.Epub)
        assertEquals("EPUB + Audiobook", types.displayLabel())
    }

    @Test fun displayOrderIsStableAcrossEveryType() {
        assertEquals(
            listOf(
                LocalMediaType.Epub,
                LocalMediaType.Pdf,
                LocalMediaType.Comic,
                LocalMediaType.Audiobook,
                LocalMediaType.Readaloud,
            ),
            LocalMediaType.entries.sortedBy { it.displayOrder },
        )
    }

    @Test fun mediaTypeLabels() {
        assertEquals("EPUB", LocalMediaType.Epub.label())
        assertEquals("PDF", LocalMediaType.Pdf.label())
        assertEquals("Comic", LocalMediaType.Comic.label())
        assertEquals("Audiobook", LocalMediaType.Audiobook.label())
        assertEquals("Readaloud", LocalMediaType.Readaloud.label())
    }
}
