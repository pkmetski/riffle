package com.riffle.core.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bounded ring buffer of recent log entries so an in-app debug screen can display them
 * without adb. [AndroidLogger] appends every emission here in addition to forwarding to
 * `android.util.Log`.
 *
 * Thread-safe: writes synchronise on [lock]. Reads take an immutable snapshot via the
 * exposed [StateFlow] — subscribers receive the current buffer contents on collect and a
 * fresh snapshot after every append.
 */
@Singleton
class InMemoryLogBuffer @Inject constructor() {

    data class Entry(
        val timestampMs: Long,
        val level: Level,
        val channel: LogChannel,
        val message: String,
        val throwableSummary: String?,
    ) {
        enum class Level { D, W, E }
    }

    private val lock = Any()
    private val ring = ArrayDeque<Entry>(CAPACITY)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun append(entry: Entry) {
        val snapshot: List<Entry> = synchronized(lock) {
            if (ring.size >= CAPACITY) ring.removeFirst()
            ring.addLast(entry)
            ring.toList()
        }
        _entries.value = snapshot
    }

    fun snapshot(): List<Entry> = synchronized(lock) { ring.toList() }

    fun clear() {
        synchronized(lock) { ring.clear() }
        _entries.value = emptyList()
    }

    companion object {
        const val CAPACITY: Int = 2000
    }
}
