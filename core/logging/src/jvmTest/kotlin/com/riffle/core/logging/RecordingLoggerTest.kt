package com.riffle.core.logging

import com.riffle.core.logging.RecordingLogger.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingLoggerTest {

    @Test
    fun recordsLevelChannelAndMessage() {
        val logger = RecordingLogger()
        logger.d(LogChannel.Readaloud) { "hello d" }
        logger.w(LogChannel.Audiobook) { "hello w" }
        logger.e(LogChannel.Handoff) { "hello e" }

        val all = logger.records
        assertEquals(3, all.size)
        assertEquals(Level.D, all[0].level)
        assertEquals(LogChannel.Readaloud, all[0].channel)
        assertEquals("hello d", all[0].message)
        assertNull(all[0].throwable)
        assertEquals(Level.W, all[1].level)
        assertEquals(LogChannel.Audiobook, all[1].channel)
        assertEquals(Level.E, all[2].level)
        assertEquals(LogChannel.Handoff, all[2].channel)
    }

    @Test
    fun throwableIsCapturedWhenPassed() {
        val logger = RecordingLogger()
        val boom = IllegalStateException("boom")
        logger.e(LogChannel.Readaloud, boom) { "blew up" }

        val record = logger.records.single()
        assertSame(boom, record.throwable)
    }

    @Test
    fun recordsByChannelFilters() {
        val logger = RecordingLogger()
        logger.d(LogChannel.Readaloud) { "a" }
        logger.d(LogChannel.Audiobook) { "b" }
        logger.d(LogChannel.Readaloud) { "c" }

        val ra = logger.records(LogChannel.Readaloud)
        assertEquals(listOf("a", "c"), ra.map { it.message })
    }

    @Test
    fun clearEmptiesRecords() {
        val logger = RecordingLogger()
        logger.d(LogChannel.Readaloud) { "x" }
        logger.clear()
        assertTrue(logger.records.isEmpty())
    }

    @Test
    fun msgLambdaIsEvaluatedExactlyOncePerCall() {
        val logger = RecordingLogger()
        var calls = 0
        logger.d(LogChannel.Readaloud) {
            calls++
            "msg #$calls"
        }
        assertEquals(1, calls)
        assertEquals("msg #1", logger.records.single().message)
    }
}
