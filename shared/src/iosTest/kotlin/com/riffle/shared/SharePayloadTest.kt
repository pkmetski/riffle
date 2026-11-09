package com.riffle.shared

import com.riffle.core.data.IosCrashReportRecorder
import com.riffle.core.logging.InMemoryLogBuffer
import com.riffle.core.logging.LogChannel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharePayloadTest {

    @Test
    fun debugLogFormatEntriesProducesNonEmptyText() {
        val entry = InMemoryLogBuffer.Entry(
            timestampMs = 1_000L,
            level = InMemoryLogBuffer.Entry.Level.D,
            channel = LogChannel.ReaderDecoration,
            message = "test message",
            throwableSummary = null,
        )
        val text = InMemoryLogBuffer.formatEntries(listOf(entry), channels = null)
        assertFalse(text.isEmpty())
        assertTrue(text.contains("test message"))
    }

    @Test
    fun debugLogFormatEntriesReturnsEmptyForEmptySnapshot() {
        val text = InMemoryLogBuffer.formatEntries(emptyList(), channels = null)
        assertTrue(text.isEmpty())
    }

    @Test
    fun crashReportContainsExpectedFields() {
        val e = RuntimeException("test crash")
        val report = IosCrashReportRecorder.report(e, 0L)
        assertTrue(report.contains("Riffle iOS crash report"))
        assertTrue(report.contains("test crash"))
        assertTrue(report.contains("RuntimeException"))
    }
}
