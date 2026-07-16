package com.riffle.core.domain.comic.panel

import java.util.ArrayDeque

/**
 * On-device panel detector for comic pages. Modeled on Kumiko's approach: treat the gutter as a
 * connected network and pick out panels as the regions the gutter walls off.
 *
 * Pipeline per page:
 *  1. Detect page background, invert if majority-dark → binarize (content=1, gutter=0).
 *  2. Trim outer margin to the content bounding box.
 *  3. Flood-fill the connected gutter starting from every gutter-valued pixel on the border.
 *  4. Connected components on non-gutter pixels are candidate panels.
 *  5. Filter tiny components (< [Config.minPanelAreaFraction] of the page), tighten bboxes.
 *  6. Sanity check: any of {no panels, single ≥95% whole-page panel, heavy panel overlap} triggers
 *     a [PanelSource.Fallback] result with one whole-page region.
 *
 * Input is a downscaled [PixelGrid] plus the source image's original dimensions; output
 * coordinates are in the original image's pixel space. Pure JVM — no Android imports.
 */
class PanelDetector(
    private val config: Config = Config(),
) {
    data class Config(
        /**
         * Reject any candidate whose bbox area is smaller than this fraction of the cropped page.
         * Filters speech balloons, dust, scanner artefacts.
         */
        val minPanelAreaFraction: Double = 0.02,

        /**
         * If detection returns a single panel that covers at least this fraction of the page, treat
         * it as a splash / detection collapse and fall back to Fit Whole.
         */
        val wholePagePanelThreshold: Double = 0.95,

        /**
         * If any two surviving panels overlap by more than this fraction of the smaller panel,
         * treat as detection confusion and fall back to Fit Whole.
         */
        val overlapRejectFraction: Double = 0.25,

        /**
         * Binarization threshold in `[0, 255]` — pixels darker than this are content, lighter are
         * gutter. Applied after optional dark-background inversion.
         */
        val binarizeThreshold: Int = 200,

        /** A row/column with fewer than this many content pixels is considered outer margin. */
        val marginContentThreshold: Int = 6,

        /**
         * A candidate panel's bbox is shrunk on each side by trimming trailing rows/columns that
         * carry fewer than this many content pixels (removes gutter that leaked into the bbox).
         */
        val tightenContentThreshold: Int = 2,

        /**
         * When true, sample corner pixels; if the majority are dark, invert the binary mask
         * (so a dark-background page is treated as if the background were light).
         */
        val invertOnDarkBackground: Boolean = true,
    )

    fun detect(
        grid: PixelGrid,
        pageIndex: Int,
        originalWidth: Int,
        originalHeight: Int,
    ): PagePanels {
        require(originalWidth > 0 && originalHeight > 0) { "original dimensions must be positive" }
        val fallback = fitWhole(pageIndex, originalWidth, originalHeight)

        val mask = binarize(grid) ?: return fallback
        val cropped = trimMargin(mask) ?: return fallback
        val gutter = floodFillGutter(cropped)
        val components = connectedComponents(cropped, gutter)
        val filtered = filterAndTighten(components, cropped)

        return sanityCheck(
            candidates = filtered,
            cropped = cropped,
            originalWidth = originalWidth,
            originalHeight = originalHeight,
            downscaledWidth = grid.width,
            downscaledHeight = grid.height,
            pageIndex = pageIndex,
        ) ?: fallback
    }

    // --- Step 1: binarize with dark-background auto-invert ---

    private fun binarize(grid: PixelGrid): BinaryMask? {
        val invert = config.invertOnDarkBackground && isDarkBackground(grid)
        val mask = ByteArray(grid.width * grid.height)
        for (i in mask.indices) {
            val v = grid.luma[i].toInt() and 0xFF
            val effective = if (invert) 255 - v else v
            // 1 = content (dark), 0 = gutter (light)
            mask[i] = if (effective < config.binarizeThreshold) 1 else 0
        }
        // If everything looks like gutter or everything looks like content, detection is impossible.
        var contentCount = 0
        for (b in mask) if (b == 1.toByte()) contentCount++
        val total = mask.size
        if (contentCount == 0 || contentCount == total) return null
        return BinaryMask(grid.width, grid.height, mask)
    }

    private fun isDarkBackground(grid: PixelGrid): Boolean {
        val w = grid.width
        val h = grid.height
        val samples = intArrayOf(
            grid.get(0, 0),
            grid.get(w - 1, 0),
            grid.get(0, h - 1),
            grid.get(w - 1, h - 1),
            grid.get(w / 2, 0),
            grid.get(w / 2, h - 1),
            grid.get(0, h / 2),
            grid.get(w - 1, h / 2),
        )
        val dark = samples.count { it < 128 }
        return dark >= 5
    }

    // --- Step 2: trim outer margin to content bounding box ---

    private fun trimMargin(mask: BinaryMask): CroppedMask? {
        val w = mask.width
        val h = mask.height
        val threshold = config.marginContentThreshold

        var top = 0
        while (top < h && mask.rowContentCount(top) < threshold) top++
        if (top >= h) return null

        var bottom = h - 1
        while (bottom > top && mask.rowContentCount(bottom) < threshold) bottom--

        var left = 0
        while (left < w && mask.colContentCount(left, top, bottom) < threshold) left++
        if (left >= w) return null

        var right = w - 1
        while (right > left && mask.colContentCount(right, top, bottom) < threshold) right--

        val cw = right - left + 1
        val ch = bottom - top + 1
        val out = ByteArray(cw * ch)
        for (y in 0 until ch) {
            System.arraycopy(mask.data, (top + y) * w + left, out, y * cw, cw)
        }
        return CroppedMask(cw, ch, out, offsetX = left, offsetY = top)
    }

    // --- Step 3: flood-fill the connected gutter from the border ---

    private fun floodFillGutter(cropped: CroppedMask): BooleanArray {
        val w = cropped.width
        val h = cropped.height
        val gutter = BooleanArray(w * h)
        val queue = ArrayDeque<Int>()

        fun seed(x: Int, y: Int) {
            val idx = y * w + x
            if (!gutter[idx] && cropped.data[idx] == 0.toByte()) {
                gutter[idx] = true
                queue.addLast(idx)
            }
        }
        for (x in 0 until w) { seed(x, 0); seed(x, h - 1) }
        for (y in 0 until h) { seed(0, y); seed(w - 1, y) }

        while (queue.isNotEmpty()) {
            val idx = queue.pollFirst()
            val x = idx % w
            val y = idx / w
            if (x > 0) seed(x - 1, y)
            if (x < w - 1) seed(x + 1, y)
            if (y > 0) seed(x, y - 1)
            if (y < h - 1) seed(x, y + 1)
        }
        return gutter
    }

    // --- Step 4: connected components on non-gutter pixels ---

    private fun connectedComponents(cropped: CroppedMask, gutter: BooleanArray): List<Bbox> {
        val w = cropped.width
        val h = cropped.height
        val visited = BooleanArray(w * h)
        val result = mutableListOf<Bbox>()
        val queue = ArrayDeque<Int>()

        for (start in 0 until w * h) {
            if (visited[start] || gutter[start]) continue
            visited[start] = true
            queue.addLast(start)
            var minX = start % w
            var maxX = minX
            var minY = start / w
            var maxY = minY
            while (queue.isNotEmpty()) {
                val idx = queue.pollFirst()
                val x = idx % w
                val y = idx / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                fun visit(nx: Int, ny: Int) {
                    val n = ny * w + nx
                    if (!visited[n] && !gutter[n]) {
                        visited[n] = true
                        queue.addLast(n)
                    }
                }
                if (x > 0) visit(x - 1, y)
                if (x < w - 1) visit(x + 1, y)
                if (y > 0) visit(x, y - 1)
                if (y < h - 1) visit(x, y + 1)
            }
            result.add(Bbox(minX, minY, maxX, maxY))
        }
        return result
    }

    // --- Step 5: filter tiny components and tighten each bbox to its content ---

    private fun filterAndTighten(components: List<Bbox>, cropped: CroppedMask): List<Bbox> {
        val pageArea = cropped.width.toLong() * cropped.height.toLong()
        val minArea = (pageArea.toDouble() * config.minPanelAreaFraction).toLong()

        return components
            .filter { it.area() >= minArea }
            .map { tighten(it, cropped) }
    }

    private fun tighten(bbox: Bbox, cropped: CroppedMask): Bbox {
        var minY = bbox.minY
        var maxY = bbox.maxY
        var minX = bbox.minX
        var maxX = bbox.maxX
        val t = config.tightenContentThreshold

        while (minY < maxY && cropped.rowContentCount(minY, minX, maxX) < t) minY++
        while (maxY > minY && cropped.rowContentCount(maxY, minX, maxX) < t) maxY--
        while (minX < maxX && cropped.colContentCount(minX, minY, maxY) < t) minX++
        while (maxX > minX && cropped.colContentCount(maxX, minY, maxY) < t) maxX--

        return Bbox(minX, minY, maxX, maxY)
    }

    // --- Step 6: sanity-check and scale up to original coordinates ---

    private fun sanityCheck(
        candidates: List<Bbox>,
        cropped: CroppedMask,
        originalWidth: Int,
        originalHeight: Int,
        downscaledWidth: Int,
        downscaledHeight: Int,
        pageIndex: Int,
    ): PagePanels? {
        if (candidates.isEmpty()) return null

        // Convert to original-image coordinates via the downscale ratio.
        val scaleX = originalWidth.toDouble() / downscaledWidth.toDouble()
        val scaleY = originalHeight.toDouble() / downscaledHeight.toDouble()

        val regions = candidates.map { bbox ->
            val absMinX = ((bbox.minX + cropped.offsetX) * scaleX).toInt().coerceIn(0, originalWidth - 1)
            val absMinY = ((bbox.minY + cropped.offsetY) * scaleY).toInt().coerceIn(0, originalHeight - 1)
            val absMaxX = ((bbox.maxX + 1 + cropped.offsetX) * scaleX).toInt().coerceIn(1, originalWidth)
            val absMaxY = ((bbox.maxY + 1 + cropped.offsetY) * scaleY).toInt().coerceIn(1, originalHeight)
            PanelRegion(
                x = absMinX,
                y = absMinY,
                width = (absMaxX - absMinX).coerceAtLeast(1),
                height = (absMaxY - absMinY).coerceAtLeast(1),
            )
        }

        val pageArea = originalWidth.toLong() * originalHeight.toLong()

        // Single whole-page-ish panel → treat as splash / collapsed detection, fall back.
        if (regions.size == 1) {
            val fraction = regions[0].area().toDouble() / pageArea.toDouble()
            if (fraction >= config.wholePagePanelThreshold) return null
        }

        // Heavy pairwise overlap → detector is confused, fall back.
        for (i in regions.indices) {
            for (j in i + 1 until regions.size) {
                val a = regions[i]
                val b = regions[j]
                val smaller = if (a.area() <= b.area()) a else b
                val larger = if (a.area() <= b.area()) b else a
                if (larger.overlapFraction(smaller) > config.overlapRejectFraction) return null
            }
        }

        return PagePanels(
            pageIndex = pageIndex,
            imageWidth = originalWidth,
            imageHeight = originalHeight,
            panels = regions,
            source = PanelSource.Auto,
        )
    }

    private fun fitWhole(pageIndex: Int, w: Int, h: Int): PagePanels = PagePanels(
        pageIndex = pageIndex,
        imageWidth = w,
        imageHeight = h,
        panels = listOf(PanelRegion(0, 0, w, h)),
        source = PanelSource.Fallback,
    )

    // --- Internal types ---

    private class BinaryMask(val width: Int, val height: Int, val data: ByteArray) {
        fun rowContentCount(y: Int): Int {
            var c = 0
            val base = y * width
            for (x in 0 until width) if (data[base + x] == 1.toByte()) c++
            return c
        }

        fun colContentCount(x: Int, yStart: Int, yEnd: Int): Int {
            var c = 0
            for (y in yStart..yEnd) if (data[y * width + x] == 1.toByte()) c++
            return c
        }
    }

    private class CroppedMask(
        val width: Int,
        val height: Int,
        val data: ByteArray,
        val offsetX: Int,
        val offsetY: Int,
    ) {
        fun rowContentCount(y: Int, xStart: Int = 0, xEnd: Int = width - 1): Int {
            var c = 0
            val base = y * width
            for (x in xStart..xEnd) if (data[base + x] == 1.toByte()) c++
            return c
        }

        fun colContentCount(x: Int, yStart: Int, yEnd: Int): Int {
            var c = 0
            for (y in yStart..yEnd) if (data[y * width + x] == 1.toByte()) c++
            return c
        }
    }

    private data class Bbox(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int) {
        fun area(): Long = (maxX - minX + 1).toLong() * (maxY - minY + 1).toLong()
    }
}
