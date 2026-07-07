package com.riffle.core.logging

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [Logger]: forwards to [android.util.Log] using the channel's tag and
 * simultaneously appends to [InMemoryLogBuffer] so the in-app debug screen can display
 * the same emissions without adb.
 *
 * The [logcatSink] and [clock] seams exist so pure JVM tests can verify buffer appends
 * without touching `android.util.Log` (which is unmocked in unit tests).
 */
@Singleton
class AndroidLogger @Inject constructor(
    private val buffer: InMemoryLogBuffer,
) : Logger {

    fun interface LogcatSink { fun write(level: InMemoryLogBuffer.Entry.Level, tag: String, msg: String, t: Throwable?) }
    fun interface Clock { fun nowMs(): Long }

    private var logcatSink: LogcatSink = DefaultSink
    private var clock: Clock = SystemClock

    internal constructor(
        buffer: InMemoryLogBuffer,
        logcatSink: LogcatSink,
        clock: Clock,
    ) : this(buffer) {
        this.logcatSink = logcatSink
        this.clock = clock
    }

    override fun d(channel: LogChannel, t: Throwable?, msg: () -> String) =
        emit(InMemoryLogBuffer.Entry.Level.D, channel, t, msg)

    override fun w(channel: LogChannel, t: Throwable?, msg: () -> String) =
        emit(InMemoryLogBuffer.Entry.Level.W, channel, t, msg)

    override fun e(channel: LogChannel, t: Throwable?, msg: () -> String) =
        emit(InMemoryLogBuffer.Entry.Level.E, channel, t, msg)

    private inline fun emit(
        level: InMemoryLogBuffer.Entry.Level,
        channel: LogChannel,
        t: Throwable?,
        msg: () -> String,
    ) {
        val m = msg()
        logcatSink.write(level, channel.tag, m, t)
        buffer.append(
            InMemoryLogBuffer.Entry(
                timestampMs = clock.nowMs(),
                level = level,
                channel = channel,
                message = m,
                throwableSummary = t?.let { "${it::class.java.simpleName}: ${it.message}" },
            ),
        )
    }

    private object DefaultSink : LogcatSink {
        override fun write(level: InMemoryLogBuffer.Entry.Level, tag: String, msg: String, t: Throwable?) {
            when (level) {
                InMemoryLogBuffer.Entry.Level.D -> if (t != null) Log.d(tag, msg, t) else Log.d(tag, msg)
                InMemoryLogBuffer.Entry.Level.W -> if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
                InMemoryLogBuffer.Entry.Level.E -> if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
            }
        }
    }

    private object SystemClock : Clock { override fun nowMs(): Long = System.currentTimeMillis() }
}
