package com.riffle.core.domain

import java.time.LocalTime

/** Production [TimeProvider] on Android/JVM. */
object SystemTimeProvider : TimeProvider {
    override fun nowLocalTime(): LocalMinuteTime {
        val now = LocalTime.now()
        return LocalMinuteTime(now.hour, now.minute)
    }
}
