package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadaloudTrackTest {

    private val clips = listOf(
        MediaOverlayClip("c1#s1", "c1.mp3", 0.0, 2.0),
        MediaOverlayClip("c1#s2", "c1.mp3", 2.0, 5.0),
        MediaOverlayClip("c1#s3", "c1.mp3", 5.0, 9.0),
        MediaOverlayClip("c2#s1", "c2.mp3", 0.0, 4.0),
    )
    private val track = ReadaloudTrack(clips)

    @Test
    fun `active clip is the one whose half-open range contains the position`() {
        assertEquals(clips[1], track.activeClipAt("c1.mp3", 2.0))
        assertEquals(clips[1], track.activeClipAt("c1.mp3", 4.999))
        assertEquals(clips[2], track.activeClipAt("c1.mp3", 5.0))
    }

    @Test
    fun `active clip is scoped to the matching audio file`() {
        assertEquals(clips[3], track.activeClipAt("c2.mp3", 1.0))
    }

    @Test
    fun `no active clip when position falls outside every range`() {
        assertNull(track.activeClipAt("c1.mp3", 100.0))
        assertNull(track.activeClipAt("missing.mp3", 1.0))
    }

    @Test
    fun `clipForFragment finds the entry point for play-from-here`() {
        assertEquals(clips[2], track.clipForFragment("c1#s3"))
        assertNull(track.clipForFragment("nope#x"))
    }

    @Test
    fun `indexOfFragment supports ordering decisions`() {
        assertEquals(2, track.indexOfFragment("c1#s3"))
        assertEquals(-1, track.indexOfFragment("nope#x"))
    }
}
