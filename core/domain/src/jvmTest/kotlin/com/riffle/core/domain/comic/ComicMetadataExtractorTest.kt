package com.riffle.core.domain.comic

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ComicMetadataExtractorTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `extracts page count and first-image cover`() {
        val file = tmp.newFile("comic.cbz").apply {
            ZipOutputStream(outputStream()).use { z ->
                z.write("ComicInfo.xml", "<xml/>".toByteArray())
                z.write("page-002.jpg", byteArrayOf(0x02))
                z.write("page-001.png", byteArrayOf(0x01))
                z.write("page-003.jpg", byteArrayOf(0x03))
            }
        }
        val metadata = ComicMetadataExtractor.extract(file)
        assertEquals(3, metadata.pageCount)
        // first sorted image (page-001.png)
        assertArrayEquals(byteArrayOf(0x01), metadata.coverBytes)
        assertEquals("png", metadata.coverExtension)
    }

    @Test
    fun `empty result for archive with no images`() {
        val file = tmp.newFile("empty.cbz").apply {
            ZipOutputStream(outputStream()).use { z ->
                z.write("ComicInfo.xml", "<xml/>".toByteArray())
            }
        }
        val metadata = ComicMetadataExtractor.extract(file)
        assertEquals(0, metadata.pageCount)
        assertNull(metadata.coverBytes)
    }

    @Test
    fun `garbage file returns EMPTY without throwing`() {
        val file = tmp.newFile("junk.cbz").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val metadata = ComicMetadataExtractor.extract(file)
        assertEquals(ComicMetadata.EMPTY, metadata)
    }

    private fun ZipOutputStream.write(name: String, data: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(data)
        closeEntry()
    }
}
