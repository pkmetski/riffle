package com.riffle.core.logging

/**
 * In-memory [Logger] for tests. Captures every emission so tests can assert on level, channel,
 * message, and (optional) throwable.
 *
 * NOT thread-safe. Tests that emit from multiple threads or coroutines must synchronise
 * externally or convert to a snapshot before asserting.
 */
class RecordingLogger : Logger {

    enum class Level { D, W, E }

    data class Record(
        val level: Level,
        val channel: LogChannel,
        val message: String,
        val throwable: Throwable?,
    )

    private val mutable = mutableListOf<Record>()

    val records: List<Record>
        get() = mutable.toList()

    fun records(channel: LogChannel): List<Record> = mutable.filter { it.channel == channel }

    fun clear() {
        mutable.clear()
    }

    override fun d(channel: LogChannel, t: Throwable?, msg: () -> String) {
        mutable += Record(Level.D, channel, msg(), t)
    }

    override fun w(channel: LogChannel, t: Throwable?, msg: () -> String) {
        mutable += Record(Level.W, channel, msg(), t)
    }

    override fun e(channel: LogChannel, t: Throwable?, msg: () -> String) {
        mutable += Record(Level.E, channel, msg(), t)
    }
}
