package com.riffle.app.feature.settings.debug

import com.riffle.core.logging.InMemoryLogBuffer
import com.riffle.core.logging.LogChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugLogDisplayOrderTest {

    @Test
    fun `newest entry is first`() {
        val oldest = entry(seq = 1L, timestampMs = 100L, message = "oldest")
        val mid = entry(seq = 2L, timestampMs = 200L, message = "mid")
        val newest = entry(seq = 3L, timestampMs = 300L, message = "newest")

        val displayed = debugLogDisplayOrder(listOf(oldest, mid, newest))

        assertEquals(listOf(newest, mid, oldest), displayed)
    }

    @Test
    fun `empty input yields empty output`() {
        assertEquals(emptyList<InMemoryLogBuffer.Entry>(), debugLogDisplayOrder(emptyList()))
    }

    private fun entry(seq: Long, timestampMs: Long, message: String) = InMemoryLogBuffer.Entry(
        timestampMs = timestampMs,
        level = InMemoryLogBuffer.Entry.Level.D,
        channel = LogChannel.Readaloud,
        message = message,
        throwableSummary = null,
        seq = seq,
    )
}
