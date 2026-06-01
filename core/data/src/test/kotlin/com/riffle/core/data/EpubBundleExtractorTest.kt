package com.riffle.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubBundleExtractorTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun extractEpub_writesInnerEpubToWorkingDir() {
        val epubBytes = "EPUB PAYLOAD".toByteArray()
        val bundle = buildBundle(
            entries = mapOf(
                "book.epub" to epubBytes,
                "overlay/chapter1.smil" to "<smil/>".toByteArray(),
            ),
        )

        val out = EpubBundleExtractor.extractEpub(ByteArrayInputStream(bundle), tmp.root)

        assertTrue(out.exists())
        assertEquals(epubBytes.toList(), out.readBytes().toList())
    }

    @Test fun extractEpub_throws_whenNoEpubEntry() {
        val bundle = buildBundle(entries = mapOf("overlay.smil" to ByteArray(0)))

        try {
            EpubBundleExtractor.extractEpub(ByteArrayInputStream(bundle), tmp.root)
            error("expected exception")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("epub", ignoreCase = true))
        }
    }

    private fun buildBundle(entries: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
