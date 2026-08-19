package com.riffle.core.domain.comic

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CbzArchiveComicInfoTest {

    @Test
    fun `readComicInfo returns null when ComicInfo xml is absent`() {
        val file = buildCbz(comicInfo = null, imageCount = 5)
        val archive = CbzArchive(file)
        assertNull(archive.readComicInfo())
        archive.close()
    }

    @Test
    fun `readComicInfo returns null when ComicInfo xml has no Bookmark pages`() {
        val xml = """
            <?xml version="1.0"?>
            <ComicInfo>
              <Pages>
                <Page Image="0" Type="FrontCover"/>
                <Page Image="1" Type="Story"/>
              </Pages>
            </ComicInfo>
        """.trimIndent()
        val file = buildCbz(comicInfo = xml, imageCount = 5)
        val archive = CbzArchive(file)
        assertNull(archive.readComicInfo())
        archive.close()
    }

    @Test
    fun `readComicInfo extracts Bookmark pages in Image order`() {
        val xml = """
            <?xml version="1.0"?>
            <ComicInfo>
              <Pages>
                <Page Image="0" Type="FrontCover"/>
                <Page Image="2" Bookmark="Chapter 1"/>
                <Page Image="10" Bookmark="Chapter 2"/>
              </Pages>
            </ComicInfo>
        """.trimIndent()
        val file = buildCbz(comicInfo = xml, imageCount = 15)
        val archive = CbzArchive(file)
        val bookmarks = archive.readComicInfo()
        assertEquals(
            listOf(
                ComicBookmark(pageIndex = 2, title = "Chapter 1"),
                ComicBookmark(pageIndex = 10, title = "Chapter 2"),
            ),
            bookmarks,
        )
        archive.close()
    }

    @Test
    fun `readComicInfo finds ComicInfo xml in subdirectory`() {
        val xml = """
            <?xml version="1.0"?>
            <ComicInfo>
              <Pages>
                <Page Image="0" Bookmark="Story 1"/>
              </Pages>
            </ComicInfo>
        """.trimIndent()
        val file = buildCbz(comicInfo = xml, imageCount = 3, comicInfoPath = "MyComic/ComicInfo.xml")
        val archive = CbzArchive(file)
        val bookmarks = archive.readComicInfo()
        assertEquals(listOf(ComicBookmark(pageIndex = 0, title = "Story 1")), bookmarks)
        archive.close()
    }

    @Test
    fun `readComicInfo returns null on malformed XML`() {
        val file = buildCbz(comicInfo = "not-xml", imageCount = 3)
        val archive = CbzArchive(file)
        assertNull(archive.readComicInfo())
        archive.close()
    }

    private fun buildCbz(
        comicInfo: String?,
        imageCount: Int,
        comicInfoPath: String = "ComicInfo.xml",
    ): File {
        val tmp = File.createTempFile("test", ".cbz").also { it.deleteOnExit() }
        ZipOutputStream(tmp.outputStream()).use { zip ->
            if (comicInfo != null) {
                zip.putNextEntry(ZipEntry(comicInfoPath))
                zip.write(comicInfo.toByteArray())
                zip.closeEntry()
            }
            repeat(imageCount) { i ->
                zip.putNextEntry(ZipEntry("page%03d.jpg".format(i)))
                zip.write(ByteArray(4))
                zip.closeEntry()
            }
        }
        return tmp
    }
}
