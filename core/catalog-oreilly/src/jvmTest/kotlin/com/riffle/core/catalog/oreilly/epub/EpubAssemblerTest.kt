package com.riffle.core.catalog.oreilly.epub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.junit.Test

/**
 * Verifies the hand-rolled [EpubZipWriter] / [EpubAssembler] produce a spec-valid archive that the
 * JVM's own ZIP reader accepts — this is the regression pin for the synthesis path that does NOT
 * require any network access. If CRC-32, header offsets, or the STORED-mimetype rule regress, the
 * JVM ZipInputStream throws or the assertions below flip.
 */
class EpubAssemblerTest {

    private fun sampleBook() = SynthesizedBook(
        identifier = "urn:oreilly:9781098100000",
        title = "Deep & <Wide> \"Kotlin\"",
        authors = listOf("Ada Lovelace", "Alan Turing"),
        language = "en",
        publisher = "O'Reilly Media",
        chapters = listOf(
            EpubChapter("ch1", "chapter1.xhtml", "Intro", "<html><body><p>One</p></body></html>"),
            EpubChapter("ch2", "chapter2.xhtml", "Deep & Wide", "<html><body><p>Two</p></body></html>"),
        ),
        resources = listOf(
            EpubResource("style.css", "body{margin:0}".encodeToByteArray(), "text/css"),
            EpubResource("images/cover.jpg", byteArrayOf(1, 2, 3, 4, 5), "image/jpeg"),
        ),
        coverPath = "images/cover.jpg",
    )

    private fun readEntries(bytes: ByteArray): List<Pair<ZipEntry, ByteArray>> {
        val result = mutableListOf<Pair<ZipEntry, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                result += entry to zis.readBytes()
                entry = zis.nextEntry
            }
        }
        return result
    }

    @Test
    fun `produces a JVM-readable archive with mimetype first and stored`() {
        val entries = readEntries(EpubAssembler.assemble(sampleBook()))

        val first = entries.first()
        assertEquals("mimetype", first.first.name)
        assertEquals("mimetype must be STORED", ZipEntry.STORED.toLong(), first.first.method.toLong())
        assertEquals("application/epub+zip", first.second.decodeToString())
    }

    @Test
    fun `contains container, opf, nav, chapters and resources with intact bytes`() {
        val entries = readEntries(EpubAssembler.assemble(sampleBook())).associate { it.first.name to it.second }

        assertNotNull(entries["META-INF/container.xml"])
        assertTrue(entries["META-INF/container.xml"]!!.decodeToString().contains("OEBPS/content.opf"))

        val opf = entries["OEBPS/content.opf"]!!.decodeToString()
        // XML-escaped title survives.
        assertTrue(opf.contains("Deep &amp; &lt;Wide&gt; &quot;Kotlin&quot;"))
        assertTrue(opf.contains("Ada Lovelace"))
        assertTrue(opf.contains("""properties="cover-image""""))
        assertTrue(opf.contains("""<itemref idref="ch1"/>"""))

        assertNotNull(entries["OEBPS/nav.xhtml"])
        assertEquals(
            "<html><body><p>One</p></body></html>",
            entries["OEBPS/chapter1.xhtml"]!!.decodeToString(),
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), entries["OEBPS/images/cover.jpg"])
        assertEquals("body{margin:0}", entries["OEBPS/style.css"]!!.decodeToString())
    }

    @Test
    fun `crc32 matches the JVM implementation`() {
        val data = "The quick brown fox jumps over the lazy dog".encodeToByteArray()
        val expected = java.util.zip.CRC32().apply { update(data) }.value.toInt()
        assertEquals(expected, EpubZipWriter.crc32(data))
    }

    @Test
    fun `assembly is deterministic`() {
        assertArrayEquals(EpubAssembler.assemble(sampleBook()), EpubAssembler.assemble(sampleBook()))
    }
}
