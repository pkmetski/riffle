package com.riffle.core.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * Builds the Readaloud sidecar — the `/synced` bundle with the audio stripped out — by range-
 * extracting the non-audio entries and repackaging them, so the existing EPUB/SMIL readers run on
 * the result unchanged (ADR 0028).
 */
class ReadaloudSidecarReaderTest {

    private val smil = "<smil><par/></smil>".toByteArray()
    private val html = "<html>chapter one</html>".toByteArray()
    private val audio = Random(7).nextBytes(1_000_000)

    private val bundle = zipOf(
        "mimetype" to "application/epub+zip".toByteArray(),
        "OEBPS/content.opf" to "<package/>".toByteArray(),
        "MediaOverlays/c1.smil" to smil,
        "text/c1.html" to html,
        "Audio/big.mp3" to audio,
    )

    private class CountingReader(val data: ByteArray) : RangeReader {
        var bytesRead = 0L
        override fun read(offset: Long, length: Int): ByteArray {
            bytesRead += length
            return data.copyOfRange(offset.toInt(), (offset + length).toInt())
        }
    }

    @Test
    fun `sidecar keeps text and overlays but drops the audio`() {
        val sidecar = ReadaloudSidecarReader.read(bundle.size.toLong(), CountingReader(bundle))
        val names = zipNames(sidecar)
        assertTrue(names.contains("OEBPS/content.opf"))
        assertTrue(names.contains("MediaOverlays/c1.smil"))
        assertTrue(names.contains("text/c1.html"))
        assertFalse(names.contains("Audio/big.mp3"))
    }

    @Test
    fun `sidecar preserves the original entry bytes`() {
        val sidecar = ReadaloudSidecarReader.read(bundle.size.toLong(), CountingReader(bundle))
        assertArrayEquals(smil, zipEntry(sidecar, "MediaOverlays/c1.smil"))
        assertArrayEquals(html, zipEntry(sidecar, "text/c1.html"))
    }

    @Test
    fun `building the sidecar never reads the audio`() {
        val reader = CountingReader(bundle)
        ReadaloudSidecarReader.read(bundle.size.toLong(), reader)
        assertTrue("read ${reader.bytesRead} of ${bundle.size}", reader.bytesRead < 100_000)
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun zipNames(bytes: ByteArray): Set<String> {
        val names = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) { names += e.name; e = zis.nextEntry }
        }
        return names
    }

    private fun zipEntry(bytes: ByteArray, name: String): ByteArray {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name == name) return zis.readBytes()
                e = zis.nextEntry
            }
        }
        error("entry $name not found")
    }
}
