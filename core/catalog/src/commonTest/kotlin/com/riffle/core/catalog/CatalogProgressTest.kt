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

    @Test fun `ebook progress wins when present`() {
        assertEquals(0.3f, progress(ebookProgress = 0.3f, audioCurrentTime = 59.0, audioDuration = 100.0).unifiedLibraryFraction())
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
}
