package com.riffle.core.data

import java.io.InputStream

/**
 * Owns the cumulative byte count and stable total for a transfer. One instance can track a single
 * book stream or successive audiobook tracks; a non-positive total remains indeterminate.
 *
 * Thread-safe: [record] and [establishTotal] synchronize on `this` so parallel track downloads can
 * all report into the same counter without races.
 */
internal class CumulativeDownloadProgress(
    total: Long,
    private val onProgress: (downloaded: Long, total: Long) -> Unit,
    initialDownloaded: Long = 0L,
) {

    private var downloaded = initialDownloaded.coerceAtLeast(0L)
    private var total = total.takeIf { it > 0L } ?: 0L

    /** Returns true when a positive total is already known (from fingerprint or a prior call). */
    @Synchronized
    fun hasKnownTotal(): Boolean = total > 0L

    /** Establishes an initially unknown total exactly once. */
    @Synchronized
    fun establishTotal(candidate: Long?) {
        if (total == 0L && candidate != null && candidate > 0L) total = candidate
    }

    @Synchronized
    fun record(delta: Long) {
        if (delta <= 0L) return
        downloaded += delta
        onProgress(downloaded, total)
    }

    fun track(delegate: InputStream): InputStream = object : java.io.FilterInputStream(delegate) {
        override fun read(): Int = super.read().also { if (it != -1) record(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) record(it.toLong()) }
    }
}
