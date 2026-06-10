package com.riffle.core.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * Pulls individual entries out of a ZIP via byte-range reads only — the mechanism that lets Riffle
 * extract the ~1 MB Readaloud sidecar (SMIL + chapter text) from the hundreds-of-MB `/synced`
 * bundle without downloading the audio (ADR 0028).
 */
class ZipRangeExtractorTest {

    private val smil = "<smil><par/></smil>".toByteArray()
    private val html = "<html>chapter one</html>".toByteArray()
    private val audio = Random(42).nextBytes(1_000_000) // incompressible → stays ~1 MB in the zip

    private val zip = buildZip(
        "OEBPS/content.opf" to "<package/>".toByteArray(),
        "MediaOverlays/c1.smil" to smil,
        "text/c1.html" to html,
        "Audio/big.mp3" to audio,
    )

    private fun buildZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private class CountingReader(val data: ByteArray) : RangeReader {
        var bytesRead = 0L
        override fun read(offset: Long, length: Int): ByteArray {
            bytesRead += length
            return data.copyOfRange(offset.toInt(), (offset + length).toInt())
        }
    }

    @Test
    fun `lists every entry from the central directory`() {
        val ex = ZipRangeExtractor(zip.size.toLong(), CountingReader(zip))
        val names = ex.entries().map { it.name }
        assertTrue(names.containsAll(listOf("OEBPS/content.opf", "MediaOverlays/c1.smil", "text/c1.html", "Audio/big.mp3")))
    }

    @Test
    fun `extracts and inflates an entry back to its original bytes`() {
        val ex = ZipRangeExtractor(zip.size.toLong(), CountingReader(zip))
        val smilEntry = ex.entries().first { it.name.endsWith(".smil") }
        assertArrayEquals(smil, ex.extract(smilEntry))
    }

    @Test
    fun `extracting the small entries never reads the audio range`() {
        val reader = CountingReader(zip)
        val ex = ZipRangeExtractor(zip.size.toLong(), reader)
        ex.entries()
            .filter { it.name.endsWith(".smil") || it.name.endsWith(".html") || it.name.endsWith(".opf") }
            .forEach { ex.extract(it) }
        assertTrue("read ${reader.bytesRead} of ${zip.size} bytes — must skip the 1 MB audio", reader.bytesRead < 100_000)
    }
}
