package com.riffle.core.logging

import platform.Foundation.NSLog

class IosLogger : Logger {
    override fun d(channel: LogChannel, t: Throwable?, msg: () -> String) =
        NSLog("[D][%@] %@", channel.tag, msg())

    override fun w(channel: LogChannel, t: Throwable?, msg: () -> String) =
        NSLog("[W][%@] %@", channel.tag, msg())

    override fun e(channel: LogChannel, t: Throwable?, msg: () -> String) =
        NSLog("[E][%@] %@", channel.tag, msg())
}
