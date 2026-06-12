package com.riffle.core.data

import com.riffle.core.domain.MediaOverlayClip
import com.riffle.core.domain.ReadaloudTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class BundleAudiobookSessionBuilderTest {

    private val bundle = File("/tmp/bundle.epub")

    private fun clip(href: String, audio: String, begin: Double, end: Double) =
        MediaOverlayClip(textFragmentRef = href, audioSrc = audio, clipBeginSec = begin, clipEndSec = end)

    @Test
    fun `builds one span per distinct audio file with cumulative offsets`() {
        val track = ReadaloudTrack(
            listOf(
                clip("c1.xhtml#s0", "audio/0.mp3", 0.0, 30.0),
                clip("c1.xhtml#s1", "audio/0.mp3", 30.0, 60.0),
                clip("c2.xhtml#s0", "audio/1.mp3", 0.0, 45.0),
            ),
        )

        val session = buildBundleAudiobookSession(track, bundle)!!

        assertEquals(listOf("audio/0.mp3", "audio/1.mp3"), session.trackUrls)
        assertEquals(2, session.tracks.size)
        assertEquals(0.0, session.tracks[0].startOffsetSec, 1e-9)
        assertEquals(60.0, session.tracks[0].durationSec, 1e-9)
        assertEquals(60.0, session.tracks[1].startOffsetSec, 1e-9)
        assertEquals(45.0, session.tracks[1].durationSec, 1e-9)
        assertEquals(105.0, session.timeline.durationSec, 1e-9)
        assertEquals(bundle, session.localZipFile)
        assertEquals(0.0, session.serverCurrentTimeSec, 1e-9)
        assertEquals(0L, session.serverLastUpdate)
    }

    @Test
    fun `derives one chapter per distinct chapter href with boundaries on the global timeline`() {
        val track = ReadaloudTrack(
            listOf(
                clip("c1.xhtml#s0", "audio/0.mp3", 0.0, 30.0),
                clip("c2.xhtml#s0", "audio/0.mp3", 30.0, 60.0),
                clip("c2.xhtml#s1", "audio/1.mp3", 0.0, 45.0),
            ),
        )

        val chapters = buildBundleAudiobookSession(track, bundle)!!.timeline.chapters

        assertEquals(2, chapters.size)
        assertEquals(0.0, chapters[0].startSec, 1e-9)
        assertEquals(30.0, chapters[0].endSec, 1e-9)
        assertEquals(30.0, chapters[1].startSec, 1e-9)
        assertEquals(105.0, chapters[1].endSec, 1e-9)
        assertEquals("Chapter 1", chapters[0].title)
        assertEquals("Chapter 2", chapters[1].title)
    }

    @Test
    fun `returns null when the track has no clips`() {
        assertNull(buildBundleAudiobookSession(ReadaloudTrack(emptyList()), bundle))
    }
}
