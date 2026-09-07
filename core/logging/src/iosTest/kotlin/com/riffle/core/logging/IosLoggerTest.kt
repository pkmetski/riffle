package com.riffle.core.logging

import kotlin.test.Test

/**
 * Regression: IosLogger previously routed messages through `NSLog("%@", msg)`. Kotlin/Native
 * cannot pass a Kotlin String through an Objective-C variadic call, so the first real log line
 * segfaulted (crashed the Chitanka install flow). These tests fail by crashing the test process
 * if that regresses — including for messages that themselves contain format tokens.
 */
class IosLoggerTest {

    private val logger = IosLogger()

    @Test
    fun logsPlainMessagesWithoutCrashing() {
        LogChannel.entries.forEach { channel ->
            logger.d(channel) { "debug line" }
            logger.w(channel) { "warn line" }
            logger.e(channel) { "error line" }
        }
    }

    @Test
    fun logsMessagesContainingFormatTokensWithoutCrashing() {
        val hostile = "url http://x/%E2%82%AC?q=%s %@ %n %d 100%"
        logger.d(LogChannel.entries.first()) { hostile }
        logger.w(LogChannel.entries.first(), Exception("boom %@ %s")) { hostile }
        logger.e(LogChannel.entries.first(), Exception(hostile)) { hostile }
    }
}
