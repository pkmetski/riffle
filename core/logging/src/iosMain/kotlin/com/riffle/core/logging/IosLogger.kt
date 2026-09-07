package com.riffle.core.logging

/**
 * iOS logger. Deliberately built on println, NOT NSLog: Kotlin/Native cannot pass a Kotlin
 * String through an Objective-C variadic call reliably — `NSLog("%@", msg)` hands `%@` a
 * non-NSString pointer and segfaults in `respondsToSelector` the first time a message is
 * actually logged (crashed Chitanka install, 2026-09-07). println reaches the Xcode console
 * and `simctl launch --console-pty` just the same.
 */
class IosLogger : Logger {
    override fun d(
        channel: LogChannel,
        t: Throwable?,
        msg: () -> String,
    ) = log("D", channel, t, msg)

    override fun w(
        channel: LogChannel,
        t: Throwable?,
        msg: () -> String,
    ) = log("W", channel, t, msg)

    override fun e(
        channel: LogChannel,
        t: Throwable?,
        msg: () -> String,
    ) = log("E", channel, t, msg)

    private fun log(level: String, channel: LogChannel, t: Throwable?, msg: () -> String) {
        println("[$level][${channel.tag}] ${msg()}${t?.let { " — ${it::class.simpleName}: ${it.message}" } ?: ""}")
    }
}
