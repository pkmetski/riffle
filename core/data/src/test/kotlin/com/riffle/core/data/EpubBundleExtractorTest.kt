package com.riffle.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.CRC32
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

    @Test fun extractEpub_doesNotLeaveTempFile_whenCopyFails() {
        // Use STORED (no compression) so the epub payload bytes are written verbatim
        // into the zip stream. The wrapper stream throws after 512 bytes, which is
        // well past the local-file header (~30 bytes) but mid-way through the 1024-
        // byte payload, guaranteeing the failure occurs inside copyTo after
        // createTempFile has already been called.
        val epubPayload = ByteArray(1024) { it.toByte() }
        val bundle = buildStoredBundle(entries = mapOf("book.epub" to epubPayload))
        val bundleStream = ByteArrayInputStream(bundle)
        val failAfter = 512
        var bytesRead = 0
        val failingStream = object : java.io.InputStream() {
            override fun read(): Int {
                if (bytesRead >= failAfter) throw java.io.IOException("simulated mid-copy failure")
                bytesRead++
                return bundleStream.read()
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (bytesRead >= failAfter) throw java.io.IOException("simulated mid-copy failure")
                val toRead = minOf(len, failAfter - bytesRead)
                val n = bundleStream.read(b, off, toRead)
                if (n > 0) bytesRead += n
                return n
            }
        }

        val before = tmp.root.listFiles()?.size ?: 0
        try {
            EpubBundleExtractor.extractEpub(failingStream, tmp.root)
            error("expected exception")
        } catch (e: java.io.IOException) {
            // expected
        }
        val after = tmp.root.listFiles()?.size ?: 0
        assertEquals(before, after)
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

    /** Build a zip where every entry uses STORED (no compression) so payload bytes are verbatim. */
    private fun buildStoredBundle(entries: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            entries.forEach { (name, content) ->
                val entry = ZipEntry(name)
                entry.method = ZipEntry.STORED
                entry.size = content.size.toLong()
                entry.compressedSize = content.size.toLong()
                val crc = CRC32().also { it.update(content) }
                entry.crc = crc.value
                zip.putNextEntry(entry)
                zip.write(content)
                zip.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
