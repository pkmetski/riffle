package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
