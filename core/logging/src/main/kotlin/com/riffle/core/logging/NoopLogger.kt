package com.riffle.core.logging

/**
 * No-op [Logger] for defaulting fields that are wired up post-construction
 * (e.g. [ContinuousDecorationController.logger] before the reader view is installed).
 *
 * Prefer injecting the real [Logger] where possible — this exists so that classes constructed
 * before the reader is wired can still safely emit without null checks at every call site.
 */
object NoopLogger : Logger {
    override fun d(channel: LogChannel, t: Throwable?, msg: () -> String) = Unit
    override fun w(channel: LogChannel, t: Throwable?, msg: () -> String) = Unit
    override fun e(channel: LogChannel, t: Throwable?, msg: () -> String) = Unit
}
