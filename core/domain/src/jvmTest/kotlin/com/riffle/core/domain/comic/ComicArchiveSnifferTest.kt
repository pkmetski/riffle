package com.riffle.core.domain.comic

import org.junit.Assert.assertEquals
import org.junit.Test

class ComicArchiveSnifferTest {

    @Test
    fun `PK header sniffs as ZIP`() {
        val head = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)
        assertEquals(ComicArchiveKind.ZIP, ComicArchiveSniffer.sniff(head))
    }

    @Test
    fun `Rar header sniffs as RAR`() {
        val head = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
        assertEquals(ComicArchiveKind.RAR, ComicArchiveSniffer.sniff(head))
    }

    @Test
    fun `garbage sniffs as UNKNOWN`() {
        assertEquals(ComicArchiveKind.UNKNOWN, ComicArchiveSniffer.sniff(byteArrayOf(0x00, 0x01, 0x02)))
    }

    @Test
    fun `too short input sniffs as UNKNOWN`() {
        assertEquals(ComicArchiveKind.UNKNOWN, ComicArchiveSniffer.sniff(byteArrayOf(0x50, 0x4B)))
    }
}
