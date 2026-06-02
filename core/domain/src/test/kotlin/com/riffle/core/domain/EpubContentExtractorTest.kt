package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubContentExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Build a minimal but structurally-valid EPUB 3 (with a media overlay) in memory. */
    private fun buildEpub(): ByteArray {
        val container = """
            <?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent()
        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <manifest>
                <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml" media-overlay="c1smil"/>
                <item id="c2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
                <item id="c1smil" href="chapter1.smil" media-type="application/smil+xml"/>
              </manifest>
              <spine>
                <itemref idref="c1"/>
                <itemref idref="c2"/>
              </spine>
            </package>
        """.trimIndent()
        val chapter1 = "<html><body><p id=\"s1\">Hello there.</p></body></html>"
        val chapter2 = "<html><body><p>Chapter two.</p></body></html>"
        val smil = """
            <?xml version="1.0"?>
            <smil xmlns="http://www.w3.org/ns/SMIL" version="3.0">
              <body><par><text src="chapter1.xhtml#s1"/><audio src="ch1.mp3" clipBegin="0s" clipEnd="2.5s"/></par></body>
            </smil>
        """.trimIndent()

        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
            }
            put("META-INF/container.xml", container)
            put("OEBPS/content.opf", opf)
            put("OEBPS/chapter1.xhtml", chapter1)
            put("OEBPS/chapter2.xhtml", chapter2)
            put("OEBPS/chapter1.smil", smil)
        }
        return bos.toByteArray()
    }

    @Test
    fun `extracts spine chapters in order with their hrefs and html`() {
        val extracted = EpubContentExtractor.extract(buildEpub())!!

        assertEquals(listOf("chapter1.xhtml", "chapter2.xhtml"), extracted.chapters.map { it.href })
        assertEquals(12L, EpubTextChars.countReadableChars(extracted.chapters[0].html)) // "Hello there."
    }

    @Test
    fun `extracts media-overlay SMIL clips referencing chapter fragments`() {
        val extracted = EpubContentExtractor.extract(buildEpub())!!

        assertEquals(
            listOf(MediaOverlayClip("chapter1.xhtml#s1", "ch1.mp3", clipBeginSec = 0.0, clipEndSec = 2.5)),
            extracted.smilClips,
        )
    }

    @Test
    fun `returns null for bytes that are not a valid EPUB`() {
        assertNull(EpubContentExtractor.extract("not a zip".toByteArray()))
    }

    @Test
    fun `extract from file reads only the text entries, ignoring large audio`() {
        // A synced bundle (ADR 0023) carries hundreds of MB of audio. The file overload must read
        // chapters straight from the zip without materialising every entry (which would OOM), and
        // produce the same result as the in-memory extract of the text-only EPUB.
        val expected = EpubContentExtractor.extract(buildEpub())!!

        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            // Stored (uncompressed) so the on-disk file genuinely contains the bytes.
            zip.setMethod(ZipOutputStream.DEFLATED)
            fun put(name: String, content: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
            }
            // Reuse the valid text EPUB's entries, then append a big "audio" blob.
            java.util.zip.ZipInputStream(buildEpub().inputStream()).use { src ->
                var e = src.nextEntry
                while (e != null) { put(e.name, src.readBytes()); e = src.nextEntry }
            }
            put("OEBPS/ch1.mp3", ByteArray(8 * 1024 * 1024))
        }
        val file = tmp.newFile("with-audio.epub").apply { writeBytes(bos.toByteArray()) }

        val fromFile = EpubContentExtractor.extract(file)!!

        assertEquals(expected.chapters, fromFile.chapters)
        assertEquals(expected.smilClips, fromFile.smilClips)
    }
}
