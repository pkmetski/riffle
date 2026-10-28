package com.riffle.shared.reader

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private class ZipBuilder {
    val bytes = ArrayList<Byte>()
    val size: Int get() = bytes.size
    fun u16(v: Int) {
        bytes.add((v and 0xFF).toByte())
        bytes.add(((v shr 8) and 0xFF).toByte())
    }
    fun u32(v: Int) {
        u16(v and 0xFFFF)
        u16((v ushr 16) and 0xFFFF)
    }
    fun raw(data: ByteArray) {
        data.forEach { bytes.add(it) }
    }
    fun append(other: ZipBuilder) {
        bytes.addAll(other.bytes)
    }
}

private fun storedZip(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ZipBuilder()
    val central = ZipBuilder()
    for ((name, data) in entries) {
        val nameBytes = name.encodeToByteArray()
        val offset = out.size
        out.u32(0x04034B50)
        listOf(20, 0, 0, 0, 0).forEach { out.u16(it) }
        out.u32(0)
        out.u32(data.size)
        out.u32(data.size)
        out.u16(nameBytes.size)
        out.u16(0)
        out.raw(nameBytes)
        out.raw(data)
        central.u32(0x02014B50)
        listOf(20, 20, 0, 0, 0, 0).forEach { central.u16(it) }
        central.u32(0)
        central.u32(data.size)
        central.u32(data.size)
        listOf(nameBytes.size, 0, 0, 0, 0).forEach { central.u16(it) }
        central.u32(0)
        central.u32(offset)
        central.raw(nameBytes)
    }
    val cdOffset = out.size
    out.append(central)
    out.u32(0x06054B50)
    listOf(0, 0, entries.size, entries.size).forEach { out.u16(it) }
    out.u32(central.size)
    out.u32(cdOffset)
    out.u16(0)
    return out.bytes.toByteArray()
}

private fun archiveWithComicInfo(xml: String) = storedZip(
    "001.png" to byteArrayOf(1),
    "ComicInfo.xml" to xml.encodeToByteArray(),
)

@OptIn(ExperimentalForeignApi::class)
class IosCbzArchiveComicInfoTest {

    @Test
    fun readComicInfo_returnsNull_whenNoComicInfoEntry() {
        val archive = IosCbzArchive(storedZip("001.png" to byteArrayOf(1), "002.png" to byteArrayOf(2)))
        assertNull(archive.readComicInfo())
    }

    @Test
    fun parseComicInfoXml_parsesBookmarks() {
        val xml = """
            <ComicInfo>
              <Pages>
                <Page Image="0" Type="FrontCover" />
                <Page Image="5" Bookmark="Chapter One" />
                <Page Image="12" Bookmark="Chapter Two" />
              </Pages>
            </ComicInfo>
        """.trimIndent()
        val archive = IosCbzArchive(archiveWithComicInfo(xml))
        val bookmarks = archive.readComicInfo()
        assertNotNull(bookmarks)
        assertEquals(2, bookmarks.size)
        assertEquals(5, bookmarks[0].pageIndex)
        assertEquals("Chapter One", bookmarks[0].title)
        assertEquals(12, bookmarks[1].pageIndex)
        assertEquals("Chapter Two", bookmarks[1].title)
    }

    @Test
    fun parseComicInfoXml_returnsNull_whenNoBookmarks() {
        val xml = """<ComicInfo><Pages><Page Image="0" /></Pages></ComicInfo>"""
        val archive = IosCbzArchive(archiveWithComicInfo(xml))
        assertNull(archive.readComicInfo())
    }

    @Test
    fun parseComicInfoXml_skipsBlankBookmarks() {
        val xml = """
            <ComicInfo>
              <Pages>
                <Page Image="0" Bookmark="" />
                <Page Image="3" Bookmark="   " />
                <Page Image="7" Bookmark="Valid Chapter" />
              </Pages>
            </ComicInfo>
        """.trimIndent()
        val archive = IosCbzArchive(archiveWithComicInfo(xml))
        val bookmarks = archive.readComicInfo()
        assertNotNull(bookmarks)
        assertEquals(1, bookmarks.size)
        assertEquals(7, bookmarks[0].pageIndex)
        assertEquals("Valid Chapter", bookmarks[0].title)
    }

    @Test
    fun readComicInfo_findsComicInfoInSubdirectory() {
        val xml = """<ComicInfo><Pages><Page Image="1" Bookmark="Ch 1" /></Pages></ComicInfo>"""
        val archive = IosCbzArchive(
            storedZip(
                "001.png" to byteArrayOf(1),
                "meta/ComicInfo.xml" to xml.encodeToByteArray(),
            ),
        )
        val bookmarks = archive.readComicInfo()
        assertNotNull(bookmarks)
        assertEquals(1, bookmarks[0].pageIndex)
    }
}
