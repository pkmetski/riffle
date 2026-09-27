package com.riffle.core.catalog

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class CatalogProgressTest {

    private fun progress(
        ebookProgress: Float = 0f,
        audioCurrentTime: Double = 0.0,
        audioDuration: Double = 0.0,
        isFinished: Boolean = false,
    ) = CatalogProgress(
        itemId = "item-1",
        ebookProgress = ebookProgress,
        audioCurrentTime = audioCurrentTime,
        audioDuration = audioDuration,
        isFinished = isFinished,
        lastUpdate = 1_000L,
    )

    @Test fun `finished pins to 1 regardless of positions`() {
        assertEquals(1f, progress(isFinished = true, audioCurrentTime = 1.0, audioDuration = 100.0).unifiedLibraryFraction())
    }

    @Test fun `the higher of ebook and audio fraction wins when both are present`() {
        // Audio at 59% beats ebook at 30%: the larger value wins so a stale small ebook scalar
        // on an audiobook does not override the real audio position.
        assertEquals(0.59f, progress(ebookProgress = 0.3f, audioCurrentTime = 59.0, audioDuration = 100.0).unifiedLibraryFraction()!!, 0.0001f)
        // Ebook at 80% beats audio at 59%: the larger value still wins.
        assertEquals(0.8f, progress(ebookProgress = 0.8f, audioCurrentTime = 59.0, audioDuration = 100.0).unifiedLibraryFraction()!!, 0.0001f)
    }

    @Test fun `tiny stale ebook scalar from abs bulk endpoint does not override real audio position`() {
        // Regression: ABS's bulk /api/me/progress can return ebookProgress=0.002 for an
        // audiobook while the audio fraction (currentTime/duration) is 47%. The old ebook-first
        // priority made unifiedLibraryFraction() return 0.2% for the library bar while the
        // per-item endpoint (used by refreshItemProgress) returned 0 ebook + 47% audio — causing
        // a permanent ping-pong between library (0.2%) and detail (47%).
        assertEquals(0.47f, progress(ebookProgress = 0.002f, audioCurrentTime = 47.0, audioDuration = 100.0).unifiedLibraryFraction()!!, 0.0001f)
    }

    @Test fun `ebook fraction used when no audio data is present`() {
        assertEquals(0.3f, progress(ebookProgress = 0.3f).unifiedLibraryFraction())
    }

    @Test fun `audio fraction derived from currentTime over duration when ebook progress is absent`() {
        assertEquals(0.59f, progress(audioCurrentTime = 59.0, audioDuration = 100.0).unifiedLibraryFraction()!!, 0.0001f)
    }

    @Test fun `audio fraction clamps to 1 when currentTime exceeds duration`() {
        assertEquals(1f, progress(audioCurrentTime = 101.0, audioDuration = 100.0).unifiedLibraryFraction())
    }

    @Test fun `empty payload yields null so callers skip the write`() {
        assertNull(progress().unifiedLibraryFraction())
    }

    @Test fun `audio position without a duration yields null`() {
        assertNull(progress(audioCurrentTime = 42.0).unifiedLibraryFraction())
    }

    @Test fun `audio position of zero with known duration yields null so stale bulk endpoint cannot overwrite valid progress`() {
        // After mark-as-unread the sweeper pushes currentTime=0 to ABS. The bulk endpoint then
        // returns currentTime=0, duration>0. If that returned 0.0f (not null), the post-loop would
        // write 0% to library_items.readingProgress, overwriting any value that a concurrent
        // refreshItemProgress had just written. Returning null skips the write.
        assertNull(progress(audioCurrentTime = 0.0, audioDuration = 100.0).unifiedLibraryFraction())
    }
}
