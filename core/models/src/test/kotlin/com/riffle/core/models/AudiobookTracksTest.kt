package com.riffle.core.models

import org.junit.Assert.assertEquals
import org.junit.Test
import com.riffle.core.models.AudiobookTrackSpan
import com.riffle.core.models.AudiobookTracks

class AudiobookTracksTest {

    // Three tracks: [0,100), [100,300), [300,600) on the book timeline.
    private val tracks = listOf(
        AudiobookTrackSpan(0, 0.0, 100.0),
        AudiobookTrackSpan(1, 100.0, 200.0),
        AudiobookTrackSpan(2, 300.0, 300.0),
    )

    @Test
    fun `trackIndexAt finds the containing track`() {
        assertEquals(0, AudiobookTracks.trackIndexAt(0.0, tracks))
        assertEquals(0, AudiobookTracks.trackIndexAt(99.9, tracks))
        assertEquals(1, AudiobookTracks.trackIndexAt(100.0, tracks))
        assertEquals(1, AudiobookTracks.trackIndexAt(299.9, tracks))
        assertEquals(2, AudiobookTracks.trackIndexAt(300.0, tracks))
        assertEquals(2, AudiobookTracks.trackIndexAt(10_000.0, tracks)) // clamps to last
    }

    @Test
    fun `offsetInTrack is measured from the track start`() {
        assertEquals(0.0, AudiobookTracks.offsetInTrackSec(100.0, tracks), 0.0)
        assertEquals(50.0, AudiobookTracks.offsetInTrackSec(150.0, tracks), 0.0)
        assertEquals(25.0, AudiobookTracks.offsetInTrackSec(325.0, tracks), 0.0)
    }

    @Test
    fun `absolute and per-track positions round-trip`() {
        val absolute = 325.0
        val idx = AudiobookTracks.trackIndexAt(absolute, tracks)
        val off = AudiobookTracks.offsetInTrackSec(absolute, tracks)
        assertEquals(absolute, AudiobookTracks.absoluteSec(idx, off, tracks), 1e-9)
    }

    @Test
    fun `empty tracks degrade to identity`() {
        assertEquals(0, AudiobookTracks.trackIndexAt(42.0, emptyList()))
        assertEquals(42.0, AudiobookTracks.offsetInTrackSec(42.0, emptyList()), 0.0)
        assertEquals(42.0, AudiobookTracks.absoluteSec(0, 42.0, emptyList()), 0.0)
    }

    @Test
    fun `startPositionFor resumes mid-track at the right track and offset, not track 0`() {
        // The bug: opening at a saved mid-book position briefly played from track 0 / offset 0. The
        // resolved start must land on the containing track with the in-track offset in millis.
        val start = AudiobookTracks.startPositionFor(absoluteSec = 325.0, durationSec = 600.0, tracks = tracks)
        assertEquals(2, start.trackIndex)
        assertEquals(25_000L, start.offsetMs)
    }

    @Test
    fun `startPositionFor at a track boundary starts that track at offset 0`() {
        val start = AudiobookTracks.startPositionFor(absoluteSec = 100.0, durationSec = 600.0, tracks = tracks)
        assertEquals(1, start.trackIndex)
        assertEquals(0L, start.offsetMs)
    }

    @Test
    fun `startPositionFor from the very beginning is track 0 offset 0`() {
        val start = AudiobookTracks.startPositionFor(absoluteSec = 0.0, durationSec = 600.0, tracks = tracks)
        assertEquals(0, start.trackIndex)
        assertEquals(0L, start.offsetMs)
    }

    @Test
    fun `startPositionFor clamps a beyond-duration position to the last track`() {
        val start = AudiobookTracks.startPositionFor(absoluteSec = 10_000.0, durationSec = 600.0, tracks = tracks)
        assertEquals(2, start.trackIndex)
        assertEquals(300_000L, start.offsetMs) // clamped to 600s -> 300s into track 2
    }

    @Test
    fun `startPositionFor clamps a negative position to the start`() {
        val start = AudiobookTracks.startPositionFor(absoluteSec = -5.0, durationSec = 600.0, tracks = tracks)
        assertEquals(0, start.trackIndex)
        assertEquals(0L, start.offsetMs)
    }
}
