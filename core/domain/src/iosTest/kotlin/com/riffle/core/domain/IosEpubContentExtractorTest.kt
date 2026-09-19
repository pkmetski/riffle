package com.riffle.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * iOS coverage for the cross-EPUB index inputs (ADR 0023): the EPUB walk, the readable-character
 * counter and the checksum must all agree with their JVM counterparts, because an index built on
 * one platform is keyed and read by the other.
 */
class IosEpubContentExtractorTest {

    private fun epub(
        opfDir: String = "OEBPS",
        withOverlay: Boolean = true,
    ): ByteArray {
        // A root-level OPF has no directory prefix at all — "/content.opf" would be a malformed
        // entry name, not the empty-opfDir case this exercises.
        val prefix = if (opfDir.isEmpty()) "" else "$opfDir/"
        val container =
            """<?xml version="1.0"?><container><rootfiles>""" +
                """<rootfile full-path="${prefix}content.opf"/></rootfiles></container>"""
        val overlayAttr = if (withOverlay) """ media-overlay="smil1"""" else ""
        val opf = """<?xml version="1.0"?><package><manifest>""" +
            """<item id="c1" href="text/c1.xhtml"$overlayAttr/>""" +
            """<item id="c2" href="text/c2.xhtml"/>""" +
            """<item id="smil1" href="smil/c1.smil"/>""" +
            """</manifest><spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>"""
        val smil = """<smil xmlns="http://www.w3.org/ns/SMIL" version="3.0"><body><seq>""" +
            """<par><text src="../text/c1.xhtml#s1"/><audio src="../audio/c1.mp3" clipBegin="0s" clipEnd="3s"/></par>""" +
            """</seq></body></smil>"""

        return IosZipWriter.write(
            listOf(
                "META-INF/container.xml" to container.encodeToByteArray(),
                "${prefix}content.opf" to opf.encodeToByteArray(),
                "${prefix}text/c1.xhtml" to "<html><body><p id=\"s1\">Chapter one text</p></body></html>".encodeToByteArray(),
                "${prefix}text/c2.xhtml" to "<html><body><p>Chapter two</p></body></html>".encodeToByteArray(),
                "${prefix}smil/c1.smil" to smil.encodeToByteArray(),
            ),
        )
    }

    @Test
    fun `extracts spine chapters in reading order`() {
        val extracted = IosEpubContentExtractor.extract(epub())

        assertTrue(extracted != null)
        assertEquals(listOf("text/c1.xhtml", "text/c2.xhtml"), extracted.chapters.map { it.href })
        assertTrue(extracted.chapters[0].html.contains("Chapter one text"))
    }

    @Test
    fun `follows a spine item's media-overlay to its smil clips`() {
        val extracted = IosEpubContentExtractor.extract(epub())!!

        assertEquals(1, extracted.smilClips.size)
        assertEquals("../text/c1.xhtml#s1", extracted.smilClips.single().textFragmentRef)
        assertEquals(3.0, extracted.smilClips.single().clipEndSec, 0.0001)
    }

    @Test
    fun `a spine without overlays yields chapters and no clips`() {
        val extracted = IosEpubContentExtractor.extract(epub(withOverlay = false))!!

        assertEquals(2, extracted.chapters.size)
        assertTrue(extracted.smilClips.isEmpty())
    }

    @Test
    fun `resolves hrefs relative to the opf directory at the archive root`() {
        val extracted = IosEpubContentExtractor.extract(epub(opfDir = ""))

        assertTrue(extracted != null, "an OPF at the root must still resolve its spine")
        assertEquals(listOf("text/c1.xhtml", "text/c2.xhtml"), extracted.chapters.map { it.href })
    }

    @Test
    fun `returns null for bytes that are not an epub`() {
        assertNull(IosEpubContentExtractor.extract("not a zip".encodeToByteArray()))
    }

    @Test
    fun `readable char counting ignores markup and whitespace-only nodes`() {
        val html = "<html><body>\n  <p>Hello <em>world</em></p>\n  <img src=\"x.png\"/>\n</body></html>"

        // "Hello " (6) + "world" (5); the indentation text nodes and the image contribute nothing.
        assertEquals(11L, IosEpubTextChars.countReadableChars(html))
    }

    @Test
    fun `element progression is the readable chars before it over the chapter total`() {
        val html = "<html><body><p>AAAA</p><p id=\"mid\">BBBB</p></body></html>"

        assertEquals(0.5, IosEpubTextChars.progressionOfElementId(html, "mid")!!, 0.0001)
        assertNull(IosEpubTextChars.progressionOfElementId(html, "absent"))
    }

    @Test
    fun `checksum matches the published SHA-256 of a known input`() {
        // SHA-256("abc") — the standard test vector, so this pins the digest to the same value
        // java.security.MessageDigest produces on the JVM side.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            IosEpubChecksum.of("abc".encodeToByteArray()),
        )
    }

    @Test
    fun `checksum of empty input matches the published SHA-256`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            IosEpubChecksum.of(ByteArray(0)),
        )
    }

    @Test
    fun `cross-epub index aligns chapters by spine order`() {
        val abs = listOf("<html><body><p>AAAA</p></body></html>", "<html><body><p>BB</p></body></html>")
        val storyteller = listOf("<html><body><p>AAAAAAAA</p></body></html>", "<html><body><p>BBBB</p></body></html>")

        val index = CrossEpubIndexBuilder.build(abs, storyteller, IosEpubTextChars::countReadableChars)

        assertEquals(2, index.perChapter.size)
        assertEquals(4L, index.perChapter[0].absChars)
        assertEquals(8L, index.perChapter[0].storytellerChars)
    }
}
