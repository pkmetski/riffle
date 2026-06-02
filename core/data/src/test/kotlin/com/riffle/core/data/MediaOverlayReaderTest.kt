package com.riffle.core.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MediaOverlayReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun epubWith(entries: Map<String, ByteArray>): File {
        val f = tmp.newFile("book.epub")
        ZipOutputStream(f.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return f
    }

    private fun smil(audioRel: String) = """
        <smil xmlns="http://www.w3.org/ns/SMIL" version="3.0"><body>
          <par><text src="../text/c1.xhtml#s1"/><audio src="$audioRel" clipBegin="0s" clipEnd="2s"/></par>
          <par><text src="../text/c1.xhtml#s2"/><audio src="$audioRel" clipBegin="2s" clipEnd="4s"/></par>
        </body></smil>
    """.trimIndent().toByteArray()

    @Test fun `builds a track with refs and audio paths resolved relative to the smil entry`() {
        val epub = epubWith(
            mapOf(
                "OEBPS/smil/c1.smil" to smil("../audio/c1.mp3"),
                "OEBPS/audio/c1.mp3" to ByteArray(10),
                "OEBPS/text/c1.xhtml" to ByteArray(5),
            ),
        )

        val track = MediaOverlayReader.readTrack(epub)

        assertEquals(2, track.clips.size)
        assertEquals("OEBPS/text/c1.xhtml#s1", track.clips[0].textFragmentRef)
        assertEquals("OEBPS/audio/c1.mp3", track.clips[0].audioSrc)
    }

    @Test fun `concatenates multiple smil entries in name order`() {
        val epub = epubWith(
            mapOf(
                "OEBPS/smil/c2.smil" to smil("../audio/c2.mp3"),
                "OEBPS/smil/c1.smil" to smil("../audio/c1.mp3"),
                "OEBPS/audio/c1.mp3" to ByteArray(10),
                "OEBPS/audio/c2.mp3" to ByteArray(10),
            ),
        )

        val track = MediaOverlayReader.readTrack(epub)

        assertEquals(4, track.clips.size)
        // c1 entries precede c2 entries (sorted by smil entry name)
        assertEquals("OEBPS/audio/c1.mp3", track.clips[0].audioSrc)
        assertEquals("OEBPS/audio/c2.mp3", track.clips[2].audioSrc)
    }

    @Test fun `no smil entries yields an empty track`() {
        val epub = epubWith(mapOf("OEBPS/text/c1.xhtml" to ByteArray(5)))
        assertEquals(0, MediaOverlayReader.readTrack(epub).clips.size)
    }
}
