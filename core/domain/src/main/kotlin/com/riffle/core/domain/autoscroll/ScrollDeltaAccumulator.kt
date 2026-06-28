package com.riffle.core.domain.autoscroll

class ScrollDeltaAccumulator {

    private var fractional: Float = 0f

    fun advance(deltaSec: Float, pxPerSec: Float): Int {
        if (deltaSec <= 0f || pxPerSec <= 0f) return 0
        fractional += deltaSec * pxPerSec
        if (fractional < 1f) return 0
        val whole = fractional.toInt()
        fractional -= whole.toFloat()
        return whole
    }

    fun reset() {
        fractional = 0f
    }
}
