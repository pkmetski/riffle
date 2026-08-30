package com.riffle.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidLoggerBufferTest {

    private val sinkCalls = mutableListOf<Triple<InMemoryLogBuffer.Entry.Level, String, String>>()
    private val sink = AndroidLogger.LogcatSink { level, tag, msg, _ ->
        sinkCalls += Triple(level, tag, msg)
    }
    private val clock = AndroidLogger.Clock { 42L }

    @Test
    fun `d appends to buffer and logcat with matching level channel message`() {
        val buffer = InMemoryLogBuffer()
        val logger = AndroidLogger(buffer, sink, clock)

        logger.d(LogChannel.ReaderDecoration) { "hi" }

        val snap = buffer.snapshot()
        assertEquals(1, snap.size)
        assertEquals(InMemoryLogBuffer.Entry.Level.D, snap[0].level)
        assertEquals(LogChannel.ReaderDecoration, snap[0].channel)
        assertEquals("hi", snap[0].message)
        assertEquals(42L, snap[0].timestampMs)
        assertNull(snap[0].throwableSummary)

        assertEquals(1, sinkCalls.size)
        assertEquals(InMemoryLogBuffer.Entry.Level.D, sinkCalls[0].first)
        assertEquals("RIFFLE_DECO", sinkCalls[0].second)
        assertEquals("hi", sinkCalls[0].third)
    }

    @Test
    fun `w and e produce their own levels`() {
        val buffer = InMemoryLogBuffer()
        val logger = AndroidLogger(buffer, sink, clock)

        logger.w(LogChannel.ReaderDecoration) { "warn" }
        logger.e(LogChannel.ReaderDecoration) { "err" }

        val snap = buffer.snapshot()
        assertEquals(
            listOf(InMemoryLogBuffer.Entry.Level.W, InMemoryLogBuffer.Entry.Level.E),
            snap.map { it.level },
        )
    }

    @Test
    fun `throwable is summarised as ClassName colon message`() {
        val buffer = InMemoryLogBuffer()
        val logger = AndroidLogger(buffer, sink, clock)

        logger.e(LogChannel.ReaderDecoration, t = IllegalStateException("boom")) { "x" }

        assertEquals("IllegalStateException: boom", buffer.snapshot().single().throwableSummary)
    }
}
