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
        val minPanelDimensionFraction: Double = 0.14,

        /**
         * Total detected panel area must be at least this fraction of the page. Real
         * multi-panel pages cover 60%+ of the page in panels (excluding gutter); the threshold
         * is set well below that so sparse-but-real layouts (2 small panels on a mostly-white
         * page) still pass, while pure-noise detections (a handful of tiny islands on a
         * bleed-splash) are rejected. Tuned against the Batman video regression.
         */
        val minTotalCoverageFraction: Double = 0.3,

        /**
         * Reject detections whose summed panel area exceeds this fraction of the page (i.e.
         * panels overlap so much they can't be a valid tiling). Real panels tile with small
         * gutters so summed coverage is < 1.0; anything > 1.05 means CC merged content across
         * panel boundaries and produced overlapping big-blob bboxes.
         */
        val maxSummedCoverageFraction: Double = 1.05,

        /**
         * When one panel bbox contains ≥ this fraction of the smaller panel it overlaps, treat
         * them as duplicates and keep only the smaller (tighter) one. Fixes the "same panel
         * shown twice" failure where the detector emits both a clean panel bbox and a merged
         * panel-plus-neighbour bbox.
         */
        val dedupOverlapFraction: Double = 0.6,

        /**
         * A pixel is considered content if its luma differs from the detected page background by
         * at least this much (in `[0, 255]`). Handles both light-background comics (dark art on
         * white gutter) and dark-background comics (bright figures on black gutter) uniformly.
         *
         * 50 (raised from 32): JPEG compression introduces artifact pixels in gutter rows at
         * roughly v ≈ 190–210 on a 240-background page (bg − v ≈ 30–50). At 32 those pixels
         * were classified as content, contaminating gutter rows and causing the projection to
         * merge adjacent row bands (e.g. a narrow banner with the section below). At 50 only
         * genuinely dark ink (v < 190, bg − v > 50) is classified as content; the texture
         * check catches any fine-grained panel content that is below this threshold.
         * Pre-binarised fixture masks have pixels at only DARK=20 and LIGHT=240 so both 32
         * and 50 produce identical results on fixtures — the threshold only matters for real
         * JPEG input on device.
         */
        val backgroundContrastThreshold: Int = 50,

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

        /**
         * Maximum recursion depth for [splitSinglePanelRecursively]. Bounds the number of times
         * a single detected panel can be split at internal gutters. 3 is plenty for real
         * comic layouts (2^3 = 8 sub-panels from one merged CC blob).
         */
        val maxInternalGutterSplitDepth: Int = 3,

        /**
         * A row/column inside a CC bbox is a real internal gutter if at least this fraction of
         * its pixels are flood-fill gutter pixels (i.e. background reachable from the page
         * border). 0.3 = 30% of the row must be page-gutter. Genuine gutters between adjacent
         * panels score ~100% (fully reachable); hollow panel interiors score 0% (enclosed by
         * the panel border, not reachable from outside). This replaces the old content-fraction
         * heuristic which fired on speech balloon interiors and dark-tone panel shadows.
         */
        val internalGutterFloodFillFraction: Double = 0.3,

        /**
         * An internal gutter whose run thickness exceeds this fraction of the bbox's perpendicular
         * dimension is rejected as a false gutter. Genuine inter-panel gutters are narrow relative
         * to the panels they separate (a 12px gutter between two 400px panels = 1.5% of bbox
         * height). A thick "gutter" indicates a flood-fill-accessible panel interior reached via a
         * downscaling border gap — those must not be split or the panel disappears.
         */
        val internalGutterMaxFraction: Double = 0.25,

        /**
         * Ignore internal gutters this close to the panel edge (fraction of panel dimension).
         * Prevents spurious "split off the top 5% of a panel" from a low-content strip near
         * the border.
         */
        val internalGutterEdgeMargin: Double = 0.1,

        /**
         * An internal gutter must be at least this many pixels thick to trigger a split. Below
         * this, the "gutter" is likely a coincidental low-content row inside a real panel
         * (e.g. a wide dark shadow).
         */
        val internalGutterMinThickness: Int = 4,

        /**
         * When looking for an internal gutter inside a bbox, sample only the INNER
         * `(1 - 2 * this)` fraction on the perpendicular axis. Prevents decorative page borders
         * / bleed art at the bbox edges from disqualifying rows/columns that are genuinely
         * gutter through the panel interior. 0.1 = ignore the outer 10% on each side.
         */
        val internalGutterInnerSampleInset: Double = 0.1,
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
        return detectFromMask(mask, grid.width, grid.height, pageIndex, originalWidth, originalHeight, fallback)
    }

    /**
     * Detect panels from a [PanelBinaryMask] produced by [PanelMaskBinarizer.binarize], bypassing
     * the binarization step. Use this when the mask is already authoritative (e.g. when verifying
     * that a stored mask produces the same layout as the original page — re-binarizing the mask
     * would introduce texture-border drift that changes the result).
     */
    fun detect(
        mask: PanelBinaryMask,
        pageIndex: Int,
        originalWidth: Int,
        originalHeight: Int,
    ): PagePanels {
        require(originalWidth > 0 && originalHeight > 0) { "original dimensions must be positive" }
        val fallback = fitWhole(pageIndex, originalWidth, originalHeight)
        val binaryMask = BinaryMask(mask.width, mask.height, mask.data)
        return detectFromMask(binaryMask, mask.width, mask.height, pageIndex, originalWidth, originalHeight, fallback)
    }

    private fun detectFromMask(
        mask: BinaryMask,
        downscaledWidth: Int,
        downscaledHeight: Int,
        pageIndex: Int,
        originalWidth: Int,
        originalHeight: Int,
        fallback: PagePanels,
    ): PagePanels {
        val cropped = trimMargin(mask) ?: return fallback

        val projResult = gridByProjection(cropped, pageIndex, originalWidth, originalHeight, downscaledWidth, downscaledHeight)
        if (projResult != null) return projResult

        val gutter = floodFillGutter(cropped)
        val components = connectedComponents(cropped, gutter)
        val filtered = filterAndTighten(components, cropped)
        val afterSplit = splitAtInternalGutters(filtered, cropped, gutter, downscaledWidth, downscaledHeight)
        val afterJunc = repairOneSidedRowJunctions(afterSplit, cropped, downscaledWidth, downscaledHeight)
        val afterMerge = mergeDiagonalSpanningPanels(afterJunc, downscaledWidth, downscaledHeight)
        val split = repairDiagonalTwoColumnRows(afterMerge, cropped, gutter, downscaledWidth, downscaledHeight)
        val afterExpand = expandDiagonalBboxOverlaps(split)

        val result = sanityCheck(
            candidates = afterExpand,
            cropped = cropped,
            originalWidth = originalWidth,
            originalHeight = originalHeight,
            downscaledWidth = downscaledWidth,
            downscaledHeight = downscaledHeight,
            pageIndex = pageIndex,
        )
        return result ?: fallback
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
            // Thin horizontal strips are usually panel borders/noise attached to the next row,
            // not standalone reading panels.
            .filterNot { it.end - it.start + 1 < 24 }
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

        // A row whose only column band spans ≥ 90 % of the cropped width is "suspicious":
        // either the projection couldn't find interior column gutters (gutters too narrow, or
        // dark art pixels pushed column counts above the gutter cutoff), or the row is a
        // genuine full-width splash panel.
        //
        // When ALL rows are suspicious we can't distinguish the two cases and the CC path is
        // better equipped to recover — it operates on flood-fill gutter connectivity, not
        // projection valleys, so narrow gutters that fool the projection are still found.
        //
        // When only SOME rows are suspicious we're almost certainly looking at a
        // "splash-on-top / panels-below" layout (or the mirror).  In that case, keeping the
        // suspicious rows as single full-width cells and the non-suspicious rows with their
        // proper column subdivisions is correct and far better than handing everything to the
        // CC path: the CC path merges splash art with the adjacent panel borders when there is
        // no clean gutter row between them, causing splitAtInternalGutters to find only a
        // vertical split and emit two wrong half-height panels.
        fun isSuspicious(colBands: List<Band>) =
            colBands.size == 1 && colBands[0].let { it.end - it.start + 1 } >= cropped.width * 0.9
        val allSuspicious = bandColBands.all { isSuspicious(it) }
        if (allSuspicious) return null

        val rawBboxes = mutableListOf<Bbox>()
        for ((rowIndex, rowBand) in rowBands.withIndex()) {
            for (colBand in bandColBands[rowIndex]) {
                rawBboxes.add(Bbox(colBand.start, rowBand.start, colBand.end, rowBand.end))
            }
        }

        // Projection bboxes can straddle two real panels whose shared gutter was too narrow for
        // projectionMinBandThickness (e.g. 12px gutter on a scanned page that merges rows 1+2
        // into one tall column strip). Apply the same flood-fill split used in the CC path: a
        // genuine inter-panel gutter is connected to the page border (~100% accessible), while a
        // closed panel interior scores 0% — so the 30% threshold distinguishes them reliably.
        val gutter = floodFillGutter(cropped)
        val projAfterSplit = rawBboxes.flatMap { splitSinglePanelRecursively(it, cropped, gutter, depth = 0, downscaledWidth = downscaledWidth, downscaledHeight = downscaledHeight) }
        val projAfterJunc = repairOneSidedRowJunctions(projAfterSplit, cropped, downscaledWidth, downscaledHeight)
        val projAfterMerge = mergeDiagonalSpanningPanels(projAfterJunc, downscaledWidth, downscaledHeight)
        val projAfterRepair = repairDiagonalTwoColumnRows(projAfterMerge, cropped, gutter, downscaledWidth, downscaledHeight)
        val bboxesInCropped = expandDiagonalBboxOverlaps(projAfterRepair)

        val scaleX = originalWidth.toDouble() / downscaledWidth.toDouble()
        val scaleY = originalHeight.toDouble() / downscaledHeight.toDouble()
        val regions = bboxesInCropped.map { bbox ->
            val minX = ((bbox.minX + cropped.offsetX) * scaleX).toInt().coerceIn(0, originalWidth - 1)
            val minY = ((bbox.minY + cropped.offsetY) * scaleY).toInt().coerceIn(0, originalHeight - 1)
            val maxX = ((bbox.maxX + 1 + cropped.offsetX) * scaleX).toInt().coerceIn(1, originalWidth)
            val maxY = ((bbox.maxY + 1 + cropped.offsetY) * scaleY).toInt().coerceIn(1, originalHeight)
            PanelRegion(
                x = minX,
                y = minY,
                width = (maxX - minX).coerceAtLeast(1),
                height = (maxY - minY).coerceAtLeast(1),
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

    /**
     * Recursively split any CC bbox that contains a full-crossing internal gutter — a run of
     * rows or columns where ≥ [Config.internalGutterFloodFillFraction] of pixels are
     * flood-fill gutter (background reachable from the page border).
     *
     * This catches the CC failure mode where two real adjacent panels are merged into one bbox
     * because a stray pixel bridged their shared gutter. The flood-fill criterion is crucial:
     * genuine between-panel gutters are fully reachable (~100% gutter pixels), while hollow
     * panel interiors and dark-tone panel backgrounds are enclosed by the panel border and
     * score 0% — so they never trigger a false split.
     */
    private fun splitAtInternalGutters(bboxes: List<Bbox>, cropped: CroppedMask, gutter: BooleanArray, downscaledWidth: Int, downscaledHeight: Int): List<Bbox> =
        bboxes.flatMap { splitSinglePanelRecursively(it, cropped, gutter, depth = 0, downscaledWidth = downscaledWidth, downscaledHeight = downscaledHeight) }

    private fun repairOneSidedRowJunctions(
        bboxes: List<Bbox>,
        cropped: CroppedMask,
        downscaledWidth: Int,
        downscaledHeight: Int,
    ): List<Bbox> {
        if (bboxes.size < 2) return bboxes
        val consumed = BooleanArray(bboxes.size)
        val repaired = mutableListOf<Bbox>()
        val sortedIndices = bboxes.indices.sortedWith(compareBy({ bboxes[it].minY }, { bboxes[it].minX }))
        for (i in sortedIndices) {
            if (consumed[i]) continue
            val top = bboxes[i]
            val bottomIndex = sortedIndices.firstOrNull { j ->
                if (i == j || consumed[j]) return@firstOrNull false
                val bottom = bboxes[j]
                val verticalGap = bottom.minY - top.maxY - 1
                verticalGap in 0..12 &&
                    similarFullWidthRows(top, bottom, downscaledWidth, downscaledHeight)
            }
            if (bottomIndex == null) {
                repaired.add(top)
                consumed[i] = true
                continue
            }

            val replacement = repairOneSidedRowJunction(top, bboxes[bottomIndex], cropped, downscaledWidth, downscaledHeight)
            if (replacement == null) {
                repaired.add(top)
                consumed[i] = true
            } else {
                repaired.addAll(replacement)
                consumed[i] = true
                consumed[bottomIndex] = true
            }
        }
        for (i in bboxes.indices) {
            if (!consumed[i]) repaired.add(bboxes[i])
        }
        return repaired
    }

    private fun similarFullWidthRows(top: Bbox, bottom: Bbox, downscaledWidth: Int, downscaledHeight: Int): Boolean {
        val topWidth = top.maxX - top.minX + 1
        val bottomWidth = bottom.maxX - bottom.minX + 1
        val minWide = downscaledWidth * 0.75
        val minHeight = (downscaledHeight * 0.12).toInt().coerceAtLeast(1)
        return topWidth >= minWide &&
            bottomWidth >= minWide &&
            top.maxX >= bottom.minX &&
            bottom.maxX >= top.minX &&
            top.maxY - top.minY + 1 >= minHeight &&
            bottom.maxY - bottom.minY + 1 >= minHeight &&
            kotlin.math.abs(top.minX - bottom.minX) <= downscaledWidth * 0.05 &&
            kotlin.math.abs(top.maxX - bottom.maxX) <= downscaledWidth * 0.05
    }

    private fun repairOneSidedRowJunction(
        top: Bbox,
        bottom: Bbox,
        cropped: CroppedMask,
        downscaledWidth: Int,
        downscaledHeight: Int,
    ): List<Bbox>? {
        val gapStart = top.maxY + 1
        val gapEnd = bottom.minY - 1
        if (gapStart > gapEnd) return null
        val minX = maxOf(top.minX, bottom.minX)
        val maxX = minOf(top.maxX, bottom.maxX)
        val width = maxX - minX + 1
        if (width < downscaledWidth * 0.6) return null

        val stripHeight = gapEnd - gapStart + 1
        val gutterByX = BooleanArray(width) { offset ->
            val x = minX + offset
            var content = 0
            for (y in gapStart..gapEnd) {
                if (cropped.data[y * cropped.width + x] == 1.toByte()) content++
            }
            content.toLong() * 1000 <= stripHeight.toLong() * 150
        }
        val edgeInset = (width * 0.08).toInt().coerceAtLeast(1)
        val minSideRun = (width * 0.30).toInt().coerceAtLeast(1)
        val rightRun = edgeGutterRun(gutterByX, fromRight = true, edgeInset)?.takeIf { (_, thickness) -> thickness >= minSideRun }
        val leftRun = edgeGutterRun(gutterByX, fromRight = false, edgeInset)?.takeIf { (_, thickness) -> thickness >= minSideRun }
        if ((rightRun == null) == (leftRun == null)) return null

        val minPanelWidth = (downscaledWidth * config.minPanelDimensionFraction).toInt().coerceAtLeast(1)
        val minPanelHeight = (downscaledHeight * config.minPanelDimensionFraction).toInt().coerceAtLeast(1)
        val minSpanningWidth = (downscaledWidth * 0.25).toInt().coerceAtLeast(minPanelWidth)
        return if (rightRun != null) {
            val splitX = (minX + rightRun.first - (width * 0.05).toInt()).coerceIn(minX + minPanelWidth, maxX - minPanelWidth)
            val spanning = Bbox(top.minX, top.minY, splitX - 1, bottom.maxY)
            val topSplit = Bbox(splitX, top.minY, top.maxX, top.maxY)
            val bottomSplit = Bbox(splitX, bottom.minY, bottom.maxX, bottom.maxY)
            listOf(spanning, topSplit, bottomSplit).takeIf { parts ->
                parts.all { it.maxX >= it.minX && it.maxY >= it.minY } &&
                    spanning.maxX - spanning.minX + 1 >= minSpanningWidth &&
                    spanning.maxY - spanning.minY + 1 >= minPanelHeight
            }
        } else {
            val leftEnd = minX + leftRun!!.first + leftRun.second - 1
            val splitX = (leftEnd + 1 + (width * 0.05).toInt()).coerceIn(minX + minPanelWidth, maxX - minPanelWidth)
            val topSplit = Bbox(top.minX, top.minY, splitX - 1, top.maxY)
            val bottomSplit = Bbox(bottom.minX, bottom.minY, splitX - 1, bottom.maxY)
            val spanning = Bbox(splitX, top.minY, top.maxX, bottom.maxY)
            listOf(topSplit, bottomSplit, spanning).takeIf { parts ->
                parts.all { it.maxX >= it.minX && it.maxY >= it.minY } &&
                    spanning.maxX - spanning.minX + 1 >= minSpanningWidth &&
                    spanning.maxY - spanning.minY + 1 >= minPanelHeight
            }
        }
    }

    private fun edgeGutterRun(gutterByX: BooleanArray, fromRight: Boolean, edgeInset: Int): Pair<Int, Int>? {
        if (gutterByX.isEmpty()) return null
        return if (fromRight) {
            var end = gutterByX.lastIndex - edgeInset
            while (end >= 0 && gutterByX[end]) end--
            val runStart = end + 1
            val runEnd = gutterByX.lastIndex - edgeInset
            if (runStart <= runEnd) runStart to (runEnd - runStart + 1) else null
        } else {
            var start = edgeInset
            while (start < gutterByX.size && gutterByX[start]) start++
            if (start > edgeInset) edgeInset to (start - edgeInset) else null
        }
    }

    /**
     * Detects the "diagonal spanning panel" layout: a tall left (or right) panel whose right (or
     * left) boundary is a large diagonal slash creates two separate CCs after flood-fill — the top
     * portion is wider, the bottom is narrower — because the horizontal gutter between rows cleanly
     * cuts both the content and the diagonal zone.
     *
     * Signature: two vertically adjacent bboxes with the same left edge (within 5% of page width)
     * where the top bbox is significantly wider on the right (≥ 15% of page width). Neither
     * [repairOneSidedRowJunctions] (requires ≥ 60% shared width) nor [repairDiagonalTwoColumnRows]
     * (requires a full-width single bbox) handles this. Here we merge the pair into one tall bbox
     * spanning minY of the top to maxY of the bottom, with maxX = max(top.maxX, bottom.maxX).
     *
     * The symmetric case (bottom wider than top) and right-edge variant (same right edge, left
     * edges differ) are handled by rotating the roles.
     */
    internal fun mergeDiagonalSpanningPanels(
        bboxes: List<Bbox>,
        downscaledWidth: Int,
        downscaledHeight: Int,
    ): List<Bbox> {
        if (bboxes.size < 2) return bboxes
        // Allow a horizontal gutter row band between the two CC chunks (≤ 2% of page height, min 15px).
        val maxGap = (downscaledHeight * 0.02).toInt().coerceAtLeast(15)
        // Both panels must be at least 10% of page height (rules out thin caption strips).
        val minPanelH = (downscaledHeight * 0.10).toInt()
        // Minimum right-edge difference (15% of page width) to distinguish a diagonal split from
        // scanner jitter between two regular-grid panels with the same left edge.
        val minEdgeDiff = (downscaledWidth * 0.15).toInt()
        // Allow the left edges to differ by up to 5% of page width (scanner jitter).
        val edgeTolerance = (downscaledWidth * 0.05).toInt().coerceAtLeast(5)

        val result = mutableListOf<Bbox>()
        val consumed = BooleanArray(bboxes.size)

        for (i in bboxes.indices) {
            if (consumed[i]) continue
            val top = bboxes[i]
            if (top.maxY - top.minY + 1 < minPanelH) { result.add(top); continue }

            var bestJ = -1
            for (j in bboxes.indices) {
                if (i == j || consumed[j]) continue
                val bot = bboxes[j]
                val gap = bot.minY - top.maxY - 1
                if (gap !in 0..maxGap) continue
                if (bot.maxY - bot.minY + 1 < minPanelH) continue
                // Same left edge, one extends significantly further right than the other.
                // Guard: the WIDER panel's right edge must cross the page centre — proves it's a
                // genuine diagonal split, not just two narrow left panels with minor jitter.
                val sameLeft = kotlin.math.abs(top.minX - bot.minX) <= edgeTolerance
                val edgeDiff = kotlin.math.abs(top.maxX - bot.maxX)
                val widerMaxX = maxOf(top.maxX, bot.maxX)
                // The wider panel must reach past page centre (real diagonal span) but must NOT
                // extend to the right page edge — that would be a full-width banner, not a
                // diagonal-slash panel, and merging it with the narrow column panel above/below
                // would produce a false wide bbox.
                val widthCap = downscaledWidth * 9 / 10
                // Guard: if the TOP panel is the wider one and its right edge extends past 80 % of
                // page width, it is almost certainly a horizontal splash or title banner — NOT the
                // top half of a diagonal left panel.  A real row1-left diagonal panel only reaches
                // 40–60 % of page width; a banner reaches 89–90 %.  Merging a banner with the
                // narrower left panel below swallows the banner's full right extent.
                val topIsWider = top.maxX > bot.maxX
                val topIsBanner = topIsWider && top.maxX > downscaledWidth * 4 / 5
                if (sameLeft && edgeDiff >= minEdgeDiff && widerMaxX > downscaledWidth / 2 && widerMaxX <= widthCap && !topIsBanner) {
                    bestJ = j
                    break
                }
            }
            if (bestJ < 0) {
                result.add(top)
                continue
            }
            val bot = bboxes[bestJ]
            consumed[bestJ] = true
            // Use the NARROWER right edge (min of the two) so the merged bbox does not extend
            // into the adjacent right-column panel's territory — the wider panel's diagonal
            // boundary means its extra width in that row overlaps the right column, which would
            // trigger the overlap sanity check and cause a Fallback result.
            val mergedMaxX = minOf(top.maxX, bot.maxX)
            result.add(
                Bbox(
                    minOf(top.minX, bot.minX),
                    top.minY,
                    mergedMaxX,
                    bot.maxY,
                ),
            )
            // When projection lumps two side-by-side panels in the bot row into a single
            // full-width bbox (because their gutter was below the detection threshold), the merge
            // above only keeps the left portion up to mergedMaxX.  Recover the right portion so it
            // is not silently lost.
            if (bot.maxX > top.maxX && bot.maxX - mergedMaxX > minEdgeDiff) {
                result.add(Bbox(mergedMaxX + 1, bot.minY, bot.maxX, bot.maxY))
            }
        }
        return result
    }

    /**
     * When two horizontally-adjacent bboxes have overlapping x-extents (left.maxX > right.minX),
     * their shared overlap zone belongs to a DIAGONAL panel boundary. The CC tighten pass stopped
     * each panel at the extent of its own ink content, leaving both panels short of the visual
     * boundary. Expand each panel by half the overlap width so both panels include the full
     * diagonal transition zone, preventing the left panel from appearing visually cut off.
     *
     * Only fires when: (1) bboxes overlap by 1-60px horizontally, (2) their y-ranges share ≥50%
     * of the shorter panel's height (same row band), and (3) neither expanded panel would become
     * empty. Non-overlapping panels (vertical gutters between them) are unchanged.
     */
    private fun expandDiagonalBboxOverlaps(bboxes: List<Bbox>): List<Bbox> {
        if (bboxes.size < 2) return bboxes
        val result = bboxes.toMutableList()
        for (i in result.indices) {
            for (j in result.indices) {
                if (i >= j) continue
                val a = result[i]
                val b = result[j]
                // Identify which is left and which is right (by minX)
                val (leftIdx, rightIdx) = if (a.minX <= b.minX) i to j else j to i
                val left = result[leftIdx]
                val right = result[rightIdx]
                // Check for horizontal overlap (diagonal boundary signature)
                val overlapX = left.maxX - right.minX + 1
                if (overlapX !in 1..60) continue
                // Check same row band (y-ranges share ≥50% of shorter panel's height)
                val yOverlapTop = maxOf(left.minY, right.minY)
                val yOverlapBottom = minOf(left.maxY, right.maxY)
                if (yOverlapBottom <= yOverlapTop) continue
                val yOverlap = yOverlapBottom - yOverlapTop
                val shorterH = minOf(left.maxY - left.minY + 1, right.maxY - right.minY + 1)
                if (yOverlap.toDouble() / shorterH < 0.5) continue
                // Expand each panel by half the horizontal overlap into the other's zone
                val pad = overlapX / 2
                if (pad <= 0) continue
                result[leftIdx] = Bbox(left.minX, left.minY, left.maxX + pad, left.maxY)
                result[rightIdx] = Bbox(right.minX - pad, right.minY, right.maxX, right.maxY)
            }
        }
        return result
    }

    private fun repairDiagonalTwoColumnRows(
        bboxes: List<Bbox>,
        cropped: CroppedMask,
        gutter: BooleanArray,
        downscaledWidth: Int,
        downscaledHeight: Int,
    ): List<Bbox> {
        val repaired = mutableListOf<Bbox>()
        for (bbox in bboxes) {
            val hasFullWidthRowBelow = bboxes.any { other ->
                other != bbox &&
                    other.minY - bbox.maxY - 1 in 0..12 &&
                    similarFullWidthRows(bbox, other, downscaledWidth, downscaledHeight)
            }
            repaired.addAll(
                if (hasFullWidthRowBelow) {
                    repairDiagonalTwoColumnRow(bbox, cropped, gutter, downscaledWidth, downscaledHeight) ?: listOf(bbox)
                } else {
                    listOf(bbox)
                },
            )
        }
        return repaired
    }

    private fun repairDiagonalTwoColumnRow(
        bbox: Bbox,
        cropped: CroppedMask,
        gutter: BooleanArray,
        downscaledWidth: Int,
        downscaledHeight: Int,
    ): List<Bbox>? {
        val width = bbox.maxX - bbox.minX + 1
        val height = bbox.maxY - bbox.minY + 1
        if (width.toDouble() / downscaledWidth < 0.85) return null
        if (height.toDouble() / downscaledHeight !in 0.16..0.32) return null

        val stripHeight = (height * 0.20).toInt().coerceAtLeast(12)
        val topRun = interiorFloodGutterRun(
            bbox = bbox,
            yStart = bbox.minY,
            yEnd = (bbox.minY + stripHeight - 1).coerceAtMost(bbox.maxY),
            cropped = cropped,
            gutter = gutter,
        ) ?: interiorProjectionGutterRun(
            bbox = bbox,
            yStart = bbox.minY,
            yEnd = (bbox.minY + stripHeight - 1).coerceAtMost(bbox.maxY),
            cropped = cropped,
        )
        val bottomRun = interiorFloodGutterRun(
            bbox = bbox,
            yStart = (bbox.maxY - stripHeight + 1).coerceAtLeast(bbox.minY),
            yEnd = bbox.maxY,
            cropped = cropped,
            gutter = gutter,
        ) ?: interiorProjectionGutterRun(
            bbox = bbox,
            yStart = (bbox.maxY - stripHeight + 1).coerceAtLeast(bbox.minY),
            yEnd = bbox.maxY,
            cropped = cropped,
        )
        topRun ?: return null
        bottomRun ?: return null

        val topCenter = (topRun.first + topRun.second) / 2
        val bottomCenter = (bottomRun.first + bottomRun.second) / 2
        val diagonalShift = kotlin.math.abs(topCenter - bottomCenter)
        if (diagonalShift < downscaledWidth * 0.025) return null
        if (diagonalShift > downscaledWidth * 0.12) return null

        val minPanelWidth = (downscaledWidth * config.minPanelDimensionFraction).toInt().coerceAtLeast(1)
        val overlapPad = (width * 0.02).toInt().coerceAtLeast(12)
        val leftMax = (maxOf(topRun.second, bottomRun.second) + overlapPad)
            .coerceIn(bbox.minX + minPanelWidth, bbox.maxX - minPanelWidth)
        val rightMin = (minOf(topRun.first, bottomRun.first) - overlapPad)
            .coerceIn(bbox.minX + minPanelWidth, bbox.maxX - minPanelWidth)
        if (rightMin >= leftMax) return null

        val left = Bbox(bbox.minX, bbox.minY, leftMax, bbox.maxY)
        val right = Bbox(rightMin, bbox.minY, bbox.maxX, bbox.maxY)
        return listOf(left, right)
    }

    private fun interiorFloodGutterRun(
        bbox: Bbox,
        yStart: Int,
        yEnd: Int,
        cropped: CroppedMask,
        gutter: BooleanArray,
    ): Pair<Int, Int>? {
        val xInset = ((bbox.maxX - bbox.minX + 1) * 0.18).toInt().coerceAtLeast(1)
        val xStart = bbox.minX + xInset
        val xEnd = bbox.maxX - xInset
        if (xStart >= xEnd) return null

        val stripHeight = yEnd - yStart + 1
        var bestStart = -1
        var bestEnd = -1
        var currentStart = -1
        for (x in xStart..xEnd) {
            var gutterCount = 0
            for (y in yStart..yEnd) {
                if (gutter[y * cropped.width + x]) gutterCount++
            }
            if (gutterCount.toLong() * 1000 >= stripHeight.toLong() * 450) {
                if (currentStart < 0) currentStart = x
            } else if (currentStart >= 0) {
                if (isNarrowDiagonalGutterRun(currentStart, x - 1, bestStart, bestEnd)) {
                    bestStart = currentStart
                    bestEnd = x - 1
                }
                currentStart = -1
            }
        }
        if (currentStart >= 0 && isNarrowDiagonalGutterRun(currentStart, xEnd, bestStart, bestEnd)) {
            bestStart = currentStart
            bestEnd = xEnd
        }
        return if (bestStart >= 0) bestStart to bestEnd else null
    }

    private fun interiorProjectionGutterRun(
        bbox: Bbox,
        yStart: Int,
        yEnd: Int,
        cropped: CroppedMask,
    ): Pair<Int, Int>? {
        val xInset = ((bbox.maxX - bbox.minX + 1) * 0.18).toInt().coerceAtLeast(1)
        val xStart = bbox.minX + xInset
        val xEnd = bbox.maxX - xInset
        if (xStart >= xEnd) return null

        val projection = IntArray(xEnd - xStart + 1) { offset ->
            cropped.colContentCount(xStart + offset, yStart, yEnd)
        }
        val maxContent = projection.maxOrNull() ?: return null
        if (maxContent <= 0) return null
        val cutoff = maxContent * 0.20
        var bestStart = -1
        var bestEnd = -1
        var currentStart = -1
        for (i in projection.indices) {
            if (projection[i] < cutoff) {
                if (currentStart < 0) currentStart = i
            } else if (currentStart >= 0) {
                if (isNarrowDiagonalGutterRun(currentStart, i - 1, bestStart, bestEnd)) {
                    bestStart = currentStart
                    bestEnd = i - 1
                }
                currentStart = -1
            }
        }
        if (currentStart >= 0 && isNarrowDiagonalGutterRun(currentStart, projection.lastIndex, bestStart, bestEnd)) {
            bestStart = currentStart
            bestEnd = projection.lastIndex
        }
        return if (bestStart >= 0) (xStart + bestStart) to (xStart + bestEnd) else null
    }

    private fun isNarrowDiagonalGutterRun(start: Int, end: Int, bestStart: Int, bestEnd: Int): Boolean {
        val thickness = end - start + 1
        if (thickness !in 5..40) return false
        if (bestStart < 0) return true
        return thickness > bestEnd - bestStart + 1
    }

    private fun splitSinglePanelRecursively(bbox: Bbox, cropped: CroppedMask, gutter: BooleanArray, depth: Int, downscaledWidth: Int, downscaledHeight: Int): List<Bbox> {
        if (depth >= config.maxInternalGutterSplitDepth) return listOf(bbox)
        val height = bbox.maxY - bbox.minY + 1
        val width = bbox.maxX - bbox.minX + 1
        val minSplitDim = 20
        if (width < minSplitDim * 2 || height < minSplitDim * 2) return listOf(bbox)

        val useInnerWidthSample = width >= (cropped.width * 0.7)
        val useInnerHeightSample = height >= (cropped.height * 0.7)

        val edgeMarginY = (height * config.internalGutterEdgeMargin).toInt().coerceAtLeast(2)
        val innerMarginX = if (useInnerWidthSample) (width * config.internalGutterInnerSampleInset).toInt().coerceAtLeast(0) else 0
        val innerMinX = bbox.minX + innerMarginX
        val innerMaxX = bbox.maxX - innerMarginX
        val innerWidth = (innerMaxX - innerMinX + 1).coerceAtLeast(1)
        val horizontalGutter = widestGutterRun(
            axisStart = bbox.minY + edgeMarginY,
            axisEnd = bbox.maxY - edgeMarginY,
        ) { y ->
            val base = y * cropped.width
            var g = 0
            for (x in innerMinX..innerMaxX) if (gutter[base + x]) g++
            g.toLong() * 1000 >= innerWidth.toLong() * (config.internalGutterFloodFillFraction * 1000).toLong()
        }

        val edgeMarginX = (width * config.internalGutterEdgeMargin).toInt().coerceAtLeast(2)
        val innerMarginY = if (useInnerHeightSample) (height * config.internalGutterInnerSampleInset).toInt().coerceAtLeast(0) else 0
        val innerMinY = bbox.minY + innerMarginY
        val innerMaxY = bbox.maxY - innerMarginY
        val innerHeight = (innerMaxY - innerMinY + 1).coerceAtLeast(1)
        val verticalGutter = widestGutterRun(
            axisStart = bbox.minX + edgeMarginX,
            axisEnd = bbox.maxX - edgeMarginX,
        ) { x ->
            var g = 0
            for (y in innerMinY..innerMaxY) if (gutter[y * cropped.width + x]) g++
            g.toLong() * 1000 >= innerHeight.toLong() * (config.internalGutterFloodFillFraction * 1000).toLong()
        }

        // Projection-based fallback for enclosed gutters. When two adjacent panels share a border
        // that forms a closed ring (only a thin gap connects the gutter to the exterior),
        // flood-fill scores < 30% and either finds nothing or detects a border-edge "gutter" that
        // would produce an invalid split (one resulting side too narrow). In both cases fall back
        // to content projection: a real gutter is a continuous run of ≥ 7 rows/columns where
        // content < 20% of the bbox peak — this threshold reliably discriminates real inter-panel
        // gutters from sparse artwork dips (typically only 1–5 rows/columns).
        val floodFillWouldSplit = horizontalGutter?.let { (start, thickness) ->
            val end = start + thickness - 1
            val minDimPx = (downscaledHeight * config.minPanelDimensionFraction).toInt().coerceAtLeast(1)
            (start - bbox.minY) >= minDimPx && (bbox.maxY - end) >= minDimPx
        } == true
        val effectiveHorizontalGutter: Pair<Int, Int>? = if (floodFillWouldSplit) horizontalGutter else run {
            val maxRowContent = (bbox.minY..bbox.maxY).maxOf { y ->
                cropped.rowContentCount(y, innerMinX, innerMaxX)
            }
            if (maxRowContent <= 0) return@run null
            val contentCutoff = maxRowContent * 0.20
            val projGutter = widestGutterRun(
                axisStart = bbox.minY + edgeMarginY,
                axisEnd = bbox.maxY - edgeMarginY,
            ) { y ->
                cropped.rowContentCount(y, innerMinX, innerMaxX) < contentCutoff
            }
            // Full ≥7-row threshold for general (non-banner) splits — keeps the guard above
            // typical 1–5-row sparse-artwork noise.
            projGutter?.takeIf { (_, thickness) -> thickness >= 7 }
                // Thin-gutter banner fallback: at device scale (inSampleSize=2) the gutter between
                // a banner and its adjacent section shrinks to ~4 rows after panel borders are
                // removed, falling below the 7-row floor. Accept ≥4 rows when the split is
                // banner-eligible (bbox ≥50% wide, short piece ≥5% of page AND ≥25% of bbox).
                // The banner conditions prevent this relaxation from firing on sparse artwork
                // dips — those lack the width + height combination that characterises a real banner.
                ?: projGutter?.takeIf { (start, thickness) ->
                    thickness >= 4 && run {
                        val end = start + thickness - 1
                        val topH = start - bbox.minY
                        val bottomH = bbox.maxY - end
                        val bboxWidthFraction = width.toDouble() / downscaledWidth.toDouble()
                        val bannerMinH = (downscaledHeight * 0.05).toInt().coerceAtLeast(1)
                        val bannerBboxMinH = (height * 0.25).toInt().coerceAtLeast(1)
                        bboxWidthFraction >= 0.5 &&
                            minOf(topH, bottomH) >= bannerMinH &&
                            minOf(topH, bottomH) >= bannerBboxMinH
                    }
                }
                // If projection found nothing, fall back to a thin flood-fill gutter when
                // banner-eligible. Flood-fill accessibility confirms the gutter connects to the
                // page border; the banner conditions guard against caption-box false splits.
                ?: horizontalGutter?.takeIf { (start, thickness) ->
                    val end = start + thickness - 1
                    val topH = start - bbox.minY
                    val bottomH = bbox.maxY - end
                    val bboxWidthFraction = width.toDouble() / downscaledWidth.toDouble()
                    val bannerMinH = (downscaledHeight * 0.05).toInt().coerceAtLeast(1)
                    val bannerBboxMinH = (height * 0.25).toInt().coerceAtLeast(1)
                    bboxWidthFraction >= 0.5 &&
                        minOf(topH, bottomH) >= bannerMinH &&
                        minOf(topH, bottomH) >= bannerBboxMinH
                }
        }

        // Same projection-based fallback for vertical (column) gutters. Covers the symmetric case
        // where two side-by-side panels have an enclosed vertical gutter that flood-fill can't reach.
        // Uses a 10% threshold (vs 20% for horizontal) to reject sparse-content columns in artwork
        // that are not real gutters — vertical artwork dips are more common than horizontal ones.
        val effectiveVerticalGutter: Pair<Int, Int>? = verticalGutter ?: run {
            val maxColContent = (bbox.minX..bbox.maxX).maxOf { x ->
                cropped.colContentCount(x, innerMinY, innerMaxY)
            }
            if (maxColContent <= 0) return@run null
            val contentCutoff = maxColContent * 0.10
            widestGutterRun(
                axisStart = bbox.minX + edgeMarginX,
                axisEnd = bbox.maxX - edgeMarginX,
            ) { x ->
                cropped.colContentCount(x, innerMinY, innerMaxY) < contentCutoff
            }?.takeIf { (_, thickness) -> thickness >= 7 }
        }

        val hGutter = effectiveHorizontalGutter?.let { Triple("h", it.first, it.second) }
        val vGutter = effectiveVerticalGutter?.let { Triple("v", it.first, it.second) }
        val bestGutter = run {
            if (hGutter != null && vGutter != null) {
                // When both H and V gutters are available, prefer H if it would produce a
                // valid banner split. Without this preference the (typically thicker) vertical
                // column gutter wins and splits the bbox into ~50%-wide halves; the banner piece
                // in each half is then too narrow to pass the ≥50% width check, so it is never
                // separated from its neighbour. Choosing H first keeps the full bbox width
                // intact and lets the banner exception fire at the correct level.
                val (_, hStart, hThick) = hGutter
                val hEnd = hStart + hThick - 1
                val topH = hStart - bbox.minY
                val bottomH = bbox.maxY - hEnd
                val minDimPx = (downscaledHeight * config.minPanelDimensionFraction).toInt().coerceAtLeast(1)
                val bannerEligible = (topH < minDimPx || bottomH < minDimPx) &&
                    width.toDouble() / downscaledWidth >= 0.5 &&
                    minOf(topH, bottomH) >= (downscaledHeight * 0.05).toInt().coerceAtLeast(1) &&
                    minOf(topH, bottomH) >= (height * 0.25).toInt().coerceAtLeast(1)
                if (bannerEligible) hGutter
                else listOfNotNull(hGutter, vGutter).maxByOrNull { it.third }!!
            } else {
                listOfNotNull(hGutter, vGutter).maxByOrNull { it.third }
            }
        } ?: return listOf(bbox)

        if (bestGutter.third < config.internalGutterMinThickness) return listOf(bbox)
        val maxGutterThickness = when (bestGutter.first) {
            "h" -> (height * config.internalGutterMaxFraction).toInt()
            else -> (width * config.internalGutterMaxFraction).toInt()
        }
        if (bestGutter.third > maxGutterThickness) return listOf(bbox)

        val (axis, start, thickness) = bestGutter
        val end = start + thickness - 1

        // A short full-width band is usually a banner/splash row. White speech balloons and
        // lighting gaps inside it can look like vertical gutters, but splitting them produces
        // narrow side fragments instead of real panels.
        if (
            axis == "v" &&
            width.toDouble() / downscaledWidth.toDouble() >= 0.95 &&
            height.toDouble() / downscaledHeight.toDouble() <= 0.25
        ) {
            return listOf(bbox)
        }

        // Skip the split if either resulting sub-panel would be too narrow to survive
        // applyGlobalSanityChecks (which filters panels < minPanelDimensionFraction of the page).
        // Catches the case where a caption box whose left/right edge touches the page border is
        // flood-fill-reachable from the outside: the thin white margin below the caption scores
        // ≥30% gutter pixels and would be split off, leaving the caption as a tiny orphan that
        // gets filtered — visually cutting off the top of the real panel.
        //
        // Exception: applyGlobalSanityChecks keeps wide banner panels (≥ 50% page width, ≥ 5%
        // page height) even below the min-dimension floor. When the bbox spans ≥ 50% of the downscaled
        // width and the short piece reaches the 5% banner-height threshold, allow the split so
        // the banner can reach the sanity check instead of being silently merged with its
        // neighbour. This is the symmetric counterpart to the banner exception in sanity checks.
        if (axis == "h") {
            val topHeight = start - bbox.minY
            val bottomHeight = bbox.maxY - end
            // Use the full downscaled page height (not cropped) so the threshold matches
            // applyGlobalSanityChecks which uses originalHeight; both scale proportionally.
            val minDimPx = (downscaledHeight * config.minPanelDimensionFraction).toInt().coerceAtLeast(1)
            if (topHeight < minDimPx || bottomHeight < minDimPx) {
                // Banner exception: allow an asymmetric split where one piece is a wide banner
                // (≥ 50% page width, ≥ 5% page height, ≥ 25% of the current bbox height) and
                // the other is the adjacent panel section. The 25%-of-bbox guard (rather than a
                // page-relative companion threshold) prevents false positives: in the #757 fixture
                // the false-split short piece is only 18.8% of the bbox, well below the 25% floor,
                // while real banners are ≥ 30% of their containing bbox.
                val bannerMinHeightPx = (downscaledHeight * 0.05).toInt().coerceAtLeast(1)
                val bannerBboxMinHeightPx = (height * 0.25).toInt().coerceAtLeast(1)
                val bboxWidthFraction = width.toDouble() / downscaledWidth.toDouble()
                val shortPieceHeight = minOf(topHeight, bottomHeight)
                if (bboxWidthFraction < 0.5 || shortPieceHeight < bannerMinHeightPx || shortPieceHeight < bannerBboxMinHeightPx) return listOf(bbox)
            }
        } else {
            val leftWidth = start - bbox.minX
            val rightWidth = bbox.maxX - end
            val minDimPx = (downscaledWidth * config.minPanelDimensionFraction).toInt().coerceAtLeast(1)
            if (leftWidth < minDimPx || rightWidth < minDimPx) return listOf(bbox)
        }

        return if (axis == "h") {
            val topBbox = Bbox(bbox.minX, bbox.minY, bbox.maxX, start - 1)
            val bottomBbox = Bbox(bbox.minX, end + 1, bbox.maxX, bbox.maxY)
            splitSinglePanelRecursively(topBbox, cropped, gutter, depth + 1, downscaledWidth, downscaledHeight) +
                splitSinglePanelRecursively(bottomBbox, cropped, gutter, depth + 1, downscaledWidth, downscaledHeight)
        } else {
            val leftBbox = Bbox(bbox.minX, bbox.minY, start - 1, bbox.maxY)
            val rightBbox = Bbox(end + 1, bbox.minY, bbox.maxX, bbox.maxY)
            splitSinglePanelRecursively(leftBbox, cropped, gutter, depth + 1, downscaledWidth, downscaledHeight) +
                splitSinglePanelRecursively(rightBbox, cropped, gutter, depth + 1, downscaledWidth, downscaledHeight)
        }
    }

    /**
     * Returns the widest run of consecutive positions in [axisStart..axisEnd] where [isGutter]
     * is true, as `(start, thickness)`. Returns null if no gutter run exists.
     */
    private inline fun widestGutterRun(
        axisStart: Int,
        axisEnd: Int,
        isGutter: (Int) -> Boolean,
    ): Pair<Int, Int>? {
        var bestStart = -1
        var bestThickness = 0
        var currentStart = -1
        for (i in axisStart..axisEnd) {
            if (isGutter(i)) {
                if (currentStart < 0) currentStart = i
            } else if (currentStart >= 0) {
                val thickness = i - currentStart
                if (thickness > bestThickness) {
                    bestThickness = thickness
                    bestStart = currentStart
                }
                currentStart = -1
            }
        }
        if (currentStart >= 0) {
            val thickness = axisEnd - currentStart + 1
            if (thickness > bestThickness) {
                bestThickness = thickness
                bestStart = currentStart
            }
        }
        return if (bestStart >= 0) bestStart to bestThickness else null
    }

    /**
     * Greedy dedup by area: sort candidates smallest-first, keep each if it doesn't overlap any
     * already-kept panel by ≥ [Config.dedupOverlapFraction] of the smaller area. Prefers tight
     * bboxes and discards merged big-bbox duplicates that would otherwise cause Panel View to
     * walk the user through the same real panel twice.
     */
    private fun deduplicateOverlapping(regions: List<PanelRegion>): List<PanelRegion> {
        val sorted = regions.sortedBy { it.area() }
        val kept = mutableListOf<PanelRegion>()
        for (candidate in sorted) {
            val duplicatesKept = kept.any { existing ->
                val smaller = if (candidate.area() <= existing.area()) candidate else existing
                val larger = if (candidate.area() <= existing.area()) existing else candidate
                larger.overlapFraction(smaller) >= config.dedupOverlapFraction
            }
            if (!duplicatesKept) kept.add(candidate)
        }
        return kept
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
        // Drop panels that are smaller than the minimum page fraction in BOTH dimensions — a region tiny
        // in both width and height is a noise island. A panel that fails only the height check
        // but spans ≥ 50% of the page width is a real wide banner (e.g. 100%×13% title strip)
        // and is kept; everything else requires both axes to pass.
        val minWidth = (originalWidth * config.minPanelDimensionFraction).toInt().coerceAtLeast(1)
        val minHeight = (originalHeight * config.minPanelDimensionFraction).toInt().coerceAtLeast(1)
        // A wide panel (≥ 50% of page width) that is taller than 5% of the page is a real
        // banner even if its height doesn't reach the full-panel threshold.
        val bannerWidthThreshold = originalWidth * 0.5
        val bannerMinHeight = (originalHeight * 0.05).toInt().coerceAtLeast(1)
        val filtered = regions.filter {
            (it.width >= minWidth && it.height >= minHeight) ||
                (it.width >= bannerWidthThreshold && it.height >= bannerMinHeight)
        }
        if (filtered.isEmpty()) return null

        // Deduplicate. If both a tight bbox around Panel A AND a larger bbox around Panel A +
        // adjacent panel B make it through, Panel View walks the user through Panel A twice —
        // once tight, once as part of the larger. Sort by area ascending and greedily keep
        // panels that don't heavily overlap any already-kept panel. The tight (smaller) ones
        // survive; merged big-bbox duplicates are dropped.
        val meaningful = deduplicateOverlapping(filtered)
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
        val summedCoverage = totalPanelArea.toDouble() / pageArea.toDouble()
        if (summedCoverage < config.minTotalCoverageFraction) return null
        // Summed panel area exceeding the page area means panels are overlapping meaningfully
        // (even if pairwise overlaps are under the reject threshold). Real panels tile with
        // small gutters; overlap sums this large indicate CC merged content across panel
        // boundaries and gave us big-blob bboxes stacked on each other. Fall back rather than
        // present the user with overlapping zoom windows.
        if (summedCoverage > config.maxSummedCoverageFraction) return null

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
        val panelMask = PanelMaskBinarizer.binarize(grid) ?: return null
        return BinaryMask(panelMask.width, panelMask.height, panelMask.data)
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

    private fun detectBackgroundLuma(grid: PixelGrid): Int = detectPageBackground(grid)


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

    internal data class Bbox(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int) {
        fun area(): Long = (maxX - minX + 1).toLong() * (maxY - minY + 1).toLong()
    }
}
