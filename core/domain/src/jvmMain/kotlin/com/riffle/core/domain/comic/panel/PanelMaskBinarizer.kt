package com.riffle.core.domain.comic.panel

/**
 * Produces a copyright-safe binary mask for panel detection reports (ADR 0062).
 *
 * Uses directional adaptive (local) thresholding: on a light-background page only pixels
 * DARKER than their local neighbourhood mean are content (ink); on a dark-background page
 * only pixels LIGHTER than their local mean are content (highlights, panel borders). This
 * matches the directionality of the detection binarizer and preserves thin gutters — a white
 * gutter pixel surrounded by dark panels has a dark local mean, but `v < mean` is false for
 * white on a light page so the gutter pixel stays gutter.
 *
 * Window half-size = max(32, height/12); constant C = 10.
 * Uses a 2-D integral image for O(1) per-pixel mean computation.
 */
object PanelMaskBinarizer {

    fun binarize(grid: PixelGrid): PanelBinaryMask? {
        val w = grid.width; val h = grid.height
        val bg = detectPageBackground(grid)
        val lightPage = bg >= 128
        val stride = w + 1

        val integral = LongArray(stride * (h + 1))
        for (y in 0 until h) {
            for (x in 0 until w) {
                integral[(y + 1) * stride + (x + 1)] =
                    grid.get(x, y).toLong() +
                    integral[y * stride + (x + 1)] +
                    integral[(y + 1) * stride + x] -
                    integral[y * stride + x]
            }
        }

        val half = maxOf(32, h / 12)
        val C = 10L
        val data = ByteArray(w * h)

        for (y in 0 until h) {
            val y0 = maxOf(0, y - half); val y1 = minOf(h - 1, y + half)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - half); val x1 = minOf(w - 1, x + half)
                val area = (y1 - y0 + 1).toLong() * (x1 - x0 + 1)
                val sum = integral[(y1 + 1) * stride + (x1 + 1)] -
                          integral[y0 * stride + (x1 + 1)] -
                          integral[(y1 + 1) * stride + x0] +
                          integral[y0 * stride + x0]
                val mean = sum / area
                val v = grid.get(x, y).toLong()
                val isContent = if (lightPage) v < mean - C else v > mean + C
                data[y * w + x] = if (isContent) 1 else 0
            }
        }

        val contentCount = data.count { it == 1.toByte() }
        if (contentCount == 0 || contentCount == data.size) return null
        return PanelBinaryMask(w, h, data)
    }
}

/**
 * 85th-percentile luma of the page border pixels — resistant to dark scanner-corner artefacts.
 * Package-internal so both [PanelDetector] and [PanelMaskBinarizer] share the same estimate.
 */
internal fun detectPageBackground(grid: PixelGrid): Int {
    val w = grid.width; val h = grid.height
    val step = maxOf(1, minOf(w, h) / 50)
    val samples = mutableListOf<Int>()
    var x = 0; while (x < w) { samples.add(grid.get(x, 0)); samples.add(grid.get(x, h - 1)); x += step }
    var y = 0; while (y < h) { samples.add(grid.get(0, y)); samples.add(grid.get(w - 1, y)); y += step }
    samples.sort()
    return samples[(samples.size * 85) / 100]
}
