package com.riffle.core.data

import com.riffle.core.network.NetworkAbsAudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * End-to-end assembly (ADR 0028): a sidecar (SMIL) + ABS tracks → a parsed track and clipped
 * streaming items, keyed by the segment path so the highlight machinery is reused.
 */
class StreamingSetupBuilderTest {

    private fun smil(audioRel: String, vararg clips: Triple<String, String, String>): ByteArray {
        val pars = clips.joinToString("\n") { (id, begin, end) ->
            """<par><text src="../text/$id"/><audio src="$audioRel" clipBegin="$begin" clipEnd="$end"/></par>"""
        }
        return """<smil xmlns="http://www.w3.org/ns/SMIL" version="3.0"><body>$pars</body></smil>""".toByteArray()
    }

    private fun sidecarFile(): File {
        val f = File.createTempFile("sidecar", ".epub")
        ZipOutputStream(f.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("OEBPS/smil/c1.smil"))
            zos.write(smil("../audio/c1.mp3", Triple("c1#s1", "0s", "2.5s"), Triple("c1#s2", "2.5s", "5s")))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("OEBPS/smil/c2.smil"))
            zos.write(smil("../audio/c2.mp3", Triple("c2#s1", "0s", "3s")))
            zos.closeEntry()
        }
        return f
    }

    @Test
    fun `builds the track and clipped items keyed by segment`() {
        val sidecar = sidecarFile()
        val tracks = listOf(
            NetworkAbsAudioTrack("ino-a", 1, 5.0),
            NetworkAbsAudioTrack("ino-b", 2, 3.0),
        )
        val setup = StreamingSetupBuilder().build(sidecar, tracks, "http://abs", "x")!!
        sidecar.delete()

        assertEquals(3, setup.track.clips.size)
        assertEquals(
            listOf(
                StreamingMediaItem("OEBPS/audio/c1.mp3", "http://abs/api/items/x/file/ino-a", 0, 5000),
                StreamingMediaItem("OEBPS/audio/c2.mp3", "http://abs/api/items/x/file/ino-b", 0, 3000),
            ),
            setup.items,
        )
    }

    @Test
    fun `returns null when ABS durations cannot be reconciled`() {
        val sidecar = sidecarFile()
        // ABS reports a single 99s track — nothing like the 5s + 3s segments.
        val setup = StreamingSetupBuilder().build(sidecar, listOf(NetworkAbsAudioTrack("ino-a", 1, 99.0)), "http://abs", "x")
        sidecar.delete()
        assertNull(setup)
    }
}
