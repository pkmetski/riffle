package com.riffle.core.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Bounded ring buffer of recent log entries so an in-app debug screen can display them
 * without adb. [AndroidLogger] appends every emission here in addition to forwarding to
 * `android.util.Log`.
 *
 * Thread-safe via a lock-free CAS loop on an immutable snapshot list. Reads take a
 * consistent snapshot via the exposed [StateFlow] — subscribers receive the current buffer
 * contents on collect and a fresh snapshot after every append.
 */
@OptIn(ExperimentalAtomicApi::class)
class InMemoryLogBuffer constructor() {

    data class Entry(
        val timestampMs: Long,
        val level: Level,
        val channel: LogChannel,
        val message: String,
        val throwableSummary: String?,
        // Monotonic, process-wide unique id assigned in [append]. Callers may leave it at 0L;
        // the buffer stamps a fresh value on insertion so consumers (e.g. the debug log
        // LazyColumn) always have a stable, unique key even when two entries share
        // timestamp/level/channel/message.
        val seq: Long = 0L,
    ) {
        enum class Level { D, W, E }
    }

    private val ring = AtomicReference<List<Entry>>(emptyList())
    private val seqGen = AtomicLong(0L)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun append(entry: Entry) {
        val stamped = entry.copy(seq = seqGen.addAndFetch(1L))
        var snapshot: List<Entry>
        while (true) {
            val current = ring.load()
            val next = if (current.size >= CAPACITY) current.drop(1) + stamped else current + stamped
            if (ring.compareAndSet(current, next)) {
                snapshot = next
                break
            }
        }
        _entries.value = snapshot
    }

    fun snapshot(): List<Entry> = ring.load()

    fun clear() {
        ring.store(emptyList())
        _entries.value = emptyList()
    }

    companion object {
        const val CAPACITY: Int = 2000
    }
}
