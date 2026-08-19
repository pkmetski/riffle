package com.riffle.core.domain.comic.panel

/**
 * The single binarizer used by both the panel detector and the panel mask reporter.
 *
 * A pixel is content if **any** of three complementary classifiers says so:
 *
 * 1. **Local adaptive** (primary): content if luma differs from the local neighbourhood mean
 *    by more than [LOCAL_C] in the direction away from the page background (darker for light
 *    pages, lighter for dark pages). Window half-size = max(32, height/12); uses a 2-D integral
 *    image for O(1) per-pixel mean computation.
 *    *JPEG-robustness property*: gutter rows are surrounded above/below by dense dark panel
 *    content, so the local mean for a gutter pixel is dark. Off-white JPEG artifact pixels
 *    (v ≈ 190–210 on a 240-bg page) satisfy v >> local_mean and are correctly classified as
 *    gutter. A global-contrast-only binarizer would see bg − v ≈ 30–50 and call them content,
 *    contaminating gutter rows and breaking projection-based gutter detection.
 *
 * 2. **Global contrast** (solid-colour fallback): content if luma differs from the page
 *    background by at least [GLOBAL_CONTRAST_CUTOFF]. Handles synthetic or pre-binarized grids
 *    where solid-colour regions have uniform local neighbourhoods (local mean = pixel value →
 *    zero local contrast → local adaptive misclassifies interior pixels as gutter).
 *
 * 3. **Texture** (border-bridge): content if the luma standard deviation in a small
 *    [TEXTURE_RADIUS]×[TEXTURE_RADIUS] window exceeds [TEXTURE_STDDEV_CUTOFF]. Fires on pixels
 *    at the boundary between content and gutter, classifying gutter-side border pixels as
 *    content. This keeps thin internal gutters from being exposed when they sit inside a border
 *    region — the same role [hasTexture] played in the original detector binarizer.
 *
 * Because the reporter uses this same class to generate JVM test fixtures, the binary image
 * the detector processes on device is identical to the fixture — any test that passes in JVM
 * is guaranteed to pass on device with real JPEG input.
 */
object PanelMaskBinarizer {

    private const val LOCAL_C = 10L
    internal const val GLOBAL_CONTRAST_CUTOFF = 50
    private const val TEXTURE_RADIUS = 2
    private const val TEXTURE_STDDEV_CUTOFF = 12.0
    private val TEXTURE_VARIANCE_CUTOFF = TEXTURE_STDDEV_CUTOFF * TEXTURE_STDDEV_CUTOFF

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
                val localContent = if (lightPage) v < mean - LOCAL_C else v > mean + LOCAL_C
                val globalContent = if (lightPage)
                    bg - v.toInt() >= GLOBAL_CONTRAST_CUTOFF
                else
                    v.toInt() - bg >= GLOBAL_CONTRAST_CUTOFF
                val textureContent = hasTexture(grid, x, y, w, h)
                data[y * w + x] = if (localContent || globalContent || textureContent) 1 else 0
            }
        }

        val contentCount = data.count { it == 1.toByte() }
        if (contentCount == 0 || contentCount == data.size) return null
        return PanelBinaryMask(w, h, data)
    }

    private fun hasTexture(grid: PixelGrid, cx: Int, cy: Int, w: Int, h: Int): Boolean {
        val y0 = maxOf(0, cy - TEXTURE_RADIUS); val y1 = minOf(h - 1, cy + TEXTURE_RADIUS)
        val x0 = maxOf(0, cx - TEXTURE_RADIUS); val x1 = minOf(w - 1, cx + TEXTURE_RADIUS)
        var sum = 0L; var sumSq = 0L; var n = 0
        for (yy in y0..y1) for (xx in x0..x1) {
            val v = grid.get(xx, yy).toLong()
            sum += v; sumSq += v * v; n++
        }
        if (n == 0) return false
        val mean = sum.toDouble() / n
        val variance = sumSq.toDouble() / n - mean * mean
        return variance > TEXTURE_VARIANCE_CUTOFF
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
