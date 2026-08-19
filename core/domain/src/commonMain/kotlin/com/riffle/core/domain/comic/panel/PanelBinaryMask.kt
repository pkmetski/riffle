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

    /**
     * Returns a new mask where each [blockSize]×[blockSize] cell is collapsed to a single pixel
     * by majority vote (≥50% content → content). Destroys art detail while preserving the
     * content-vs-gutter signal used by the panel detector (ADR 0062).
     */
    fun blockQuantize(blockSize: Int = 16): PanelBinaryMask {
        val cols = (width + blockSize - 1) / blockSize
        val rows = (height + blockSize - 1) / blockSize
        val out = ByteArray(cols * rows)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                var content = 0; var total = 0
                for (dy in 0 until blockSize) {
                    val py = row * blockSize + dy; if (py >= height) continue
                    for (dx in 0 until blockSize) {
                        val px = col * blockSize + dx; if (px >= width) continue
                        total++
                        if (data[py * width + px] == 1.toByte()) content++
                    }
                }
                out[row * cols + col] = if (total > 0 && content * 2 >= total) 1 else 0
            }
        }
        return PanelBinaryMask(cols, rows, out)
    }
}
