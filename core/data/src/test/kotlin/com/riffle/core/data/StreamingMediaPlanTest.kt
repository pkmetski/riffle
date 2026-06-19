package com.riffle.core.data

import com.riffle.core.domain.SegmentPlacement
import com.riffle.core.network.NetworkAbsAudioTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingMediaPlanTest {

    @Test
    fun `one-to-one segments become whole-track clipped items keyed by audioSrc`() {
        val items = StreamingMediaPlan.build(
            placements = listOf(
                SegmentPlacement("c1.mp3", 0, 0.0, 5.0),
                SegmentPlacement("c2.mp3", 1, 0.0, 3.0),
            ),
            tracks = listOf(
                NetworkAbsAudioTrack("ino-a", 1, 5.0),
                NetworkAbsAudioTrack("ino-b", 2, 3.0),
            ),
            baseUrl = "http://abs",
            itemId = "x",
            token = "tkn",
        )
        assertEquals(
            listOf(
                StreamingMediaItem("c1.mp3", "http://abs/api/items/x/file/ino-a?token=tkn", 0, 5000),
                StreamingMediaItem("c2.mp3", "http://abs/api/items/x/file/ino-b?token=tkn", 0, 3000),
            ),
            items,
        )
    }

    @Test
    fun `single-file segments point at the same track, clipped by offset`() {
        val items = StreamingMediaPlan.build(
            placements = listOf(
                SegmentPlacement("c1.mp3", 0, 0.0, 5.0),
                SegmentPlacement("c2.mp3", 0, 5.0, 3.0),
            ),
            tracks = listOf(NetworkAbsAudioTrack("ino-a", 1, 8.0)),
            baseUrl = "http://abs",
            itemId = "x",
            token = "tkn",
        )
        assertEquals(
            listOf(
                StreamingMediaItem("c1.mp3", "http://abs/api/items/x/file/ino-a?token=tkn", 0, 5000),
                StreamingMediaItem("c2.mp3", "http://abs/api/items/x/file/ino-a?token=tkn", 5000, 8000),
            ),
            items,
        )
    }
}
