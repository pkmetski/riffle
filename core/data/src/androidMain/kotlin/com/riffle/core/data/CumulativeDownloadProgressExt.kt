package com.riffle.core.data

import java.io.FilterInputStream
import java.io.InputStream

internal fun CumulativeDownloadProgress.track(delegate: InputStream): InputStream =
    object : FilterInputStream(delegate) {
        override fun read(): Int = super.read().also { if (it != -1) record(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) record(it.toLong()) }
    }
