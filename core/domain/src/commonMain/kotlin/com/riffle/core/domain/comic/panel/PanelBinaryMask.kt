package com.riffle.core.domain.comic.panel

/**
 * Binarized representation of a comic page — one byte per pixel, 0 = gutter/background,
 * 1 = content. Produced by [PanelDetector.binarizeMask]; used as the canonical
 * Sanitized Page Mask format for regression test fixtures (ADR 0062).
 */
data class PanelBinaryMask(val width: Int, val height: Int, val data: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PanelBinaryMask) return false
        return width == other.width && height == other.height && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * (31 * width + height) + data.contentHashCode()
}
