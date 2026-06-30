package com.riffle.core.domain

/** Production [Clock] — delegates straight to the JVM system clock. Bound in `AppModule`. */
object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun nowNs(): Long = System.nanoTime()
}
