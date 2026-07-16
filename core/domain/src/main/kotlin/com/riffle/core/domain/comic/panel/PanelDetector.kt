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
         * A panel must span at least this fraction of the page in EITHER dimension. Anything
         * smaller isn't a real panel worth zooming to — it's a noise island (e.g. an artifact
         * inside a bleed-splash background). Rejecting them prevents Panel View from forcing
         * the user through blurry meaningless zooms.
         */
        val minPanelDimensionFraction: Double = 0.15,

        /**
         * Total detected panel area must be at least this fraction of the page. Real
         * multi-panel pages cover 60%+ of the page in panels (excluding gutter); the threshold
         * is set well below that so sparse-but-real layouts (2 small panels on a mostly-white
         * page) still pass, while pure-noise detections (a handful of tiny islands on a
         * bleed-splash) are rejected. Tuned against the Batman video regression.
         */
        val minTotalCoverageFraction: Double = 0.3,

        /**
         * A pixel is considered content if its luma differs from the detected page background by
         * at least this much (in `[0, 255]`). Handles both light-background comics (dark art on
         * white gutter) and dark-background comics (bright figures on black gutter) uniformly.
         */
        val backgroundContrastThreshold: Int = 32,

        /**
         * A pixel is also content if the standard deviation of luma in an 11x11 window around it
         * exceeds this value. Directly measures "is this pixel in a modulated region" — works
         * for dark-tone comics where panel interiors are mostly dark with a few bright accents
         * (sparse bright pixels alone wouldn't survive the promotion-count heuristic, but they
         * do lift σ meaningfully above the ≈0 σ of a uniform gutter).
         */
        val textureStdDevThreshold: Double = 12.0,

        /**
         * Half-side of the texture-detection window. Kept small (5x5) so thin between-panel
         * gutters aren't filled in by the pass — a 5x5 window preserves gutters ≥ 3 pixels wide.
         */
        val textureWindowRadius: Int = 2,

        /** A row/column with fewer than this many content pixels is considered outer margin. */
        val marginContentThreshold: Int = 6,

        /**
         * A candidate panel's bbox is shrunk on each side by trimming trailing rows/columns that
         * carry fewer than this many content pixels (removes gutter that leaked into the bbox).
         */
        val tightenContentThreshold: Int = 2,

        /**
         * Radius of the morphological CLOSE applied to the binary mask before flood-fill. CLOSE
         * (dilate then erode) fills holes inside panels. Kept small so it never bridges the
         * gutter between adjacent panels (which is typically 10+ pixels at ~1000px long-edge).
         * Zero disables the pass.
         */
        val morphCloseRadius: Int = 0,

        /**
         * Radius of the morphological OPEN applied after CLOSE. OPEN (erode then dilate) breaks
         * thin (< 2R+1 pixels) content bridges that would otherwise connect adjacent panels.
         * Kept small so it doesn't eat real thin content (line art, speech-balloon borders).
         * Zero disables the pass.
         */
        val morphOpenRadius: Int = 0,

        /**
         * A row (or column) is considered part of the gutter if its content-pixel count is at or
         * below this fraction of the maximum row (or column) content in the cropped page. Used
         * by the projection-based grid detector to find gutter bands between panels.
         */
        val projectionGutterFraction: Double = 0.15,

        /**
         * The projection-based grid detector rejects gutter/content bands thinner than this many
         * pixels — filters noise (a single content row inside a gutter, or a stray gutter row
         * inside a panel band that would spuriously split a panel).
         */
        val projectionMinBandThickness: Int = 15,
    )

    fun detect(
        grid: PixelGrid,
        pageIndex: Int,
        originalWidth: Int,
        originalHeight: Int,
    ): PagePanels {
        require(originalWidth > 0 && originalHeight > 0) { "original dimensions must be positive" }
        val fallback = fitWhole(pageIndex, originalWidth, originalHeight)

        val rawMask = binarize(grid) ?: return fallback
        val mask = morphologyClose(rawMask, config.morphCloseRadius)
            .let { morphologyOpen(it, config.morphOpenRadius) }
        val cropped = trimMargin(mask) ?: return fallback

        // 1. Try grid detection via row/column projection profiles. Works cleanly for regular
        //    N×M grids (common in Western superhero, most manga chapters, and dark-tone books
        //    where local pixel classification is noisy but projections still show clean valleys).
        gridByProjection(cropped, pageIndex, originalWidth, originalHeight, grid.width, grid.height)
            ?.let { return it }

        // 2. Fall back to connected-component detection for irregular layouts (T-shapes,
        //    staircases, splash-with-insets).
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

    // --- Grid detection via projection profiles ---

    /**
     * Detect a regular row×column grid by finding "gutter bands" in the row and column
     * projections of the content mask. A gutter band is a run of consecutive rows/columns whose
     * content pixel count is below [Config.projectionGutterFraction] of the max content in any
     * row/column. Returns null if the grid geometry isn't plausible (too few panels, degenerate
     * dimensions, or a single-cell result).
     */
    private fun gridByProjection(
        cropped: CroppedMask,
        pageIndex: Int,
        originalWidth: Int,
        originalHeight: Int,
        downscaledWidth: Int,
        downscaledHeight: Int,
    ): PagePanels? {
        val rowContent = IntArray(cropped.height) { cropped.rowContentCount(it) }
        val rowBands = contentBands(rowContent, config.projectionGutterFraction, config.projectionMinBandThickness)
        if (rowBands.isEmpty()) return null

        // Compute column bands PER ROW BAND — layouts often vary across rows (e.g. top splash
        // over three-panel bottom row). Projecting the full-height column would let the
        // panel-rich rows swamp the sparse bands and hide vertical gutters.
        val bandColBands = rowBands.map { row ->
            val colContent = IntArray(cropped.width) { cropped.colContentCount(it, row.start, row.end) }
            contentBands(colContent, config.projectionGutterFraction, config.projectionMinBandThickness)
        }
        // Every row must have found at least one column band, and at least one axis somewhere
        // must produce a real subdivision (otherwise we're claiming "single panel" — let CC handle it).
        if (bandColBands.any { it.isEmpty() }) return null
        val totalCells = bandColBands.sumOf { it.size }
        if (totalCells < 2 && rowBands.size < 2) return null

        val scaleX = originalWidth.toDouble() / downscaledWidth.toDouble()
        val scaleY = originalHeight.toDouble() / downscaledHeight.toDouble()
        val regions = mutableListOf<PanelRegion>()
        for ((rowIndex, rowBand) in rowBands.withIndex()) {
            for (colBand in bandColBands[rowIndex]) {
                val minX = ((colBand.start + cropped.offsetX) * scaleX).toInt().coerceIn(0, originalWidth - 1)
                val minY = ((rowBand.start + cropped.offsetY) * scaleY).toInt().coerceIn(0, originalHeight - 1)
                val maxX = ((colBand.end + 1 + cropped.offsetX) * scaleX).toInt().coerceIn(1, originalWidth)
                val maxY = ((rowBand.end + 1 + cropped.offsetY) * scaleY).toInt().coerceIn(1, originalHeight)
                regions.add(
                    PanelRegion(
                        x = minX,
                        y = minY,
                        width = (maxX - minX).coerceAtLeast(1),
                        height = (maxY - minY).coerceAtLeast(1),
                    ),
                )
            }
        }

        val meaningful = applyGlobalSanityChecks(regions, originalWidth, originalHeight) ?: return null
        return PagePanels(
            pageIndex = pageIndex,
            imageWidth = originalWidth,
            imageHeight = originalHeight,
            panels = meaningful,
            source = PanelSource.Auto,
        )
    }

    /**
     * Post-detection sanity checks applied to BOTH the projection and CC paths:
     *  - drop panels smaller than [Config.minPanelDimensionFraction] of the page in both axes
     *    (noise islands that would make Panel View force blurry zooms into meaningless regions);
     *  - reject a single whole-page-ish panel as a splash / collapse;
     *  - reject heavy pairwise overlap as detector confusion;
     *  - require total panel coverage of at least [Config.minTotalCoverageFraction] of the page
     *    (rules out "we only found some tiny artifacts on a bleed-splash page").
     *
     * Returns the filtered list, or null if the checks reject the whole result (→ Fallback).
     */
    private fun applyGlobalSanityChecks(
        regions: List<PanelRegion>,
        originalWidth: Int,
        originalHeight: Int,
    ): List<PanelRegion>? {
        val pageArea = originalWidth.toLong() * originalHeight.toLong()
        val minWidth = (originalWidth * config.minPanelDimensionFraction).toInt().coerceAtLeast(1)
        val minHeight = (originalHeight * config.minPanelDimensionFraction).toInt().coerceAtLeast(1)
        val meaningful = regions.filter { it.width >= minWidth || it.height >= minHeight }
        if (meaningful.isEmpty()) return null

        if (meaningful.size == 1) {
            val fraction = meaningful[0].area().toDouble() / pageArea.toDouble()
            if (fraction >= config.wholePagePanelThreshold) return null
        }

        for (i in meaningful.indices) {
            for (j in i + 1 until meaningful.size) {
                val a = meaningful[i]
                val b = meaningful[j]
                val smaller = if (a.area() <= b.area()) a else b
                val larger = if (a.area() <= b.area()) b else a
                if (larger.overlapFraction(smaller) > config.overlapRejectFraction) return null
            }
        }

        val totalPanelArea = meaningful.sumOf { it.area() }
        if (totalPanelArea.toDouble() / pageArea.toDouble() < config.minTotalCoverageFraction) return null

        return meaningful
    }

    private data class Band(val start: Int, val end: Int)

    /**
     * Given a 1-D projection, return the runs of "content" (values above a threshold derived from
     * the peak), skipping short runs (< [minThickness]).
     */
    private fun contentBands(projection: IntArray, gutterFraction: Double, minThickness: Int): List<Band> {
        val maxV = projection.maxOrNull() ?: return emptyList()
        if (maxV == 0) return emptyList()
        val cutoff = (maxV * gutterFraction).toInt().coerceAtLeast(1)
        val bands = mutableListOf<Band>()
        var start = -1
        for (i in projection.indices) {
            val isContent = projection[i] > cutoff
            if (isContent && start < 0) start = i
            else if (!isContent && start >= 0) {
                if (i - start >= minThickness) bands.add(Band(start, i - 1))
                start = -1
            }
        }
        if (start >= 0 && projection.size - start >= minThickness) {
            bands.add(Band(start, projection.size - 1))
        }
        return bands
    }

    /**
     * Test-only entry point returning the intermediate masks so unit tests can dump them as
     * PNGs and debug why detection fell back. Never called from production paths.
     */
    fun detectDebug(grid: PixelGrid): DebugTrace {
        val rawMask = binarize(grid)
        val mask = rawMask
            ?.let { morphologyClose(it, config.morphCloseRadius) }
            ?.let { morphologyOpen(it, config.morphOpenRadius) }
        val cropped = mask?.let { trimMargin(it) }
        val gutter = cropped?.let { floodFillGutter(it) }
        val components = if (cropped != null && gutter != null) connectedComponents(cropped, gutter) else emptyList()
        val filtered = if (cropped != null) filterAndTighten(components, cropped) else emptyList()
        return DebugTrace(
            gridWidth = grid.width,
            gridHeight = grid.height,
            binaryMaskData = mask?.data,
            binaryMaskWidth = mask?.width ?: 0,
            binaryMaskHeight = mask?.height ?: 0,
            croppedMaskData = cropped?.data,
            croppedWidth = cropped?.width ?: 0,
            croppedHeight = cropped?.height ?: 0,
            croppedOffsetX = cropped?.offsetX ?: 0,
            croppedOffsetY = cropped?.offsetY ?: 0,
            gutterMask = gutter,
            componentBboxes = components.map { IntArray(4).apply { this[0] = it.minX; this[1] = it.minY; this[2] = it.maxX; this[3] = it.maxY } },
            filteredBboxes = filtered.map { IntArray(4).apply { this[0] = it.minX; this[1] = it.minY; this[2] = it.maxX; this[3] = it.maxY } },
        )
    }

    /**
     * Morphological CLOSE = dilate by [radius] then erode by [radius]. Fills small holes inside
     * content regions. Uses a separable-1D pass in each axis for O(width * height * radius)
     * cost instead of O(width * height * radius^2). Zero radius is a no-op.
     */
    private fun morphologyClose(mask: BinaryMask, radius: Int): BinaryMask {
        if (radius <= 0) return mask
        val dilated = dilate1D(mask, radius, horizontal = true)
            .let { dilate1D(it, radius, horizontal = false) }
        return erode1D(dilated, radius, horizontal = true)
            .let { erode1D(it, radius, horizontal = false) }
    }

    private fun morphologyOpen(mask: BinaryMask, radius: Int): BinaryMask {
        if (radius <= 0) return mask
        val eroded = erode1D(mask, radius, horizontal = true)
            .let { erode1D(it, radius, horizontal = false) }
        return dilate1D(eroded, radius, horizontal = true)
            .let { dilate1D(it, radius, horizontal = false) }
    }

    private fun dilate1D(mask: BinaryMask, radius: Int, horizontal: Boolean): BinaryMask {
        val w = mask.width
        val h = mask.height
        val out = ByteArray(w * h)
        if (horizontal) {
            for (y in 0 until h) {
                val rowBase = y * w
                for (x in 0 until w) {
                    val x0 = maxOf(0, x - radius)
                    val x1 = minOf(w - 1, x + radius)
                    var any: Byte = 0
                    for (xx in x0..x1) if (mask.data[rowBase + xx] == 1.toByte()) { any = 1; break }
                    out[rowBase + x] = any
                }
            }
        } else {
            for (x in 0 until w) {
                for (y in 0 until h) {
                    val y0 = maxOf(0, y - radius)
                    val y1 = minOf(h - 1, y + radius)
                    var any: Byte = 0
                    for (yy in y0..y1) if (mask.data[yy * w + x] == 1.toByte()) { any = 1; break }
                    out[y * w + x] = any
                }
            }
        }
        return BinaryMask(w, h, out)
    }

    private fun erode1D(mask: BinaryMask, radius: Int, horizontal: Boolean): BinaryMask {
        val w = mask.width
        val h = mask.height
        val out = ByteArray(w * h)
        if (horizontal) {
            for (y in 0 until h) {
                val rowBase = y * w
                for (x in 0 until w) {
                    val x0 = maxOf(0, x - radius)
                    val x1 = minOf(w - 1, x + radius)
                    var all: Byte = 1
                    for (xx in x0..x1) if (mask.data[rowBase + xx] != 1.toByte()) { all = 0; break }
                    out[rowBase + x] = all
                }
            }
        } else {
            for (x in 0 until w) {
                for (y in 0 until h) {
                    val y0 = maxOf(0, y - radius)
                    val y1 = minOf(h - 1, y + radius)
                    var all: Byte = 1
                    for (yy in y0..y1) if (mask.data[yy * w + x] != 1.toByte()) { all = 0; break }
                    out[y * w + x] = all
                }
            }
        }
        return BinaryMask(w, h, out)
    }

    /** Snapshot of every intermediate stage for [detectDebug]. Arrays are row-major. */
    data class DebugTrace(
        val gridWidth: Int,
        val gridHeight: Int,
        val binaryMaskData: ByteArray?,
        val binaryMaskWidth: Int,
        val binaryMaskHeight: Int,
        val croppedMaskData: ByteArray?,
        val croppedWidth: Int,
        val croppedHeight: Int,
        val croppedOffsetX: Int,
        val croppedOffsetY: Int,
        val gutterMask: BooleanArray?,
        val componentBboxes: List<IntArray>,
        val filteredBboxes: List<IntArray>,
    )

    // --- Step 1: binarize as content-vs-background ---

    /**
     * A pixel is CONTENT if either:
     *   (a) its luma differs from the page background by ≥ [Config.backgroundContrastThreshold]
     *       (catches solid colours that clearly aren't the page background), OR
     *   (b) the standard deviation of luma in an 11x11 window centred on it exceeds
     *       [Config.textureStdDevThreshold] (catches any pixel sitting in a modulated
     *       neighbourhood — the panel-interior signal that survives even when panels are dark
     *       and only sparsely lit).
     *
     * Uniform pixels matching the page background (any colour) with a uniform neighbourhood are
     * GUTTER. Handles white-gutter Western comics AND dark-tone comics with black gutter using
     * the same code path.
     *
     * Uses the sum-of-squares definition of variance:
     *   σ² = mean(x²) − mean(x)² = Σx²/n − (Σx/n)²
     * so one pass per pixel over its window is enough.
     */
    private fun binarize(grid: PixelGrid): BinaryMask? {
        val w = grid.width
        val h = grid.height
        val bg = detectBackgroundLuma(grid)
        val cutoff = config.backgroundContrastThreshold
        val radius = config.textureWindowRadius
        val stddevCutoff = config.textureStdDevThreshold
        val varianceCutoff = stddevCutoff * stddevCutoff
        val mask = ByteArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = grid.get(x, y)
                val differsFromBg = kotlin.math.abs(v - bg) >= cutoff
                mask[y * w + x] = if (differsFromBg || hasTexture(grid, x, y, radius, varianceCutoff)) 1 else 0
            }
        }
        var contentCount = 0
        for (b in mask) if (b == 1.toByte()) contentCount++
        val total = mask.size
        if (contentCount == 0 || contentCount == total) return null
        return BinaryMask(w, h, mask)
    }

    private fun hasTexture(grid: PixelGrid, x: Int, y: Int, radius: Int, varianceCutoff: Double): Boolean {
        val w = grid.width
        val h = grid.height
        val y0 = maxOf(0, y - radius)
        val y1 = minOf(h - 1, y + radius)
        val x0 = maxOf(0, x - radius)
        val x1 = minOf(w - 1, x + radius)
        var sum = 0L
        var sumSq = 0L
        var n = 0
        for (yy in y0..y1) {
            for (xx in x0..x1) {
                val v = grid.get(xx, yy)
                sum += v
                sumSq += v * v
                n++
            }
        }
        val mean = sum.toDouble() / n
        val variance = sumSq.toDouble() / n - mean * mean
        return variance >= varianceCutoff
    }

    /** Median luma of eight border samples — cheap and robust page-background estimator. */
    private fun detectBackgroundLuma(grid: PixelGrid): Int {
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
        samples.sort()
        return (samples[3] + samples[4]) / 2
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

        val meaningful = applyGlobalSanityChecks(regions, originalWidth, originalHeight) ?: return null
        return PagePanels(
            pageIndex = pageIndex,
            imageWidth = originalWidth,
            imageHeight = originalHeight,
            panels = meaningful,
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
