package com.riffle.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryLogBufferTest {

    private fun entry(i: Int) = InMemoryLogBuffer.Entry(
        timestampMs = i.toLong(),
        level = InMemoryLogBuffer.Entry.Level.D,
        channel = LogChannel.ReaderDecoration,
        message = "m$i",
        throwableSummary = null,
    )

    @Test
    fun appendPreservesOrderAndExposesViaSnapshot() {
        val buf = InMemoryLogBuffer()
        buf.append(entry(1))
        buf.append(entry(2))
        buf.append(entry(3))
        assertEquals(listOf("m1", "m2", "m3"), buf.snapshot().map { it.message })
    }

    @Test
    fun ringEvictsOldestAtCapacity() {
        val buf = InMemoryLogBuffer()
        repeat(InMemoryLogBuffer.CAPACITY + 5) { buf.append(entry(it)) }
        val snap = buf.snapshot()
        assertEquals(InMemoryLogBuffer.CAPACITY, snap.size)
        // Oldest 5 evicted → first surviving entry has timestampMs == 5.
        assertEquals(5L, snap.first().timestampMs)
        assertEquals((InMemoryLogBuffer.CAPACITY + 4).toLong(), snap.last().timestampMs)
    }

    @Test
    fun clearEmptiesBufferAndStateFlow() {
        val buf = InMemoryLogBuffer()
        buf.append(entry(1))
        buf.clear()
        assertTrue(buf.snapshot().isEmpty())
        assertTrue(buf.entries.value.isEmpty())
    }

    @Test
    fun entriesStateFlowEmitsAppendedSnapshots() {
        val buf = InMemoryLogBuffer()
        assertTrue(buf.entries.value.isEmpty())
        buf.append(entry(1))
        assertEquals(1, buf.entries.value.size)
        buf.append(entry(2))
        assertEquals(2, buf.entries.value.size)
    }

    @Test
    fun appendStampsMonotonicallyIncreasingUniqueSeqEvenForIdenticalEntries() {
        // Regression: DebugLogScreen's LazyColumn crashed with "Key was already used" when
        // two log entries hashed to the same value (same timestamp/level/channel/message).
        // Every appended entry must carry a distinct seq so it can serve as the LazyColumn key.
        val buf = InMemoryLogBuffer()
        val duplicate = InMemoryLogBuffer.Entry(
            timestampMs = 42L,
            level = InMemoryLogBuffer.Entry.Level.D,
            channel = LogChannel.ReaderDecoration,
            message = "same",
            throwableSummary = null,
        )
        repeat(3) { buf.append(duplicate) }
        val seqs = buf.snapshot().map { it.seq }
        assertEquals(3, seqs.toSet().size)
        assertEquals(seqs.sorted(), seqs)
    }

    @Test
    fun snapshotIsIndependentOfSubsequentAppends() {
        val buf = InMemoryLogBuffer()
        buf.append(entry(1))
        val snap = buf.snapshot()
        buf.append(entry(2))
        assertEquals(1, snap.size)
    }
}
