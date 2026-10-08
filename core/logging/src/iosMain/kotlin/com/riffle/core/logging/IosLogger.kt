package com.riffle.core.logging

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * iOS logger. Deliberately built on println, NOT NSLog: Kotlin/Native cannot pass a Kotlin
 * String through an Objective-C variadic call reliably — `NSLog("%@", msg)` hands `%@` a
 * non-NSString pointer and segfaults in `respondsToSelector` the first time a message is
 * actually logged (crashed Chitanka install, 2026-09-07). println reaches the Xcode console
 * and `simctl launch --console-pty` just the same.
 *
 * Additionally appends every emission to [InMemoryLogBuffer] so the in-app [IosDebugLogScreen]
 * can display log entries without needing a connected console.
 */
class IosLogger constructor(
    private val buffer: InMemoryLogBuffer,
) : Logger {

    override fun d(channel: LogChannel, t: Throwable?, msg: () -> String) =
        emit("D", InMemoryLogBuffer.Entry.Level.D, channel, t, msg)

    override fun w(channel: LogChannel, t: Throwable?, msg: () -> String) =
        emit("W", InMemoryLogBuffer.Entry.Level.W, channel, t, msg)

    override fun e(channel: LogChannel, t: Throwable?, msg: () -> String) =
        emit("E", InMemoryLogBuffer.Entry.Level.E, channel, t, msg)

    private inline fun emit(
        levelTag: String,
        level: InMemoryLogBuffer.Entry.Level,
        channel: LogChannel,
        t: Throwable?,
        msg: () -> String,
    ) {
        val m = msg()
        println("[$levelTag][${channel.tag}] $m${t?.let { " — ${it::class.simpleName}: ${it.message}" } ?: ""}")
        buffer.append(
            InMemoryLogBuffer.Entry(
                timestampMs = (NSDate().timeIntervalSince1970 * 1000).toLong(),
                level = level,
                channel = channel,
                message = m,
                throwableSummary = t?.let { "${it::class.simpleName}: ${it.message}" },
            ),
        )
    }
}
