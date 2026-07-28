package com.riffle.core.domain.comic

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CbzArchiveTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `page order is filename sorted, non-image entries filtered`() {
        val file = tmp.newFile("comic.cbz").apply {
            ZipOutputStream(outputStream()).use { z ->
                // deliberately mixed order + non-image junk to prove filter + sort
                z.write("ComicInfo.xml", "<xml/>".toByteArray())
                z.write("page-002.png", pngBytes(0x02))
                z.write("__MACOSX/._page-001.jpg", "junk".toByteArray())
                z.write(".hidden.jpg", "junk".toByteArray())
                z.write("page-010.jpg", pngBytes(0x0A))
                z.write("page-001.jpg", pngBytes(0x01))
                z.write("Thumbs.db", "junk".toByteArray())
            }
        }

        CbzArchive(file).use { archive ->
            assertEquals(3, archive.pageCount)
            assertArrayEquals(pngBytes(0x01), archive.imageBytes(0))
            assertArrayEquals(pngBytes(0x02), archive.imageBytes(1))
            assertArrayEquals(pngBytes(0x0A), archive.imageBytes(2))
            assertEquals("image/jpeg", archive.mediaType(0))
            assertEquals("image/png", archive.mediaType(1))
        }
    }

    @Test
    fun `zero pages when archive has no images`() {
        val file = tmp.newFile("empty.cbz").apply {
            ZipOutputStream(outputStream()).use { z ->
                z.write("ComicInfo.xml", "<xml/>".toByteArray())
            }
        }
        CbzArchive(file).use { archive ->
            assertEquals(0, archive.pageCount)
        }
    }

    private fun pngBytes(marker: Byte) = ByteArray(8) { if (it == 7) marker else 0x89.toByte() }

    private fun ZipOutputStream.write(name: String, data: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(data)
        closeEntry()
    }
}
