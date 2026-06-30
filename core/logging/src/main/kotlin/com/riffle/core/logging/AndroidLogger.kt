package com.riffle.core.logging

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [Logger]: forwards to [android.util.Log] using the channel's tag.
 *
 * No release-build gating today — every call hits `Log.*`. The lambda message arg keeps the
 * door open for a future `if (channel !in enabledChannels) return` short-circuit.
 */
@Singleton
class AndroidLogger @Inject constructor() : Logger {

    override fun d(channel: LogChannel, t: Throwable?, msg: () -> String) {
        if (t != null) Log.d(channel.tag, msg(), t) else Log.d(channel.tag, msg())
    }

    override fun w(channel: LogChannel, t: Throwable?, msg: () -> String) {
        if (t != null) Log.w(channel.tag, msg(), t) else Log.w(channel.tag, msg())
    }

    override fun e(channel: LogChannel, t: Throwable?, msg: () -> String) {
        if (t != null) Log.e(channel.tag, msg(), t) else Log.e(channel.tag, msg())
    }
}
