package com.riffle.core.data

import com.riffle.core.domain.IosZipArchive
import com.riffle.core.domain.IosZipWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the iOS Readaloud sidecar (ADR 0040) — the `/synced` bundle repackaged without its audio.
 * The sidecar must remain a readable, spec-valid ZIP (it is consumed as an audio-free EPUB), so
 * these assert both the filtering contract Android's ReadaloudSidecarReader defines and that the
 * bytes this writer produces can be read back.
 */
class IosReadaloudSidecarTest {

    private fun bundle(vararg entries: Pair<String, ByteArray>) = IosZipWriter.write(entries.toList())

    private val smilXml =
        """<smil xmlns="http://www.w3.org/ns/SMIL" version="3.0"><body><seq>""" +
            """<par><text src="../text/c1.xhtml#s1"/><audio src="../audio/c1.mp3" clipBegin="0s" clipEnd="2s"/></par>""" +
            """</seq></body></smil>"""

    private fun alignedBundle() = bundle(
        "OEBPS/smil/part0001.smil" to smilXml.encodeToByteArray(),
        "OEBPS/text/c1.xhtml" to "<html><body><p id=\"s1\">Hello</p></body></html>".encodeToByteArray(),
        "OEBPS/audio/c1.mp3" to ByteArray(4096) { 7 },
        "OEBPS/audio/c2.m4b" to ByteArray(2048) { 8 },
    )

    @Test
    fun `sidecar drops every audio entry and keeps the rest`() {
        val sidecar = IosReadaloudSidecarReader.read(alignedBundle())

        assertNotNull(sidecar)
        val names = IosZipArchive(sidecar).entryNames()
        assertEquals(listOf("OEBPS/smil/part0001.smil", "OEBPS/text/c1.xhtml").sorted(), names.sorted())
        assertFalse(names.any { it.endsWith(".mp3") || it.endsWith(".m4b") }, "audio must not be copied")
    }

    @Test
    fun `sidecar is far smaller than the bundle it came from`() {
        val full = alignedBundle()
        val sidecar = IosReadaloudSidecarReader.read(full)!!

        assertTrue(sidecar.size < full.size / 2, "sidecar ${sidecar.size} should be far under bundle ${full.size}")
    }

    @Test
    fun `sidecar content is byte-identical to the bundle's non-audio entries`() {
        val sidecar = IosReadaloudSidecarReader.read(alignedBundle())!!

        val archive = IosZipArchive(sidecar)
        assertEquals(smilXml, archive.readEntryAsText("OEBPS/smil/part0001.smil"))
        assertEquals("<html><body><p id=\"s1\">Hello</p></body></html>", archive.readEntryAsText("OEBPS/text/c1.xhtml"))
    }

    @Test
    fun `an unaligned bundle with no smil yields no sidecar`() {
        val unaligned = bundle(
            "OEBPS/text/c1.xhtml" to "<html/>".encodeToByteArray(),
            "OEBPS/audio/c1.mp3" to ByteArray(1024),
        )

        assertNull(IosReadaloudSidecarReader.read(unaligned), "no SMIL means Storyteller has not aligned the book")
    }

    @Test
    fun `the readaloud track parses out of a sidecar exactly as out of the full bundle`() {
        val full = alignedBundle()
        val sidecar = IosReadaloudSidecarReader.read(full)!!

        val fromBundle = IosMediaOverlayReader.readTrack(full)
        val fromSidecar = IosMediaOverlayReader.readTrack(sidecar)

        assertEquals(fromBundle.clips, fromSidecar.clips, "the sidecar must be a drop-in for the bundle")
    }
}
