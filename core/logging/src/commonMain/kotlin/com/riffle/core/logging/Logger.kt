package com.riffle.core.logging

/**
 * Typed logging seam. Inject [Logger] (production) or substitute [RecordingLogger] in tests.
 *
 * The [msg] argument is a lambda so a future channel gate is zero-cost on the hot path
 * (no string interpolation when the channel is disabled). Today every call evaluates it.
 *
 * Pass [t] when logging an exception — implementations forward it to the underlying logger.
 */
interface Logger {
    fun d(channel: LogChannel, t: Throwable? = null, msg: () -> String)
    fun w(channel: LogChannel, t: Throwable? = null, msg: () -> String)
    fun e(channel: LogChannel, t: Throwable? = null, msg: () -> String)
}
