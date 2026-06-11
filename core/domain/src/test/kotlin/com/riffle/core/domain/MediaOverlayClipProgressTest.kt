package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [MediaOverlayClip.progressAt] — the within-sentence elapsed fraction the reader follows to turn the
 * page when a sentence spans more than one paginated column.
 */
class MediaOverlayClipProgressTest {

    private fun clip(begin: Double, end: Double) =
        MediaOverlayClip(textFragmentRef = "c1.xhtml#s0", audioSrc = "c1.mp3", clipBeginSec = begin, clipEndSec = end)

    @Test fun `reports the fraction elapsed within the clip`() {
        val c = clip(begin = 10.0, end = 14.0) // 4s clip
        assertEquals(0.0, c.progressAt(10.0), 1e-9)
        assertEquals(0.25, c.progressAt(11.0), 1e-9)
        assertEquals(0.5, c.progressAt(12.0), 1e-9)
        assertEquals(1.0, c.progressAt(14.0), 1e-9)
    }

    @Test fun `clamps a position before the clip to 0`() {
        assertEquals(0.0, clip(10.0, 14.0).progressAt(5.0), 1e-9)
    }

    @Test fun `clamps a position past the clip to 1`() {
        // The boundary instant belongs to the next clip, but a poll can land just past clipEnd while
        // this is still the active clip — clamp rather than overshoot past the last column.
        assertEquals(1.0, clip(10.0, 14.0).progressAt(99.0), 1e-9)
    }

    @Test fun `a zero-duration clip reports 0`() {
        assertEquals(0.0, clip(10.0, 10.0).progressAt(10.0), 1e-9)
    }

    @Test fun `a negative-duration clip reports 0`() {
        assertEquals(0.0, clip(14.0, 10.0).progressAt(12.0), 1e-9)
    }
}
