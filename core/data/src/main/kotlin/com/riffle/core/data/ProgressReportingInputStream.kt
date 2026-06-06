package com.riffle.core.data

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Wraps a download body's stream and reports how many bytes have been read so far as the consumer
 * (the [com.riffle.core.domain.LocalStore]) drains it. [total] is the advertised content length, or
 * -1 when the server didn't send one — callers treat a non-positive total as "indeterminate" and
 * fall back to a spinner instead of a percentage.
 */
internal class ProgressReportingInputStream(
    delegate: InputStream,
    private val total: Long,
    private val onProgress: (downloaded: Long, total: Long) -> Unit,
) : FilterInputStream(delegate) {

    private var read = 0L

    override fun read(): Int {
        val b = super.read()
        if (b != -1) report(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) report(n.toLong())
        return n
    }

    private fun report(delta: Long) {
        read += delta
        onProgress(read, total)
    }
}
