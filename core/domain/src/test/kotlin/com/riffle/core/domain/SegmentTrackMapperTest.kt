package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Maps a Media Overlay clip's per-segment audio offset onto the ABS audiobook's track timeline
 * (ADR 0028). Storyteller re-splits the source audio into its own segments; this reconciles those
 * segments with ABS's tracks for the three shapes the dev-server survey actually produced.
 */
class SegmentTrackMapperTest {

    // Two Storyteller segments: c1.mp3 (duration 5s), c2.mp3 (duration 3s).
    private val clips = listOf(
        MediaOverlayClip("c1#s1", "c1.mp3", 0.0, 2.0),
        MediaOverlayClip("c1#s2", "c1.mp3", 2.0, 5.0),
        MediaOverlayClip("c2#s1", "c2.mp3", 0.0, 3.0),
    )

    @Test
    fun `segments map one-to-one to ABS tracks of equal duration`() {
        val map = SegmentTrackMapper.align(clips, absTrackDurationsSec = listOf(5.0, 3.0))!!
        assertEquals(AbsAudioPosition(0, 2.0), map.positionFor(clips[1])) // c1#s2 → track 0 @ 2s
        assertEquals(AbsAudioPosition(1, 0.0), map.positionFor(clips[2])) // c2#s1 → track 1 @ 0s
    }

    @Test
    fun `a dropped leading ABS track (intro) is skipped`() {
        // ABS has a 1s publisher intro Storyteller dropped; segments align to tracks 1..2.
        val map = SegmentTrackMapper.align(clips, absTrackDurationsSec = listOf(1.0, 5.0, 3.0))!!
        assertEquals(AbsAudioPosition(1, 0.0), map.positionFor(clips[0])) // c1#s1 → track 1 @ 0s
        assertEquals(AbsAudioPosition(2, 0.0), map.positionFor(clips[2])) // c2#s1 → track 2 @ 0s
    }

    @Test
    fun `a single ABS file maps segments by concatenated global time`() {
        val map = SegmentTrackMapper.align(clips, absTrackDurationsSec = listOf(8.0))!!
        assertEquals(AbsAudioPosition(0, 2.0), map.positionFor(clips[1])) // c1#s2 → 0+2 = 2s
        assertEquals(AbsAudioPosition(0, 5.0), map.positionFor(clips[2])) // c2#s1 → 5+0 = 5s
    }

    @Test
    fun `returns null when the audio durations cannot be reconciled`() {
        // Segments total 8s but ABS only has 3s — not the same recording.
        assertNull(SegmentTrackMapper.align(clips, absTrackDurationsSec = listOf(3.0)))
    }
}
