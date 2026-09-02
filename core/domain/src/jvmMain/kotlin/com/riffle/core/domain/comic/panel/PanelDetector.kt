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
    private val config: PanelDetectionConfig = PanelDetectionConfig(),
) {

    fun detect(
        grid: PixelGrid,
        pageIndex: Int,
        originalWidth: Int,
        originalHeight: Int,
    ): PagePanels {
        require(originalWidth > 0 && originalHeight > 0) { "original dimensions must be positive" }
        val fallback = fitWhole(pageIndex, originalWidth, originalHeight)
        val mask = binarize(grid) ?: return fallback
        return detectFromMask(mask, grid.width, grid.height, pageIndex, originalWidth, originalHeight, fallback, grid)
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
        grid: PixelGrid? = null,
    ): PagePanels {
        val cropped = trimMargin(mask) ?: return fallback

        val projResult = gridByProjection(cropped, grid, pageIndex, originalWidth, originalHeight, downscaledWidth, downscaledHeight)
        if (projResult != null) return projResult

        // Same repair-before-merge ordering as gridByProjection — see the comment there.
        val gutter = floodFillGutter(cropped)
        val components = connectedComponents(cropped, gutter)
        val filtered = filterAndTighten(components, cropped)
        val afterSplit = splitAtInternalGutters(filtered, cropped, gutter, downscaledWidth, downscaledHeight)
        val afterCoalesce = coalesceNarrowStripColumns(afterSplit, cropped, gutter, downscaledWidth, downscaledHeight)
        val afterJunc = repairOneSidedRowJunctions(afterCoalesce, cropped, downscaledWidth, downscaledHeight)
        val afterRowRepair = repairDiagonalTwoColumnRows(afterJunc, cropped, gutter, downscaledWidth, downscaledHeight)
        val afterPairRepair = repairDiagonalAdjacentColumnPairs(afterRowRepair, cropped, gutter, downscaledWidth, downscaledHeight)
        val afterMerge = mergeDiagonalSpanningPanels(afterPairRepair, downscaledWidth, downscaledHeight, cropped, gutter)
        val afterBleedTrim = trimArtworkBleedOverlaps(afterMerge, downscaledWidth)
        val afterExpand = expandDiagonalBboxOverlaps(afterBleedTrim)

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
        grid: PixelGrid?,
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

        // Flood-fill gutter is needed both here (for bubble-tolerant gutter confirmation) and
        // below (for splitSinglePanelRecursively and repair stages). Compute once and reuse.
        // A pixel is a flood-fill gutter pixel if it is background (mask=0) AND reachable from
        // the page border — this reliably excludes internal panel white-space that looks like a
        // gutter in raw mask data but is not connected to any actual inter-panel gap.
        val gutter = floodFillGutter(cropped)

        // Bubble-tolerant gutter confirmation (issue #788): when a suspicious band's column
        // projection fails because bubble/art content crosses the gutter, the gutter column still
        // has lower luma-gradient energy than the dithered/art panel columns on either side.
        // Use column gutter positions inferred from the non-suspicious bands as suspects, then
        // confirm each via an energy valley in the suspicious band's luma profile.
        val effectiveColBands: List<List<Band>> = if (grid != null) {
            val candidateXs = bandColBands.indices
                .filter { !isSuspicious(bandColBands[it]) }
                .flatMap { i -> gutterCentresFromColBands(bandColBands[i]) }
                .distinct()
            bandColBands.mapIndexed { rowIndex, colBands ->
                if (isSuspicious(colBands) && candidateXs.isNotEmpty()) {
                    energyValleySplit(grid, gutter, cropped, rowBands[rowIndex], colBands, candidateXs) ?: colBands
                } else {
                    colBands
                }
            }
        } else {
            bandColBands
        }

        val rawBboxes = mutableListOf<Bbox>()
        for ((rowIndex, rowBand) in rowBands.withIndex()) {
            for (colBand in effectiveColBands[rowIndex]) {
                rawBboxes.add(Bbox(colBand.start, rowBand.start, colBand.end, rowBand.end))
            }
        }

        // Projection bboxes can straddle two real panels whose shared gutter was too narrow for
        // projectionMinBandThickness (e.g. 12px gutter on a scanned page that merges rows 1+2
        // into one tall column strip). Apply the same flood-fill split used in the CC path: a
        // genuine inter-panel gutter is connected to the page border (~100% accessible), while a
        // closed panel interior scores 0% — so the 30% threshold distinguishes them reliably.
        // Repair order matters (device-verified on the #783/#784/#786 pages):
        //  1. repairDiagonalTwoColumnRows first, so a full-width row with a diagonal interior
        //     gutter is split into overlapping columns BEFORE the merge stage looks for a
        //     column-over-band pair (the #784 tall character's top row arrives full-width).
        //  2. repairDiagonalAdjacentColumnPairs next, widening pairs the split stage cut at a
        //     diagonal boundary (#783) — which also removes the false right-edge difference that
        //     previously made stacked column panels look like a diagonal-spanning pair (#786).
        //  3. mergeDiagonalSpanningPanels after both repairs, so it sees final column geometry.
        val projAfterSplit = rawBboxes.flatMap { splitSinglePanelRecursively(it, cropped, gutter, depth = 0, downscaledWidth = downscaledWidth, downscaledHeight = downscaledHeight) }
        val projAfterCoalesce = coalesceNarrowStripColumns(projAfterSplit, cropped, gutter, downscaledWidth, downscaledHeight)
        val projAfterJunc = repairOneSidedRowJunctions(projAfterCoalesce, cropped, downscaledWidth, downscaledHeight)
        val projAfterRowRepair = repairDiagonalTwoColumnRows(projAfterJunc, cropped, gutter, downscaledWidth, downscaledHeight)
        val projAfterPairRepair = repairDiagonalAdjacentColumnPairs(projAfterRowRepair, cropped, gutter, downscaledWidth, downscaledHeight)
        val projAfterMerge = mergeDiagonalSpanningPanels(projAfterPairRepair, downscaledWidth, downscaledHeight, cropped, gutter)
        val bboxesInCropped = expandTopsToNearbyContentFragments(
            expandDiagonalBboxOverlaps(trimArtworkBleedOverlaps(projAfterMerge, downscaledWidth)),
            cropped,
        )

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

        val meaningful = applyGlobalSanityChecks(regions, originalWidth, originalHeight)
        meaningful ?: return null
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

    /**
     * Merges groups of narrow adjacent vertical panels that together form a taller combined
     * region, then re-splits each group HORIZONTALLY ONLY. Handles the pattern where intra-panel
     * white background (e.g. space between character silhouettes) is flood-fill reachable from
     * the outer border and creates false column-gutter signals, producing narrow vertical strips
     * instead of the correct stacked panels.
     *
     * A group qualifies when ALL of:
     *  - Each panel is narrow (width < 20% of page width).
     *  - Consecutive panels in the group are horizontally adjacent (gap ≤ 10% of page width).
     *  - Consecutive panels share y-range overlap (≥ 20% of the shorter panel's height).
     *  - The merged union is TALLER than it is wide (h ≥ w — pillar-like, not row-like).
     *  - At least one panel in the group spans ≥ 70% of the union's height (spanning pillar).
     *
     * After merging, the union is split at any horizontal internal gutter (≥ 30% flood-fill
     * coverage). The resulting sub-bboxes are tightened to content bounds. If no horizontal
     * gutter is found, the union is returned as-is.
     */
    private fun coalesceNarrowStripColumns(
        bboxes: List<Bbox>,
        cropped: CroppedMask,
        gutter: BooleanArray,
        downscaledWidth: Int,
        downscaledHeight: Int,
    ): List<Bbox> {
        val narrowThreshold = (downscaledWidth * 0.20).toInt()
        val (narrow, notNarrow) = bboxes.partition { it.maxX - it.minX + 1 < narrowThreshold }
        if (narrow.size < 2) return bboxes

        val maxGapX = (downscaledWidth * 0.10).toInt()

        // Sort by minX to build x-adjacent groups; include y-overlap check to avoid merging
        // panels from different row bands that share a similar x position.
        val sorted = narrow.sortedBy { it.minX }
        val groups = mutableListOf<MutableList<Bbox>>()
        var current = mutableListOf(sorted[0])
        for (i in 1 until sorted.size) {
            val curr = sorted[i]
            val groupMaxX = current.maxOf { it.maxX }
            val gapX = curr.minX - groupMaxX - 1
            // Group by x-proximity only — do NOT require y-overlap.  A strip that was split into
            // upper/lower halves by splitSinglePanelRecursively produces two bboxes with identical
            // x-ranges but non-overlapping y-ranges (overlap = 0).  Requiring y-overlap would
            // prevent those halves from being reunited, leaving stray fragments that survive the
            // pillar union and appear as extra panels (issues #876/#877).
            if (gapX <= maxGapX) {
                current.add(curr)
            } else {
                groups.add(current)
                current = mutableListOf(curr)
            }
        }
        groups.add(current)

        val result = notNarrow.toMutableList()
        for (group in groups) {
            if (group.size < 2) {
                result.addAll(group)
                continue
            }
            val unionMinX = group.minOf { it.minX }
            val unionMinY = group.minOf { it.minY }
            val unionMaxX = group.maxOf { it.maxX }
            val unionMaxY = group.maxOf { it.maxY }
            val unionW = unionMaxX - unionMinX + 1
            val unionH = unionMaxY - unionMinY + 1

            // Only merge if the combined region is taller than wide (pillar-like).
            if (unionH < unionW) {
                result.addAll(group)
                continue
            }
            // Require at least one panel that spans ≥ 70% of the union height (a true spanning
            // pillar). Without this guard, two short stacked narrow panels at different y-ranges
            // would incorrectly be merged and returned as a single union panel.
            val hasSpanningPanel = group.any { p -> (p.maxY - p.minY + 1).toDouble() / unionH >= 0.70 }
            if (!hasSpanningPanel) {
                // Before giving up, check whether a suspicious (full-width) bbox spans the
                // y-gap between the narrow strips. A suspicious band is one whose column gutter
                // was undetectable in the projection (artwork bleed connects left and right
                // content). When such a band sits between the narrow strips, the strips are
                // partial representations of one tall column panel — not separate panels.
                // Extend the union to the full column boundary (gutter-start..right-margin)
                // so the tall panel is captured at its actual width.
                val groupMinY = group.minOf { it.minY }
                val groupMaxY = group.maxOf { it.maxY }
                val suspiciousWidthThreshold = downscaledWidth * 85 / 100
                val suspiciousRightEdgeThreshold = downscaledWidth * 9 / 10
                val suspiciousGapBbox = notNarrow.firstOrNull { nb ->
                    (nb.maxX - nb.minX + 1) >= suspiciousWidthThreshold &&
                        nb.maxX >= suspiciousRightEdgeThreshold &&
                        nb.maxY >= groupMinY && nb.minY <= groupMaxY
                }
                if (suspiciousGapBbox != null) {
                    // Left boundary: end of the adjacent center/left column + 1.
                    val leftAdjacentMaxX = notNarrow
                        .filter { nb ->
                            nb.maxX < unionMinX && nb.maxY >= groupMinY && nb.minY <= groupMaxY
                        }
                        .maxOfOrNull { it.maxX }
                    val extendedMinX = if (leftAdjacentMaxX != null) leftAdjacentMaxX + 1 else unionMinX
                    val extendedMaxX = suspiciousGapBbox.maxX
                    val extended = Bbox(extendedMinX, unionMinY, extendedMaxX, unionMaxY)
                    result.addAll(splitUnionHorizontalOnly(extended, cropped, gutter, downscaledHeight))
                    continue
                }
                // No spanning panel and no suspicious-gap band: the group's strips are
                // partial slices of a tall right-column panel whose border-connected gutter
                // was lost to ink bleed (#892). Treat the bounding union as a single panel
                // candidate — splitUnionHorizontalOnly will split it at any valid internal
                // horizontal gutter (both halves ≥ minPanelDimensionFraction), or return
                // the whole union unsplit when the inter-strip gutters produce sub-panels
                // too short to survive sanity checks on their own.
                val union = Bbox(unionMinX, unionMinY, unionMaxX, unionMaxY)
                result.addAll(splitUnionHorizontalOnly(union, cropped, gutter, downscaledHeight))
                continue
            }

            // Try to split using y-gaps from non-spanning bboxes (those that don't cover the
            // full pillar height). These are the "middle" panels that were split horizontally
            // by splitSinglePanelRecursively — their boundaries mark the real inter-panel gutter
            // more reliably than pixel-based gutter detection, which can fail when stray anti-
            // aliasing pixels at character edges block the flood-fill (issues #876/#877).
            val nonSpanning = group.filter { (it.maxY - it.minY + 1).toDouble() / unionH < 0.80 }
            if (nonSpanning.size >= 2) {
                val sortedByMaxY = nonSpanning.sortedBy { it.maxY }
                var bestGapStart = -1
                var bestGapSize = 0
                for (i in 0 until sortedByMaxY.size - 1) {
                    val gapStart = sortedByMaxY[i].maxY + 1
                    val gapEnd = sortedByMaxY[i + 1].minY - 1
                    if (gapEnd >= gapStart) {
                        val size = gapEnd - gapStart + 1
                        if (size > bestGapSize) {
                            bestGapSize = size
                            bestGapStart = gapStart
                        }
                    }
                }
                val minDimPxH = (downscaledHeight * config.minPanelDimensionFraction).toInt().coerceAtLeast(1)
                if (bestGapStart > 0
                    && bestGapStart - unionMinY >= minDimPxH
                    && unionMaxY - (bestGapStart + bestGapSize - 1) >= minDimPxH
                ) {
                    result.add(tighten(Bbox(unionMinX, unionMinY, unionMaxX, bestGapStart - 1), cropped))
                    result.add(tighten(Bbox(unionMinX, bestGapStart + bestGapSize, unionMaxX, unionMaxY), cropped))
                    continue
                }
            }
            val union = Bbox(unionMinX, unionMinY, unionMaxX, unionMaxY)
            result.addAll(splitUnionHorizontalOnly(union, cropped, gutter, downscaledHeight))
        }
        return result
    }

    /**
     * Splits [bbox] at its widest horizontal internal gutter (≥ [Config.internalGutterFloodFillFraction]
     * of inner columns are flood-fill gutter pixels, run ≥ [Config.internalGutterMinThickness] rows,
     * each half ≥ [Config.minPanelDimensionFraction] of the page). Returns the two tightened
     * sub-bboxes, or a singleton list containing the original [bbox] if no valid split is found.
     */
    private fun splitUnionHorizontalOnly(
        bbox: Bbox,
        cropped: CroppedMask,
        gutter: BooleanArray,
        downscaledHeight: Int,
    ): List<Bbox> {
        val width = bbox.maxX - bbox.minX + 1
        val height = bbox.maxY - bbox.minY + 1
        val edgeMarginX = (width * config.internalGutterEdgeMargin).toInt().coerceAtLeast(1)
        val innerMinX = bbox.minX + edgeMarginX
        val innerMaxX = bbox.maxX - edgeMarginX
        val innerWidth = innerMaxX - innerMinX + 1
        if (innerWidth <= 0) return listOf(bbox)
        val edgeMarginY = (height * config.internalGutterEdgeMargin).toInt().coerceAtLeast(1)
        val thresholdTimesK = (config.internalGutterFloodFillFraction * 1000).toLong()

        fun countGutter(y: Int): Int {
            var g = 0
            for (x in innerMinX..innerMaxX) if (gutter[y * cropped.width + x]) g++
            return g
        }
        val hGutter = widestGutterRun(
            axisStart = bbox.minY + edgeMarginY,
            axisEnd = bbox.maxY - edgeMarginY,
        ) { y ->
            countGutter(y).toLong() * 1000 >= innerWidth.toLong() * thresholdTimesK
        } ?: return listOf(bbox)

        val (start, thickness) = hGutter
        if (thickness < config.internalGutterMinThickness) return listOf(bbox)
        val end = start + thickness - 1
        val minDimPxH = (downscaledHeight * config.minPanelDimensionFraction).toInt().coerceAtLeast(1)
        if (start - bbox.minY < minDimPxH || bbox.maxY - end < minDimPxH) return listOf(bbox)

        return listOf(
            tighten(Bbox(bbox.minX, bbox.minY, bbox.maxX, start - 1), cropped),
            tighten(Bbox(bbox.minX, end + 1, bbox.maxX, bbox.maxY), cropped),
        )
    }

    internal fun repairOneSidedRowJunctions(
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
                verticalGap in 0..15 &&
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
     * Rejoins a tall left panel that a horizontal row split cut in two, in the specific shape
     * observed on real pages (issue #784, device-verified geometry): the TOP piece is a proper
     * left-column panel and the BOTTOM piece is an UNSPLIT full-width row band — projection could
     * not find the vertical gutter in the bottom row because the tall character's diagonal right
     * edge blurs it. The merge keeps the column's right edge and emits the remainder of the
     * full-width band as its own panel (the bottom row's right-hand panel).
     *
     * The "bot must be a full-width band" requirement is the load-bearing guard: when the bottom
     * row was ALREADY properly split into columns, its left panel is a real standalone panel and
     * merging it upward destroys a valid layout (issue #786 regression — two clean stacked column
     * panels with different right edges were merged, and a sliver remainder emitted). Requiring
     * "top must be a column panel" likewise prevents a full-width banner from being merged with a
     * column panel below it (the false merge previously guarded by widthCap/topIsBanner).
     *
     * Merge is deliberately DOWNWARD-ONLY (column on top, band below): the mirrored shape
     * (unsplit band on top, column below) is indistinguishable from a title banner above a
     * column panel, which must never merge.
     *
     * The final and decisive guard is the BOUNDARY-CONTENT check (requires [cropped]): the same
     * column-over-band geometry also occurs when a genuine full-width panel sits below a column
     * row. What distinguishes the split tall panel is that the horizontal gutter that cut it
     * exists only under the RIGHT side (between the right-hand stacked panels) — within the
     * column's own x-range the panel's artwork runs continuously through the boundary rows.
     * A genuine panel boundary is white across the full width, including the column's range.
     */
    internal fun mergeDiagonalSpanningPanels(
        bboxes: List<Bbox>,
        downscaledWidth: Int,
        downscaledHeight: Int,
        cropped: CroppedMask? = null,
        gutter: BooleanArray? = null,
    ): List<Bbox> {
        if (bboxes.size < 2) return bboxes
        // Process top-to-bottom so a bbox emitted as its own panel can never later be picked as
        // a merge bot (a bot always starts below its top, so sorting guarantees the top's
        // iteration runs first). Callers happen to pass raster/band order today, but nothing
        // enforced it.
        val ordered = bboxes.sortedWith(compareBy({ it.minY }, { it.minX }))
        // Allow a horizontal gutter row band between the two pieces (≤ 2% of page height, min 15px).
        val maxGap = (downscaledHeight * 0.02).toInt().coerceAtLeast(15)
        // Both pieces must be at least 10% of page height (rules out thin caption strips).
        val minPanelH = (downscaledHeight * 0.10).toInt()
        // Minimum right-edge difference (15% of page width) to distinguish a diagonal split from
        // scanner jitter between two regular-grid panels with the same left edge.
        val minEdgeDiff = (downscaledWidth * 0.15).toInt()
        // Allow the left edges to differ by up to 5% of page width (scanner jitter).
        val edgeTolerance = (downscaledWidth * 0.05).toInt().coerceAtLeast(5)
        // The bottom piece must be an unsplit row band spanning ≥ 85% of the page width — same
        // full-width criterion as repairDiagonalTwoColumnRow. A properly-split column panel
        // (≤ ~55% width) never qualifies, which is what protects the #786 layout.
        val minBotWidth = (downscaledWidth * 0.85).toInt()
        // The top piece must be a genuine column panel — no wider than 65% of the page. A
        // full-width banner (~90%+) never qualifies.
        val maxTopWidth = (downscaledWidth * 0.65).toInt()

        val result = mutableListOf<Bbox>()
        val consumed = BooleanArray(ordered.size)

        for (i in ordered.indices) {
            if (consumed[i]) continue
            val top = ordered[i]
            if (top.maxY - top.minY + 1 < minPanelH) {
                result.add(top)
                consumed[i] = true
                continue
            }

            var bestJ = -1
            for (j in ordered.indices) {
                if (i == j || consumed[j]) continue
                val bot = ordered[j]
                val gap = bot.minY - top.maxY - 1
                if (gap !in 0..maxGap) continue
                if (bot.maxY - bot.minY + 1 < minPanelH) continue
                val sameLeft = kotlin.math.abs(top.minX - bot.minX) <= edgeTolerance
                val edgeDiff = bot.maxX - top.maxX
                val topIsColumn = top.maxX - top.minX + 1 <= maxTopWidth
                val botIsFullWidthBand = bot.maxX - bot.minX + 1 >= minBotWidth
                if (sameLeft && edgeDiff >= minEdgeDiff && topIsColumn && botIsFullWidthBand &&
                    boundaryContinuesThroughColumn(top, bot, cropped)
                ) {
                    bestJ = j
                    break
                }
            }
            if (bestJ < 0) {
                result.add(top)
                consumed[i] = true
                continue
            }
            val bot = ordered[bestJ]
            consumed[i] = true
            consumed[bestJ] = true
            // Keep the COLUMN's right edge for the merged tall panel so it does not extend into
            // the bottom row's right-hand panel territory (which would trip the overlap sanity
            // check and cause a Fallback result).
            val mergedTall = Bbox(
                minOf(top.minX, bot.minX),
                top.minY,
                top.maxX,
                bot.maxY,
            )
            result.add(mergedTall)
            // The right portion of the full-width band is the bottom row's right-hand panel —
            // emit it so it is not silently lost. Guaranteed non-degenerate: edgeDiff ≥ minEdgeDiff.
            // The remainder's LEFT edge follows the diagonal boundary inside the band: the tall
            // panel's right edge is slanted, so at band depth the character has receded well left
            // of top.maxX and the remainder must start where the band's own gutter run puts it —
            // starting at top.maxX + 1 chops off the remainder's left portion (issue #787).
            var remainderMinX = diagonalRemainderStart(top, bot, cropped, gutter, downscaledWidth)
                ?: (top.maxX + 1)
            // Rectangles cannot tile a diagonal: the remainder and the tall panel intentionally
            // share the transition zone. But the merge must never emit geometry that its own
            // sanity check rejects — walk the left edge back right until the overlap stays under
            // overlapRejectFraction (with a 5% margin for original-scale rounding drift).
            val bandHeight = (bot.maxY - bot.minY + 1).toLong()
            val overlapCap = config.overlapRejectFraction * 0.95
            while (remainderMinX <= top.maxX) {
                val overlapArea = (top.maxX - remainderMinX + 1).toLong() * bandHeight
                val remainderArea = (bot.maxX - remainderMinX + 1).toLong() * bandHeight
                if (overlapArea <= overlapCap * minOf(mergedTall.area(), remainderArea)) break
                remainderMinX++
            }
            result.add(Bbox(remainderMinX, bot.minY, bot.maxX, bot.maxY))
        }
        return result
    }

    /**
     * True when the panel artwork runs continuously through the horizontal boundary between
     * [top] and [bot] within the TOP column's x-range — the signature of a tall panel that the
     * split stage cut collaterally (the gutter it found belongs to the stacked panels on the
     * right). A genuine panel boundary is white (near-zero content) across the column's range.
     *
     * When [cropped] is null (unit tests exercising the geometric gate in isolation) the check
     * is skipped.
     */
    private fun boundaryContinuesThroughColumn(top: Bbox, bot: Bbox, cropped: CroppedMask?): Boolean {
        cropped ?: return true
        // Sample ONLY the gap rows between the two bboxes. The bboxes' own edge rows (top.maxY,
        // bot.minY) are content by construction — tighten() guarantees it — so including them
        // made the 30% threshold trivially satisfiable for gaps ≤ 4 rows (2 full-content rows
        // out of ≤ 6 sampled), letting a genuine thin white gutter "pass" as continuous artwork.
        // No gap rows at all = no evidence of continuity = do not merge.
        val yStart = (top.maxY + 1).coerceAtLeast(0)
        val yEnd = (bot.minY - 1).coerceAtMost(cropped.height - 1)
        if (yEnd < yStart) return false
        val xStart = top.minX.coerceIn(0, cropped.width - 1)
        val xEnd = top.maxX.coerceIn(0, cropped.width - 1)
        val columnWidth = xEnd - xStart + 1
        if (columnWidth <= 0) return false
        var content = 0L
        for (y in yStart..yEnd) {
            content += cropped.rowContentCount(y, xStart, xEnd)
        }
        val total = (yEnd - yStart + 1).toLong() * columnWidth
        // Continuous artwork fills ≥ ~40-90% of the boundary band; a genuine gutter ≤ ~10%.
        return content * 100 >= total * 30
    }

    /**
     * Locates where the row-2 right remainder should START when [mergeDiagonalSpanningPanels]
     * rejoins a tall diagonal-edged panel with the unsplit band below it (issue #787).
     *
     * The tall panel's right boundary is a slash: at the band's depth the character's edge has
     * receded left of `top.maxX`, and the gutter between character and the band's right-hand
     * panel is found by the same top/bottom strip run analysis as [repairDiagonalTwoColumnRow].
     * When the two runs are shifted apart (diagonal confirmed) the remainder starts just right
     * of the LEFTMOST run — i.e. where the right panel's content actually begins — instead of
     * at `top.maxX + 1`, which chops off everything between the diagonal and the column edge.
     *
     * Returns null (caller falls back to `top.maxX + 1`) when the mask/gutter are unavailable
     * (synthetic geometry-only tests), when no runs are found, when the runs sit away from the
     * column boundary (unrelated interior dip), or when the boundary is straight.
     */
    private fun diagonalRemainderStart(
        top: Bbox,
        bot: Bbox,
        cropped: CroppedMask?,
        gutter: BooleanArray?,
        downscaledWidth: Int,
    ): Int? {
        cropped ?: return null
        gutter ?: return null
        val (topRun, bottomRun) = diagonalStripGutterRuns(bot, cropped, gutter) ?: return null
        val topCenter = (topRun.first + topRun.second) / 2
        val bottomCenter = (bottomRun.first + bottomRun.second) / 2
        // Both runs must sit at the tall panel's column boundary, not some unrelated interior
        // gutter elsewhere in the band.
        val neighborhoodMax = top.maxX + (downscaledWidth * 0.05).toInt()
        val neighborhoodMin = top.maxX - (downscaledWidth * 0.20).toInt()
        if (topCenter !in neighborhoodMin..neighborhoodMax) return null
        if (bottomCenter !in neighborhoodMin..neighborhoodMax) return null
        val shift = kotlin.math.abs(topCenter - bottomCenter)
        // Same diagonal-shift floor as repairDiagonalAdjacentColumnPairs; 15% cap keeps a
        // wildly-slanted false run from dragging the remainder into the character's column.
        if (shift < (downscaledWidth * 0.015).toInt().coerceAtLeast(8)) return null
        if (shift > (downscaledWidth * 0.15).toInt()) return null
        val leftRun = if (topRun.first <= bottomRun.first) topRun else bottomRun
        // Start at the gutter's right edge — the first column of the right panel's content.
        // coerceIn throws on an empty range; a degenerate top (maxX ≤ bot.minX) means the
        // geometry makes no sense for a remainder adjustment — fall back to top.maxX + 1.
        if (bot.minX + 1 > top.maxX) return null
        return (leftRun.second + 1).coerceIn(bot.minX + 1, top.maxX)
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
    internal fun expandDiagonalBboxOverlaps(bboxes: List<Bbox>): List<Bbox> {
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
                val yOverlap = yOverlapBottom - yOverlapTop + 1
                val shorterH = minOf(left.maxY - left.minY + 1, right.maxY - right.minY + 1)
                if (yOverlap.toDouble() / shorterH < 0.5) continue
                // Expand each panel by half the horizontal overlap into the other's zone.
                var pad = overlapX / 2
                if (pad <= 0) continue
                // Padding doubles the pair's overlap. Upstream stages (the merge remainder
                // walk-back, repairDiagonalAdjacentColumnPairs) may have already produced
                // overlap close to overlapRejectFraction — expanding past the cap would make
                // applyGlobalSanityChecks reject the WHOLE page into Fallback. Shrink the pad
                // until the post-expand overlap stays under the cap (same 5% margin as the
                // merge walk-back for original-scale rounding drift).
                val overlapCap = config.overlapRejectFraction * 0.95
                while (pad > 0) {
                    val newLeft = Bbox(left.minX, left.minY, left.maxX + pad, left.maxY)
                    val newRight = Bbox((right.minX - pad).coerceAtLeast(0), right.minY, right.maxX, right.maxY)
                    val ovW = (newLeft.maxX - newRight.minX + 1).toLong()
                    val ovH = (yOverlapBottom - yOverlapTop + 1).toLong()
                    if (ovW * ovH <= overlapCap * minOf(newLeft.area(), newRight.area())) break
                    pad--
                }
                if (pad <= 0) continue
                result[leftIdx] = Bbox(left.minX, left.minY, left.maxX + pad, left.maxY)
                result[rightIdx] = Bbox((right.minX - pad).coerceAtLeast(0), right.minY, right.maxX, right.maxY)
            }
        }
        return result
    }

    /**
     * Trims panels whose bounding box bleeds mildly into a neighbouring panel's column due to
     * artwork crossing the drawn panel border (e.g. a large silhouette figure painted past the
     * border line). The bleed manifests as a small horizontal overlap (≤35% of the bleeding
     * panel's width) with a y-adjacent panel whose x-centre is clearly in a different column.
     *
     * Without this step the bleeding panel's bbox is too wide and the mild overlap (< 25%)
     * slips under the sanity-check rejection threshold, resulting in a panel that covers the
     * neighbouring column's territory (#890).
     */
    private fun trimArtworkBleedOverlaps(
        bboxes: List<Bbox>,
        downscaledWidth: Int,
    ): List<Bbox> {
        if (bboxes.size < 2) return bboxes
        val minDimPx = (downscaledWidth * config.minPanelDimensionFraction).toInt().coerceAtLeast(1)
        val result = bboxes.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in result.indices) {
                for (j in result.indices) {
                    if (i == j) continue
                    val a = result[i]
                    val b = result[j]
                    // A must extend rightward past B's left edge
                    if (a.maxX <= b.minX || a.minX >= b.minX) continue
                    // A's x-centre must be clearly left of B's x-centre (different columns)
                    val aCenterX = (a.minX + a.maxX) / 2
                    val bCenterX = (b.minX + b.maxX) / 2
                    if (aCenterX >= bCenterX) continue
                    // Must share meaningful y-overlap (≥30% of shorter panel's height)
                    val yOverlapStart = maxOf(a.minY, b.minY)
                    val yOverlapEnd = minOf(a.maxY, b.maxY)
                    if (yOverlapEnd < yOverlapStart) continue
                    val yOverlapLen = yOverlapEnd - yOverlapStart + 1
                    val shorterH = minOf(a.maxY - a.minY + 1, b.maxY - b.minY + 1)
                    if (yOverlapLen.toDouble() / shorterH < 0.30) continue
                    // Diagonal-boundary repairs (repairDiagonalAdjacentColumnPairs) produce overlapping
                    // bboxes for panels from the SAME projection row band — those panels share
                    // identical top-y. Artwork bleed (repairDiagonalAdjacentColumnPairs incorrectly
                    // widening a panel into a neighbouring cross-row-band panel) produces A and B
                    // from different projection row bands → B.minY > A.minY by at least 5px.
                    if (b.minY <= a.minY + 5) continue
                    // A tall diagonal panel (A) naturally overlaps with the lower-right panel (B/C)
                    // in the same row band — both share the same bottom edge. True artwork bleed has
                    // A extending significantly BELOW B (A.maxY >> B.maxY). If A and B share the
                    // same bottom edge (±5px), the overlap is legitimate diagonal geometry, not bleed.
                    if (a.maxY <= b.maxY + 5) continue
                    // X-bleed must be 10–35% of A's width. The lower bound excludes tiny
                    // diagonal-expansion overlaps (~1–5%) added by expandDiagonalBboxOverlaps;
                    // the upper bound excludes genuine panel mergers that are not artwork bleed.
                    val aWidth = a.maxX - a.minX + 1
                    val xBleed = a.maxX - b.minX + 1
                    val xBleedFraction = xBleed.toDouble() / aWidth
                    if (xBleedFraction < 0.10 || xBleedFraction > 0.35) continue
                    // Clipped A must still be at least minDimPx wide
                    val clippedWidth = b.minX - a.minX
                    if (clippedWidth < minDimPx) continue
                    result[i] = Bbox(a.minX, a.minY, b.minX - 1, a.maxY)
                    changed = true
                    continue@outer
                }
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

        val (topRun, bottomRun) = diagonalStripGutterRuns(bbox, cropped, gutter) ?: return null

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

    /**
     * Locates the interior gutter run in the TOP 20% strip and the BOTTOM 20% strip of [bbox],
     * preferring flood-fill evidence and falling back to content projection. Returns
     * `(topRun, bottomRun)` as `(startX, endX)` pairs, or null when either strip has no run.
     * Comparing the two runs' positions reveals whether the internal boundary is diagonal
     * (runs shifted apart) or straight (runs coincide).
     */
    /**
     * Alternative to [diagonalStripGutterRuns] for panel pairs where the diagonal gutter is only
     * partially flood-fill accessible (e.g. gap=0 or touching panels where the gutter is enclosed
     * by content beyond a certain column).
     *
     * Scans the topmost flood-fill gutter pixel per column across the shared boundary zone and
     * identifies the rising segment at the start of the profile (before any big forward jump that
     * signals a transition to a different gutter region). The slope of the accessible segment is
     * then extrapolated to estimate where the boundary reaches union.maxY, producing a bottomRun
     * that covers the full diagonal extent even when only a partial prefix is flood-fill visible.
     *
     * Returns synthetic single-column topRun / bottomRun compatible with
     * [repairDiagonalAdjacentColumnPairs], or null if no valid rising diagonal segment is found.
     */
    private fun diagonalProfileScan(
        union: Bbox,
        leftMaxX: Int,
        rightMinX: Int,
        yOverlapTop: Int,
        yOverlapBottom: Int,
        cropped: CroppedMask,
        gutter: BooleanArray,
        downscaledWidth: Int,
        minShift: Int,
        maxShift: Int,
    ): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        val slack = (downscaledWidth * 0.02).toInt().coerceAtLeast(5)
        val scanStart = (leftMaxX - slack).coerceAtLeast(union.minX)
        val scanEnd = (rightMinX + maxShift).coerceAtMost(union.maxX)
        val unionHeight = union.maxY - union.minY + 1
        val overlapHeight = yOverlapBottom - yOverlapTop + 1

        // Profile A: topmost gutter pixel per column within the y-overlap zone.
        // Combines raw mask pixels (data==0) with flood-fill gutter so that both synthetic tests
        // (where gutter is tracked via the flood-fill array, not the raw mask) and real-image
        // cases (where the binarized mask encodes gutter as data==0) are handled.
        // This detects rising-right diagonals (gap narrows at the top, widens at the bottom FROM
        // the left panel's perspective): the gutter moves downward as x increases, so the
        // topmost gutter per column rises from left to right.
        val profileA = mutableListOf<Pair<Int, Int>>()
        for (x in scanStart..scanEnd) {
            for (y in yOverlapTop..yOverlapBottom) {
                val idx = y * cropped.width + x
                if (cropped.data[idx] == 0.toByte() || gutter[idx]) {
                    profileA.add(x to y)
                    break
                }
            }
        }

        // Profile B: first content→gutter transition per column.
        // This detects falling-right diagonals (gap widens at the bottom; the left panel is
        // wider at the bottom than at the top). A horizontal panel border at y=yOverlapTop would
        // make profileA flat (border row is gutter for every column), masking the diagonal.
        // Profile B skips the border by scanning for the FIRST gutter that follows content,
        // which only fires inside the diagonal transition zone — not in the flat border.
        // Columns that are fully gutter (the gap itself) produce no entry; columns fully inside
        // a panel produce no entry either (no gutter after content), so this profile is
        // inherently focused on the diagonal boundary.
        val profileB = mutableListOf<Pair<Int, Int>>()
        for (x in scanStart..scanEnd) {
            var sawContent = false
            for (y in yOverlapTop..yOverlapBottom) {
                val isContent = cropped.data[y * cropped.width + x] == 1.toByte()
                if (isContent) sawContent = true
                else if (sawContent) { profileB.add(x to y); break }
            }
        }

        val bigJumpThreshold = (unionHeight * 0.25).toInt().coerceAtLeast(10)
        val smallDropThreshold = (unionHeight * 0.05).toInt()

        // Try both profiles × both scan directions. Profile A + forward = rising-right diagonal.
        // Profile A + reverse = falling-right via reversed topmost-gutter.
        // Profile B + forward = falling-right via content-to-gutter boundary.
        // Profile B + reverse = secondary attempt (usually redundant but harmless).
        for (profile in listOf(profileA, profileB)) {
            if (profile.size < 5) continue
        for (forward in listOf(true, false)) {
            val scanProfile = if (forward) profile else profile.reversed()

            // firstY should sit in the upper quarter of the y-overlap zone so we have a
            // meaningful region to extrapolate through.
            val firstY = scanProfile.first().second
            if (firstY > yOverlapTop + (unionHeight * 0.30).toInt()) continue

            // Find the rising segment, stopping at a big forward jump or significant drop.
            var segEndIdx = 0
            var risingCount = 0
            var firstRisingIdx = -1
            var hasFlatPrefix = false
            for (i in 1 until scanProfile.size) {
                val dy = scanProfile[i].second - scanProfile[i - 1].second
                when {
                    dy > bigJumpThreshold -> break
                    dy < -smallDropThreshold -> break
                    dy == 0 -> hasFlatPrefix = true
                    dy > 0 -> {
                        risingCount++
                        segEndIdx = i
                        // If a flat prefix preceded this rise, require dy ≥ 2 to anchor
                        // firstRisingIdx. A horizontal gutter row just above the diagonal zone
                        // generates flat profileB entries followed by a 1-pixel bump; without
                        // this guard the bump sets firstRisingIdx too early, inflating riseSpan
                        // and diluting the monotonicity ratio below 75% (issue #834).
                        val minDy = if (hasFlatPrefix) 2 else 1
                        if (firstRisingIdx < 0 && dy >= minDy) firstRisingIdx = i
                    }
                }
            }

            if (segEndIdx < 4) continue
            val segFirstX = scanProfile[0].first
            val segLastX = scanProfile[segEndIdx].first
            val segFirstY = scanProfile[0].second
            val segLastY = scanProfile[segEndIdx].second
            val dxSeg = segLastX - segFirstX
            val dySeg = segLastY - segFirstY
            // Segment must span at least 10% of the y-overlap height.
            if (dySeg < overlapHeight * 0.10) continue
            // Monotonicity: measure only over the rising portion (skip any flat prefix from the gap).
            val riseSpan = if (firstRisingIdx in 1..segEndIdx) segEndIdx - firstRisingIdx + 1 else segEndIdx
            if (risingCount < riseSpan * 0.75) continue

            // Extrapolate the diagonal slope to where the boundary reaches the far end of the
            // y-overlap zone. For forward scans the extrapolation target is union.maxY (the segment
            // starts near the top and is extrapolated downward). For reverse scans the same holds
            // because scanProfile[0] also sits near yOverlapTop (firstY check passed).
            val dyToBottom = union.maxY - segFirstY
            val extrapolatedEndX = if (dySeg > 0) {
                (segFirstX + dyToBottom.toDouble() * dxSeg / dySeg).toInt()
                    .let { raw ->
                        // Forward (rising-right): end is to the right of the segment; cap at maxX.
                        // Reverse (falling-right): end is to the left of the segment; floor at minX.
                        if (dxSeg >= 0) raw.coerceIn(segLastX, union.maxX)
                        else raw.coerceIn(union.minX, segLastX)
                    }
            } else {
                segLastX
            }

            val shift = kotlin.math.abs(extrapolatedEndX - segFirstX)
            if (shift < minShift || shift > maxShift) continue

            // topRun = where the gutter sits at the TOP of the y-overlap zone (segFirstX, near
            // yOverlapTop for both directions). bottomRun = estimated x at union.maxY.
            return (segFirstX to segFirstX) to (extrapolatedEndX to extrapolatedEndX)
        }
        }
        return null
    }

    private fun diagonalStripGutterRuns(
        bbox: Bbox,
        cropped: CroppedMask,
        gutter: BooleanArray,
    ): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        val height = bbox.maxY - bbox.minY + 1
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
        return topRun to bottomRun
    }

    /**
     * Widens a pair of same-row panels whose shared vertical boundary is DIAGONAL, so that both
     * cover the full transition zone (issue #783, device-verified geometry: the split stage cuts
     * both panels at the diagonal's waist, chopping off the left panel's widest corner).
     *
     * For each horizontally-adjacent same-row pair with a small gap, the union region is analysed
     * with the same top/bottom strip gutter runs used by [repairDiagonalTwoColumnRow]. When the
     * runs are shifted apart (diagonal boundary), the left panel is extended to the rightmost run
     * edge and the right panel to the leftmost run edge (plus the same overlap pad), mirroring
     * [expandDiagonalBboxOverlaps] semantics. Straight gutters (runs coincide) are left untouched.
     *
     * The shift floor is lower than [repairDiagonalTwoColumnRow]'s (1.5% of page width vs 2.5%)
     * because a real gutter has already been independently confirmed by the split stage here —
     * the only question is whether it is slanted enough that the hard cut truncated the panels.
     */
    internal fun repairDiagonalAdjacentColumnPairs(
        bboxes: List<Bbox>,
        cropped: CroppedMask,
        gutter: BooleanArray,
        downscaledWidth: Int,
        downscaledHeight: Int,
    ): List<Bbox> {
        if (bboxes.size < 2) return bboxes
        val maxPairGap = (downscaledWidth * 0.05).toInt().coerceAtLeast(5)
        val minShift = (downscaledWidth * 0.015).toInt().coerceAtLeast(8)
        val maxShift = (downscaledWidth * 0.12).toInt()
        // The profile scan extrapolates the diagonal slope from the flood-fill-accessible segment,
        // so it can handle larger shifts than the strip scan without false-positives (the y-overlap
        // restriction already gates which pairs it fires on). Allow up to 20% for the profile path
        // (issue #834: falling-right diagonal spanning 16.5% of page width).
        val maxShiftProfile = (downscaledWidth * 0.20).toInt()
        val result = bboxes.toMutableList()

        for (i in result.indices) {
            for (j in result.indices) {
                if (i >= j) continue
                val a = result[i]
                val b = result[j]
                val (leftIdx, rightIdx) = if (a.minX <= b.minX) i to j else j to i
                val left = result[leftIdx]
                val right = result[rightIdx]
                // Same row band: y-ranges share ≥ 50% of the shorter panel's height.
                val yOverlapTop = maxOf(left.minY, right.minY)
                val yOverlapBottom = minOf(left.maxY, right.maxY)
                if (yOverlapBottom <= yOverlapTop) continue
                val shorterH = minOf(left.maxY - left.minY + 1, right.maxY - right.minY + 1)
                if ((yOverlapBottom - yOverlapTop + 1).toDouble() / shorterH < 0.5) continue
                // Horizontally adjacent with a small gap (already-overlapping pairs are handled
                // by expandDiagonalBboxOverlaps). Gap=0 means the panels physically touch; those
                // are handled via diagonalProfileScan instead of the strip-density run scan.
                val gap = right.minX - left.maxX - 1
                if (gap !in 0..maxPairGap) continue
                // Same full-width/height envelope as repairDiagonalTwoColumnRow, applied to the union.
                // Height raised to 0.55 to cover tall-section diagonals (e.g. bottom half of page).
                val union = Bbox(left.minX, minOf(left.minY, right.minY), right.maxX, maxOf(left.maxY, right.maxY))
                val unionWidth = union.maxX - union.minX + 1
                val unionHeight = union.maxY - union.minY + 1
                if (unionWidth.toDouble() / downscaledWidth < 0.85) continue
                val heightFrac = unionHeight.toDouble() / downscaledHeight
                if (heightFrac !in 0.16..0.55) continue

                // Try the profile scan first: it detects gradual diagonals (< 1 row/column) that
                // are invisible to the 45 %-strip-density threshold. The scan is restricted to the
                // pair's y-overlap zone so pairs whose y-overlap doesn't contain the diagonal are
                // naturally rejected (e.g. a left/right-bottom pair when the diagonal is only
                // visible within the left/right-top overlap zone). Fall back to the strip scan for
                // wide gaps or steep diagonals where the profile approach is insufficient.
                val profileResult = diagonalProfileScan(union, left.maxX, right.minX, yOverlapTop, yOverlapBottom, cropped, gutter, downscaledWidth, minShift, maxShiftProfile)
                val stripResult = if (profileResult == null) diagonalStripGutterRuns(union, cropped, gutter) else null
                val (topRun, bottomRun) = profileResult ?: stripResult ?: continue
                val effectiveMaxShift = if (profileResult != null) maxShiftProfile else maxShift
                // Both runs must sit at the pair's shared boundary, not some unrelated interior dip.
                val neighborhoodMin = left.maxX - effectiveMaxShift
                val neighborhoodMax = right.minX + effectiveMaxShift
                val topCenter = (topRun.first + topRun.second) / 2
                val bottomCenter = (bottomRun.first + bottomRun.second) / 2
                if (topCenter !in neighborhoodMin..neighborhoodMax) continue
                if (bottomCenter !in neighborhoodMin..neighborhoodMax) continue
                val diagonalShift = kotlin.math.abs(topCenter - bottomCenter)
                if (diagonalShift < minShift || diagonalShift > effectiveMaxShift) continue

                // If BOTH runs are entirely within the current gap between the two panels, the
                // split already captured the straight vertical gutter correctly — diagonal
                // widening would create artificial overlap from an unrelated run. A real diagonal
                // boundary has at least ONE run extending past the gap into a panel. Skip only
                // when both are inside the gap; a falling-right diagonal has its bottom run at
                // the gap and its top run far into one of the panels — that still needs widening.
                val topRunInGap = topRun.first >= left.maxX + 1 && topRun.second <= right.minX - 1
                val bottomRunInGap = bottomRun.first >= left.maxX + 1 && bottomRun.second <= right.minX - 1
                if (topRunInGap && bottomRunInGap) continue

                val overlapPad = (unionWidth * 0.02).toInt().coerceAtLeast(12)
                var newLeftMax = (maxOf(topRun.second, bottomRun.second) + overlapPad)
                    .coerceAtMost(right.maxX - 1)
                var newRightMin = (minOf(topRun.first, bottomRun.first) - overlapPad)
                    .coerceAtLeast(left.minX + 1)
                if (newLeftMax <= left.maxX && newRightMin >= right.minX) continue
                // Walk newLeftMax back until the resulting overlap stays under the global sanity
                // threshold (same cap pattern used in mergeDiagonalSpanningPanels).
                val overlapCap = config.overlapRejectFraction * 0.95
                val yOvTop = maxOf(left.minY, right.minY)
                val yOvBottom = minOf(left.maxY, right.maxY)
                val overlapH = (yOvBottom - yOvTop + 1L).coerceAtLeast(0L)
                while (newLeftMax > left.maxX) {
                    // Use right.minX (fixed output position) rather than newRightMin which can
                    // exceed right.minX during walk-back, making the proxy overlap reach zero while
                    // the actual output overlap (newLeftMax - right.minX) remains large.
                    val overlapW = (newLeftMax - minOf(newRightMin, right.minX) + 1L).coerceAtLeast(0L)
                    val leftArea = (newLeftMax - left.minX + 1L) * (left.maxY - left.minY + 1L)
                    val rightArea = (right.maxX - minOf(newRightMin, right.minX) + 1L) * (right.maxY - right.minY + 1L)
                    if (overlapW * overlapH <= overlapCap * minOf(leftArea, rightArea)) break
                    newLeftMax--
                    newRightMin++
                }
                if (newLeftMax <= left.maxX && newRightMin >= right.minX) continue
                result[leftIdx] = Bbox(left.minX, left.minY, maxOf(newLeftMax, left.maxX), left.maxY)
                result[rightIdx] = Bbox(minOf(newRightMin, right.minX), right.minY, right.maxX, right.maxY)
            }
        }
        return result
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
        // Set to true when the chosen horizontal gutter came from the diagonal-gutter fallback
        // (22%-relaxed projection path). Used below to skip the bannerBboxMinHeightPx guard,
        // which is too strict for sub-bboxes produced by a prior vertical split.
        var horizontalFromDiagonalFallback = false
        // Set to true when effectiveVerticalGutter came from the both-strips confirmation fallback
        // (top + bottom border both see the gutter at the same column). Used in the vertical split
        // guard to allow a top-strip sparse check instead of requiring the full bbox to be sparse.
        var verticalFromBothStripsConfirmation = false
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
            // typical 1–5-row sparse-artwork noise. When the widest projection run is thick
            // (≥7 rows) but would fail the minDimPx split-validity check — e.g. 169 rows of
            // low-content artwork deep inside the lower panel shadow the genuine 30-row section
            // boundary above — scan top-to-bottom for the first valid run that is also thick
            // enough (≥20 rows) to beat a typical inter-column vertical gutter in bestGutter
            // selection, ensuring the horizontal boundary wins. The ≥20-row floor avoids sparse
            // artwork dips (typically 1–15 rows) and is below the genuine section boundary run
            // (~30 rows) observed in practice.
            run {
                val pg = projGutter ?: return@run null
                val (pgStart, pgThick) = pg
                if (pgThick < 7) return@run null
                val minDimPxH = (downscaledHeight * config.minPanelDimensionFraction).toInt().coerceAtLeast(1)
                val pgEnd = pgStart + pgThick - 1
                if ((pgStart - bbox.minY) >= minDimPxH && (bbox.maxY - pgEnd) >= minDimPxH) return@run pg
                var runStart = -1
                val axisEnd = bbox.maxY - edgeMarginY
                for (y in (bbox.minY + edgeMarginY)..axisEnd) {
                    val isG = cropped.rowContentCount(y, innerMinX, innerMaxX) < contentCutoff
                    if (isG) { if (runStart < 0) runStart = y }
                    else if (runStart >= 0) {
                        val t = y - runStart
                        if (t >= 20) {
                            val topH = runStart - bbox.minY
                            val botH = bbox.maxY - (runStart + t - 1)
                            if (topH >= minDimPxH && botH >= minDimPxH) return@run runStart to t
                        }
                        runStart = -1
                    }
                }
                if (runStart >= 0) {
                    val t = axisEnd - runStart + 1
                    if (t >= 20) {
                        val topH = runStart - bbox.minY
                        val botH = bbox.maxY - (runStart + t - 1)
                        if (topH >= minDimPxH && botH >= minDimPxH) return@run runStart to t
                    }
                }
                null
            }
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
                // Diagonal-gutter banner fallback: when the gutter between a banner and a lower
                // section is slanted, the boundary crosses each row at a different x-position. No
                // single row has near-zero content (only the row closest to the diagonal midpoint
                // drops below 20%), so the 4-row threshold fails. Accept ≥4 rows at a 22% cutoff
                // for wide banner-eligible bboxes — the extra 2% captures the rows just above the
                // diagonal midpoint without opening the door for 1–3-row artwork dips.
                ?: run diagonalGutterFallback@{
                    val bboxWidthFraction = width.toDouble() / downscaledWidth.toDouble()
                    if (bboxWidthFraction < 0.5) return@diagonalGutterFallback null
                    val relaxedCutoff = maxRowContent * 0.22
                    val relaxedGutter = widestGutterRun(
                        axisStart = bbox.minY + edgeMarginY,
                        axisEnd = bbox.maxY - edgeMarginY,
                    ) { y ->
                        cropped.rowContentCount(y, innerMinX, innerMaxX) < relaxedCutoff
                    }
                    // Use only the absolute bannerMinH guard (not the bbox-fraction one) — this bbox
                    // may already be a sub-section after a prior vertical split, making height <
                    // the full-page banner height; the bottom stub will be merged by repair steps.
                    relaxedGutter?.takeIf { (start, thickness) ->
                        thickness >= 4 && run {
                            val end = start + thickness - 1
                            val topH = start - bbox.minY
                            val bottomH = bbox.maxY - end
                            val bannerMinH = (downscaledHeight * 0.05).toInt().coerceAtLeast(1)
                            // Banner pattern: tall piece at top, short stub at bottom (topH > bottomH).
                            // Compositional art gaps produce the opposite (gap near top, majority below).
                            topH >= bannerMinH && bottomH >= bannerMinH && bottomH < topH
                        }
                    }?.also { horizontalFromDiagonalFallback = true }
                }
                // Partial-gutter row-transition fallback: handles inter-section row boundaries
                // inside tall bboxes where CC has merged multiple page sections. Panel-border pixels
                // at the transition zone partially block the flood-fill (typical fraction: 15–25%),
                // keeping it below the standard 30% threshold. Requires the bbox to span ≥ 50% of the
                // page height (only arises when the CC covers most of the page — a structural anomaly,
                // not a single-panel bbox), a run of ≥ 7 rows with ≥ 18% flood-fill gutter fraction,
                // and each resulting piece ≥ minPanelDimensionFraction of the page height.
                ?: run partialGutterRowTransitionFallback@{
                    if (height.toDouble() / downscaledHeight.toDouble() < 0.50) return@partialGutterRowTransitionFallback null
                    val relaxedGutter = widestGutterRun(
                        axisStart = bbox.minY + edgeMarginY,
                        axisEnd = bbox.maxY - edgeMarginY,
                    ) { y ->
                        val base = y * cropped.width
                        var g = 0
                        for (x in innerMinX..innerMaxX) if (gutter[base + x]) g++
                        g.toLong() * 1000 >= innerWidth.toLong() * 180L
                    }
                    relaxedGutter?.takeIf { (start, thickness) ->
                        if (thickness < 7) return@takeIf false
                        val end = start + thickness - 1
                        val topH = start - bbox.minY
                        val bottomH = bbox.maxY - end
                        val minDimPx = (downscaledHeight * config.minPanelDimensionFraction).toInt().coerceAtLeast(1)
                        topH >= minDimPx && bottomH >= minDimPx
                    }
                }
                // Dense-border horizontal split: for wide (≥ 70% page width), tall (≥ 50% page
                // height) merged bboxes, detect a drawn panel separator as a thin band of
                // near-full-width rows. Unlike white-gutter splits (sparse rows), drawn panel
                // borders appear as DENSE rows — ≥ 90% of the full bbox width — because the
                // shared ink line spans the entire panel width. The band must be thin (≤ 20 rows)
                // to exclude broad artwork regions (dark backgrounds, large silhouettes). A spike
                // test confirms the border is a true transition: the average content of rows 5–15
                // after the border must be < 80% of full bbox width. Without the spike test,
                // short sub-runs inside a broad artwork zone produce false splits.
                //
                // Minimum piece height: uses the banner-level floor (7%) rather than the standard
                // minPanelDimensionFraction (14%), because the strip produced by this split may be
                // a narrow banner that is wide enough to survive applyGlobalSanityChecks via the
                // banner exception (≥ 50% width, ≥ 7% height).
                //
                // Sets horizontalFromDiagonalFallback = true to bypass the bannerBboxMinHeightPx
                // (25%-of-bbox) guard below — the dense-border split is a genuine panel boundary,
                // not an artifact of a recursive split on an already-narrow sub-bbox.
                //
                // Issue #878: shared border at y=1637–1648 (92%) correctly splits a park-scene
                // splash from the middle strip; post-border rows drop to 64–73%.
                // Issue #880: shared border at y=1575–1585 (100%) splits the top panel from the
                // bottom strips; post-border rows drop to 56–67%.
                ?: run denseBorderHorizontalSplitFallback@{
                    val bboxWidthFraction = width.toDouble() / downscaledWidth.toDouble()
                    val bboxHeightFraction = height.toDouble() / downscaledHeight.toDouble()
                    if (bboxWidthFraction < 0.70) return@denseBorderHorizontalSplitFallback null
                    if (bboxHeightFraction < 0.50) return@denseBorderHorizontalSplitFallback null
                    val fullBboxWidth = bbox.maxX - bbox.minX + 1
                    val borderThresholdK = 900L  // ≥ 90% of fullBboxWidth per 1000
                    val postBorderThresholdK = 800L  // < 80% for spike confirmation
                    val maxBorderThick = 20
                    // Use banner-level minimum (7% of page height) so the strip produced by the
                    // split can be kept by the banner exception in applyGlobalSanityChecks.
                    val bannerMinH = (downscaledHeight * 0.07).toInt().coerceAtLeast(30)
                    var runStartD = -1
                    val borderAxisEnd = bbox.maxY - edgeMarginY
                    var foundResult: Pair<Int, Int>? = null
                    loop@ for (y in (bbox.minY + edgeMarginY)..borderAxisEnd) {
                        val isBorderRow = cropped.rowContentCount(y, bbox.minX, bbox.maxX).toLong() * 1000L >=
                            fullBboxWidth.toLong() * borderThresholdK
                        if (isBorderRow) {
                            if (runStartD < 0) runStartD = y
                            // Run too thick → artwork (dark background / silhouette), not a drawn border.
                            // Cancel; subsequent border row restarts a fresh search.
                            if (y - runStartD >= maxBorderThick) runStartD = -1
                        } else if (runStartD >= 0) {
                            val thickness = y - runStartD
                            if (thickness >= 3) {
                                val end = runStartD + thickness - 1
                                val topH = runStartD - bbox.minY
                                val bottomH = bbox.maxY - end
                                if (topH >= bannerMinH && bottomH >= bannerMinH) {
                                    // Spike test: average content of rows 5–15 after the border must drop
                                    // below 80% of full bbox width, confirming this separates two distinct
                                    // content regions rather than a brief dip inside a dark artwork zone.
                                    val checkStart = end + 5
                                    val checkEnd = minOf(end + 15, bbox.maxY)
                                    if (checkStart <= checkEnd) {
                                        var postSum = 0L
                                        for (cy in checkStart..checkEnd) {
                                            postSum += cropped.rowContentCount(cy, bbox.minX, bbox.maxX)
                                        }
                                        val checkCount = checkEnd - checkStart + 1
                                        if (postSum * 1000L < fullBboxWidth.toLong() * postBorderThresholdK * checkCount) {
                                            foundResult = runStartD to thickness
                                            break@loop
                                        }
                                    }
                                }
                            }
                            runStartD = -1
                        }
                    }
                    foundResult
                }?.also { horizontalFromDiagonalFallback = true }
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
        } ?: run {
            // Bottom-strip flood-fill fallback: handles vertical gutters that are accessible from
            // the BOTTOM border only. When speech bubbles or artwork fill the upper portion of a
            // wide panel row, the flood-fill path from the top border to the vertical boundary is
            // blocked, so the full-column fraction stays well below 30% and projection finds
            // nothing. But the gutter connects to the horizontal row-separator below the bbox, so
            // the bottom 25% of the strip reveals the boundary clearly.
            //
            // Width guard (≥ 70% of page width): restricts to the "suspicious full-width row" case.
            // Narrow sub-panel bboxes are excluded — their border-accessible edge pixels would
            // score above the threshold for unrelated columns.
            //
            // Height guard (≤ 35% of page height): excludes tall bboxes spanning multiple row bands.
            // For a 3-row comic layout each row occupies ≈ 25-30% of the page, so ≤ 35% admits
            // single-row bboxes only. A tall bbox (> 35%) is likely a multi-row section; its bottom
            // strip captures a row boundary rather than an interior panel gutter.
            if (width.toDouble() / downscaledWidth.toDouble() < 0.70) return@run null
            if (height.toDouble() / downscaledHeight.toDouble() > 0.35) return@run null
            val stripH = (height * 0.25).toInt().coerceAtLeast(1)
            val bottomStripStart = bbox.maxY - stripH + 1
            val stripRows = bbox.maxY - bottomStripStart + 1
            val thresholdTimesK = (config.internalGutterFloodFillFraction * 1000).toLong()
            widestGutterRun(
                axisStart = bbox.minX + edgeMarginX,
                axisEnd = bbox.maxX - edgeMarginX,
            ) { x ->
                var g = 0
                for (y in bottomStripStart..bbox.maxY) if (gutter[y * cropped.width + x]) g++
                g.toLong() * 1000 >= stripRows.toLong() * thresholdTimesK
            }?.takeIf { (_, thickness) -> thickness in config.internalGutterMinThickness..40 }
                ?.takeIf {
                    // If the top strip ALSO has a flood-fill gutter run (≥ 45% threshold,
                    // ≥ 7 pixels thick), this is likely a diagonal boundary that
                    // repairDiagonalTwoColumnRows will handle. Skip this fallback to avoid
                    // interfering. Thickness floor = 7: a ≤ 6-pixel run in the top strip is a
                    // thin gap between speech bubbles or artwork (issue #794 fixture shows a
                    // spurious 6-pixel run at x=480 that does NOT represent the vertical boundary
                    // at x=388). Real diagonal boundaries from repairDiagonalTwoColumnRows produce
                    // top-strip runs of ≥ 8 pixels (validated by issue-784 fixtures, topRun=8,10).
                    // Boundary coverage: issue-794 (topRun=6 → fires) and issue-784 (topRun=8,10
                    // → blocked) form the two sides of the thickness-7 gate.
                    val topStripH = (height * 0.20).toInt().coerceAtLeast(1)
                    val topStripEnd = bbox.minY + topStripH - 1
                    val topHasRun = widestGutterRun(
                        axisStart = bbox.minX + edgeMarginX,
                        axisEnd = bbox.maxX - edgeMarginX,
                    ) { x ->
                        var g = 0
                        for (y in bbox.minY..topStripEnd) if (gutter[y * cropped.width + x]) g++
                        g.toLong() * 1000 >= topStripH.toLong() * 450L
                    }?.let { (_, thickness) -> thickness in 7..40 } == true
                    !topHasRun
                }
        } ?: run {
            // Both-strips confirmation: handles vertical gutters visible from BOTH the top and
            // bottom borders but blocked in the inner region (e.g. a tall silhouette that extends
            // leftward into the gutter zone, covering the gutter column in most inner rows).
            // The bottom-strip fallback blocks when the top strip has a run (anti-diagonal guard).
            // This fallback fires precisely in that case: top strip has a run AND bottom strip has
            // a run at the same column → confirmed straight gutter, not a diagonal boundary.
            // A true diagonal produces runs at DIFFERENT column positions in the two strips.
            if (width.toDouble() / downscaledWidth.toDouble() < 0.70) return@run null
            if (height.toDouble() / downscaledHeight.toDouble() > 0.35) return@run null
            val topStripConfH = (height * 0.20).toInt().coerceAtLeast(1)
            val topStripConfEnd = bbox.minY + topStripConfH - 1
            val bottomStripConfH = (height * 0.25).toInt().coerceAtLeast(1)
            val bottomStripConfStart = bbox.maxY - bottomStripConfH + 1
            val thresholdK = (config.internalGutterFloodFillFraction * 1000).toLong()
            val topRun = widestGutterRun(
                axisStart = bbox.minX + edgeMarginX,
                axisEnd = bbox.maxX - edgeMarginX,
            ) { x ->
                var g = 0
                for (y in bbox.minY..topStripConfEnd) if (gutter[y * cropped.width + x]) g++
                g.toLong() * 1000 >= topStripConfH.toLong() * thresholdK
            }?.takeIf { (_, t) -> t in config.internalGutterMinThickness..40 } ?: return@run null
            val botRun = widestGutterRun(
                axisStart = bbox.minX + edgeMarginX,
                axisEnd = bbox.maxX - edgeMarginX,
            ) { x ->
                var g = 0
                for (y in bottomStripConfStart..bbox.maxY) if (gutter[y * cropped.width + x]) g++
                g.toLong() * 1000 >= bottomStripConfH.toLong() * thresholdK
            }?.takeIf { (_, t) -> t in config.internalGutterMinThickness..40 } ?: return@run null
            // Both runs found — verify they overlap in column position (same gutter, not diagonal)
            val (topStart, topThick) = topRun
            val (botStart, botThick) = botRun
            if (topStart + topThick <= botStart || botStart + botThick <= topStart) return@run null
            // Return the wider run as the effective gutter position
            if (topThick >= botThick) topRun else botRun
        }?.also { verticalFromBothStripsConfirmation = true }
        ?: run {
            // Top-strip-only fallback: handles vertical gutters accessible from the TOP PAGE
            // BORDER only (bottom border blocked by artwork, e.g. a tall silhouette extending
            // leftward into the gutter zone below its head). This can only happen for a sub-region
            // that touches the top of the page — sub-regions at larger y values have the gutter
            // accessible via horizontal gutters above them (accessible from side borders), and all
            // existing fallbacks handle those correctly. Restricting to bbox.minY ≤ 3% of page
            // height prevents false positives on bottom-section sub-regions whose top-strip
            // whitespace comes from horizontal gutters, not the actual page border.
            if (width.toDouble() / downscaledWidth.toDouble() < 0.70) return@run null
            if (height.toDouble() / downscaledHeight.toDouble() > 0.35) return@run null
            if (bbox.minY > downscaledHeight * 0.03) return@run null
            val topStripOnlyH = (height * 0.20).toInt().coerceAtLeast(1)
            val topStripOnlyEnd = bbox.minY + topStripOnlyH - 1
            val botStripOnlyH = (height * 0.25).toInt().coerceAtLeast(1)
            val botStripOnlyStart = bbox.maxY - botStripOnlyH + 1
            val threshK = (config.internalGutterFloodFillFraction * 1000).toLong()
            // Guard: bottom strip must have NO flood-fill run. If it does, it's either a diagonal
            // boundary (handled by repairDiagonalTwoColumnRows) or the both-strips case above.
            val hasBotRun = widestGutterRun(
                axisStart = bbox.minX + edgeMarginX,
                axisEnd = bbox.maxX - edgeMarginX,
            ) { x ->
                var g = 0
                for (y in botStripOnlyStart..bbox.maxY) if (gutter[y * cropped.width + x]) g++
                g.toLong() * 1000 >= botStripOnlyH.toLong() * threshK
            }?.let { (_, t) -> t >= config.internalGutterMinThickness } == true
            if (hasBotRun) return@run null
            widestGutterRun(
                axisStart = bbox.minX + edgeMarginX,
                axisEnd = bbox.maxX - edgeMarginX,
            ) { x ->
                var g = 0
                for (y in bbox.minY..topStripOnlyEnd) if (gutter[y * cropped.width + x]) g++
                g.toLong() * 1000 >= topStripOnlyH.toLong() * threshK
            }?.takeIf { (_, t) -> t in 1..40 }
        }?.also { verticalFromBothStripsConfirmation = true }

        val hGutter = effectiveHorizontalGutter?.let { Triple("h", it.first, it.second) }
        val vGutter = effectiveVerticalGutter?.let { Triple("v", it.first, it.second) }
        val rawBestGutter = run {
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
                // When the horizontal gutter came from the diagonal-gutter fallback (22%-relaxed
                // projection), the bottom stub is intentionally small (the diagonal runs across
                // the column, not straight across the page). Skip the bbox-fraction height guard
                // so hGutter beats the vertical column gutter at this level too.
                val bannerEligible = (topH < minDimPx || bottomH < minDimPx) &&
                    width.toDouble() / downscaledWidth >= 0.5 &&
                    minOf(topH, bottomH) >= (downscaledHeight * 0.05).toInt().coerceAtLeast(1) &&
                    (horizontalFromDiagonalFallback || minOf(topH, bottomH) >= (height * 0.25).toInt().coerceAtLeast(1))
                val hWouldBeInvalid = topH < minDimPx || bottomH < minDimPx
                if (bannerEligible) hGutter
                else if (hWouldBeInvalid) {
                    // The horizontal gutter would fail the minDimPx split-validity check.
                    // A thick artwork low-content run (e.g. 169 rows deep in panel interior)
                    // can shadow a thinner but genuine vertical column gutter this way.
                    // When the vertical gutter would produce a valid split, prefer it.
                    val (_, vStart, vThick) = vGutter
                    val vEnd = vStart + vThick - 1
                    val leftW = vStart - bbox.minX
                    val rightW = bbox.maxX - vEnd
                    val minDimPxW = (downscaledWidth * config.minPanelDimensionFraction).toInt().coerceAtLeast(1)
                    if (leftW >= minDimPxW && rightW >= minDimPxW) vGutter
                    else listOfNotNull(hGutter, vGutter).maxByOrNull { it.third }!!
                }
                else listOfNotNull(hGutter, vGutter).maxByOrNull { it.third }!!
            } else {
                listOfNotNull(hGutter, vGutter).maxByOrNull { it.third }
            }
        }

        // Top-strip column-gap detection: scans the top 25% of a wide bbox for vertical column
        // gaps, then measures how far each gap extends downward. Handles two layouts:
        //
        // (A) "Row separator" gaps — end well before the bbox bottom (gapEndFraction < 70%,
        //     extension ≥ max(5% height, 30px)): the top portion is multi-column and the bottom
        //     is a single wide panel. Use the deepest gap end as a HORIZONTAL split point.
        //     Example: 3-panel middle row merges with a wide bottom panel via shared black
        //     borders; the white column gutters in the middle row end at the row boundary.
        //
        // (B) "Column boundary" gaps — extend all the way to near the bbox bottom (within 2%
        //     of height or 10px, whichever is larger): the bbox is a single multi-column row
        //     where full-height projection misses the gutters (columns have dark borders in the
        //     lower portion but white gutter in the upper portion). Collect all such gaps and
        //     split the bbox VERTICALLY at each one.
        //
        // Width guard (≥ 70%): restricts to full-page-width rows; narrow sub-columns have
        // already been separated from their neighbours and need no top-strip re-scan.
        // Height guard (≥ 10%): excludes tiny slivers produced by earlier splits.
        // The standard gutter would be blocked when axis="v" and this bbox is full-width + short
        // (the guard at the split site rejects such splits to avoid false positives from speech
        // bubbles). In that case the standard path returns listOf(bbox) — same as if rawBestGutter
        // were null — so top-strip detection is equally applicable.
        val standardVSplitWouldBeBlocked = rawBestGutter?.let {
            it.first == "v" &&
                width.toDouble() / downscaledWidth >= 0.95 &&
                height.toDouble() / downscaledHeight <= 0.25
        } == true
        if ((rawBestGutter == null || standardVSplitWouldBeBlocked) &&
            width.toDouble() / downscaledWidth >= 0.70 &&
            height.toDouble() / downscaledHeight >= 0.10
        ) {
            val topStripH = (height * 0.25).toInt().coerceAtLeast(1)
            val topStripEnd = bbox.minY + topStripH - 1
            val maxColStrip = (bbox.minX..bbox.maxX).maxOf { x ->
                cropped.colContentCount(x, bbox.minY, topStripEnd)
            }
            if (maxColStrip > 0) {
                val stripCutoff = maxColStrip * 0.10
                val extensionMin = maxOf((height * 0.05).toInt(), 30)
                val minDimPxH = (downscaledHeight * config.minPanelDimensionFraction).toInt().coerceAtLeast(1)
                val minDimPxW = (downscaledWidth * config.minPanelDimensionFraction).toInt().coerceAtLeast(1)
                // Column-boundary threshold: gap must reach within this many rows of the bbox bottom.
                val nearBottomTolerance = maxOf(10, (height * 0.02).toInt())

                data class GapRun(val gapStart: Int, val gapThick: Int, val lastGapY: Int)
                val gapRuns = mutableListOf<GapRun>()
                var runStart3 = -1
                for (x in (bbox.minX + edgeMarginX)..(bbox.maxX - edgeMarginX + 1)) {
                    val inGap = x <= bbox.maxX - edgeMarginX &&
                        cropped.colContentCount(x, bbox.minY, topStripEnd) < stripCutoff
                    if (inGap) {
                        if (runStart3 < 0) runStart3 = x
                    } else if (runStart3 >= 0) {
                        val gapStart = runStart3
                        val gapThick = x - runStart3
                        runStart3 = -1
                        if (gapThick < 3) continue
                        val gapContentThreshold = (gapThick * 0.20).toInt().coerceAtLeast(1)
                        var lastGapY = topStripEnd
                        for (y in (topStripEnd + 1)..bbox.maxY) {
                            if (cropped.rowContentCount(y, gapStart, gapStart + gapThick - 1) > gapContentThreshold) break
                            lastGapY = y
                        }
                        gapRuns.add(GapRun(gapStart, gapThick, lastGapY))
                    }
                }

                // (B) Column-boundary gaps: reach the bottom → split VERTICALLY at each gap
                val colBoundaryGaps = gapRuns.filter { bbox.maxY - it.lastGapY <= nearBottomTolerance }
                if (colBoundaryGaps.isNotEmpty()) {
                    val sortedGaps = colBoundaryGaps.sortedBy { it.gapStart }
                    val subBboxes = mutableListOf<Bbox>()
                    var prevX = bbox.minX
                    for (gap in sortedGaps) {
                        if (gap.gapStart > prevX) {
                            val sub = Bbox(prevX, bbox.minY, gap.gapStart - 1, bbox.maxY)
                            if (sub.maxX - sub.minX + 1 >= minDimPxW) subBboxes.add(sub)
                        }
                        prevX = gap.gapStart + gap.gapThick
                    }
                    if (prevX <= bbox.maxX) {
                        val sub = Bbox(prevX, bbox.minY, bbox.maxX, bbox.maxY)
                        if (sub.maxX - sub.minX + 1 >= minDimPxW) subBboxes.add(sub)
                    }
                    if (subBboxes.size >= 2) {
                        return subBboxes.flatMap {
                            splitSinglePanelRecursively(it, cropped, gutter, depth + 1, downscaledWidth, downscaledHeight)
                        }
                    }
                }

                // (A) Row-separator gaps: end before the bottom → split HORIZONTALLY at deepest gap
                var bestSplitY = -1
                for (gap in gapRuns) {
                    val lastGapY = gap.lastGapY
                    if (lastGapY - topStripEnd < extensionMin) continue
                    val gapEndFraction = (lastGapY - bbox.minY).toDouble() / height
                    if (gapEndFraction >= 0.70) continue
                    val topH = lastGapY - bbox.minY + 1
                    val bottomH = bbox.maxY - lastGapY
                    if (topH < minDimPxH || bottomH < minDimPxH) continue
                    if (lastGapY > bestSplitY) bestSplitY = lastGapY
                }
                if (bestSplitY >= 0) {
                    val topBbox = Bbox(bbox.minX, bbox.minY, bbox.maxX, bestSplitY)
                    val botBbox = Bbox(bbox.minX, bestSplitY + 1, bbox.maxX, bbox.maxY)
                    return splitSinglePanelRecursively(topBbox, cropped, gutter, depth + 1, downscaledWidth, downscaledHeight) +
                        splitSinglePanelRecursively(botBbox, cropped, gutter, depth + 1, downscaledWidth, downscaledHeight)
                }
            }
        }

        val bestGutter = rawBestGutter ?: return listOf(bbox)

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
        //
        // Exception: allow the split when the gutter is a genuine full-height enclosed separator
        // found by projection (verticalGutter == null, meaning flood-fill didn't reach it) AND the
        // gutter column has ZERO dark pixels across the full bbox height. This catches the bottom
        // strip produced by the dense-border horizontal split, which contains two real side-by-side
        // panels separated by a pure-white enclosed gutter. Speech-bubble false gutters always have
        // dark border pixels in the column → colContentCount > 0, so they fail the check.
        if (
            axis == "v" &&
            width.toDouble() / downscaledWidth.toDouble() >= 0.95 &&
            height.toDouble() / downscaledHeight.toDouble() <= 0.25
        ) {
            // Allow the split only when there is clear evidence of a genuine inter-panel gutter.
            // Two cases are accepted:
            // (a) The gutter column is sparse throughout the full bbox height (< 10% dark pixels).
            //     Catches #880: clean white channel between drawn-border side-by-side panels.
            // (b) The gutter was confirmed by the both-strips fallback AND the top portion of the
            //     column is sparse (< 10% of topStripH). Catches #879: a silhouette figure crosses
            //     the gutter column in the inner region but the top strip is white. Speech-bubble
            //     and artwork false gutters have content in the column including at the top, and
            //     are rejected by the top-strip check.
            val topStripHForGuard = (height * 0.25).toInt().coerceAtLeast(1)
            val gutterColContent = cropped.colContentCount(start, bbox.minY, bbox.maxY)
            val topStripColContent = if (verticalFromBothStripsConfirmation) {
                cropped.colContentCount(start, bbox.minY, bbox.minY + topStripHForGuard - 1)
            } else 0
            // For the projection-based sparse-column exception (gutterColContent < 10%):
            // a genuine dense-border inter-panel gutter has the column white throughout the
            // full strip, with at most a thin horizontal border crossing it at the top and
            // bottom edges (typically 1–3 dark pixels in the edge 10%). A speech-balloon
            // interior that spans the inner section of the strip has the surrounding panel
            // artwork ABOVE and BELOW the balloon — producing significantly denser content
            // at x=start in the top and bottom 10% of the strip.
            // Require BOTH edges of the gutter column to be sparse so that genuine border
            // gutters (#880) pass but speech-balloon interiors (#891) are rejected.
            // Top edge: ≤ 25% content (× 4); bottom edge: ≤ 33% content (× 3). The asymmetry
            // is intentional — the drawn bottom border of adjacent panels can contribute
            // ~26% content at the gutter column's bottom edge (#880: botEdgeContent=11/43=25.6%),
            // while speech-balloon false gutters have ≥ 37% at both edges (#891).
            val edgeCheckH = (height * 0.10).toInt().coerceAtLeast(1)
            val topEdgeContent = cropped.colContentCount(start, bbox.minY, bbox.minY + edgeCheckH - 1)
            val botEdgeContent = cropped.colContentCount(start, bbox.maxY - edgeCheckH + 1, bbox.maxY)
            val isGenuineFullHeightGutter = effectiveVerticalGutter != null && (
                (gutterColContent.toLong() * 10L <= height.toLong() &&
                    topEdgeContent * 4L <= edgeCheckH.toLong() &&
                    botEdgeContent * 3L <= edgeCheckH.toLong()) ||
                (verticalFromBothStripsConfirmation && topStripColContent.toLong() * 10L <= topStripHForGuard.toLong())
            )
            if (!isGenuineFullHeightGutter) return listOf(bbox)
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
                if (bboxWidthFraction < 0.5 || shortPieceHeight < bannerMinHeightPx || (!horizontalFromDiagonalFallback && shortPieceHeight < bannerBboxMinHeightPx)) return listOf(bbox)
            }
        } else {
            val leftWidth = start - bbox.minX
            val rightWidth = bbox.maxX - end
            val minDimPx = (downscaledWidth * config.minPanelDimensionFraction).toInt().coerceAtLeast(1)
            if (leftWidth < minDimPx || rightWidth < minDimPx) return listOf(bbox)
            // If the bbox is wide enough to survive as a banner (≥ 50% page width, height ≥ 7%
            // page height) but too short for the standard min-dimension check (height < 14%), block
            // a vertical split that would produce two halves both below the banner width threshold
            // (< 50% each). The split would destroy the banner-eligible wide panel by fragmenting it
            // into two pieces that each fail both sanity-check paths and are filtered entirely.
            // Example: #879 page 34 top sub-region (width=69%, height=14%) split at an internal
            // whitespace column produces two 30%+35% halves — neither survives the banner exception.
            // Allow the split when at least one half is ≥ 50% wide, which is the scenario where the
            // split is genuinely revealing a banner and an adjacent section.
            val bboxHeightFraction = height.toDouble() / downscaledHeight.toDouble()
            if (bboxHeightFraction < config.minPanelDimensionFraction) {
                val bannerWidthPx = downscaledWidth * 0.5
                val bboxWidthFraction = width.toDouble() / downscaledWidth.toDouble()
                val bannerBannerMinHFraction = 0.07
                if (
                    bboxWidthFraction >= 0.5 &&
                    height.toDouble() / downscaledHeight.toDouble() >= bannerBannerMinHFraction &&
                    leftWidth < bannerWidthPx &&
                    rightWidth < bannerWidthPx
                ) return listOf(bbox)
            }
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
        // A wide panel (≥ 50% of page width) that is taller than 7% of the page is a real
        // banner even if its height doesn't reach the full-panel threshold. 7% (not 5%) prevents
        // diagonal-transition slivers (~5–6% tall) produced by splitSinglePanelRecursively's
        // diagonal-gutter fallback from surviving as spurious panels (#797/#802).
        val bannerWidthThreshold = originalWidth * 0.5
        val bannerMinHeight = (originalHeight * 0.07).toInt().coerceAtLeast(1)
        // A moderately wide panel (≥ 25% page width) that is taller than 10% of the page is also
        // kept. This catches top-panel fragments produced when a page's top region has internal
        // whitespace accessible from the top border: CC detection splits the region into two
        // adjacent CCs each ~30-35% wide and ~14% tall — too narrow for the ≥ 50% banner check
        // but real panels. The 10% height floor is well above the 5-6% diagonal slivers that
        // the 7% floor is designed to reject (issue #879).
        val narrowBannerWidthThreshold = originalWidth * 0.25
        val narrowBannerMinHeight = (originalHeight * 0.10).toInt().coerceAtLeast(1)
        val filtered = regions.filter {
            (it.width >= minWidth && it.height >= minHeight) ||
                (it.width >= bannerWidthThreshold && it.height >= bannerMinHeight) ||
                (it.width >= narrowBannerWidthThreshold && it.height >= narrowBannerMinHeight)
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
                val of = larger.overlapFraction(smaller)
                if (of > config.overlapRejectFraction) {
                    return null
                }
            }
        }

        val totalPanelArea = meaningful.sumOf { it.area() }
        val summedCoverage = totalPanelArea.toDouble() / pageArea.toDouble()
        if (summedCoverage < config.minTotalCoverageFraction) {
            return null
        }
        // Summed panel area exceeding the page area means panels are overlapping meaningfully
        // (even if pairwise overlaps are under the reject threshold). Real panels tile with
        // small gutters; overlap sums this large indicate CC merged content across panel
        // boundaries and gave us big-blob bboxes stacked on each other. Fall back rather than
        // present the user with overlapping zoom windows.
        if (summedCoverage > config.maxSummedCoverageFraction) {
            return null
        }

        return meaningful
    }

    /**
     * Projection detects rows from heavy content runs. Real panel tops often include a short
     * border/caption/balloon fragment above that run; if the fragment is thinner than
     * projectionMinBandThickness, it is not a standalone row and the panel top is cut off. Expand
     * each bbox upward into nearby same-column fragments, but never past an overlapping panel
     * above it.
     */
    private fun expandTopsToNearbyContentFragments(
        bboxes: List<Bbox>,
        cropped: CroppedMask,
    ): List<Bbox> {
        if (bboxes.isEmpty()) return bboxes
        val maxBridgeGap = 12
        val maxScanDistance = maxOf(96, (cropped.height * 0.06).toInt())
        val minBlockingPanelHeight = (cropped.height * config.minPanelDimensionFraction).toInt().coerceAtLeast(1)
        return bboxes.map { bbox ->
            val width = bbox.maxX - bbox.minX + 1
            val minRowContent = maxOf(config.marginContentThreshold, (width * 0.05).toInt())
            val nearestBlockingPanelBottom = bboxes
                .filter { other ->
                    other != bbox &&
                        other.maxY - other.minY + 1 >= minBlockingPanelHeight &&
                        other.maxY < bbox.minY &&
                        horizontalOverlapFraction(bbox, other) >= 0.25
                }
                .maxOfOrNull { it.maxY }
                ?: -1
            val minScanY = maxOf(0, nearestBlockingPanelBottom + 1, bbox.minY - maxScanDistance)

            var y = bbox.minY - 1
            var emptyGap = 0
            var expandedMinY = bbox.minY
            while (y >= minScanY) {
                val content = cropped.rowContentCount(y, bbox.minX, bbox.maxX)
                if (content >= minRowContent) {
                    expandedMinY = y
                    emptyGap = 0
                } else {
                    emptyGap++
                    if (emptyGap > maxBridgeGap) break
                }
                y--
            }
            if (bbox.minY - expandedMinY >= 4) {
                if (nearestBlockingPanelBottom >= 0) {
                    var blankRowsAfterBlocker = 0
                    for (blankY in nearestBlockingPanelBottom + 1 until expandedMinY) {
                        if (cropped.rowContentCount(blankY, bbox.minX, bbox.maxX) < minRowContent) {
                            blankRowsAfterBlocker++
                        }
                    }
                    if (blankRowsAfterBlocker < 4) return@map bbox
                }
                Bbox(bbox.minX, expandedMinY, bbox.maxX, bbox.maxY)
            } else {
                bbox
            }
        }
    }

    private fun horizontalOverlapFraction(a: Bbox, b: Bbox): Double {
        val overlap = minOf(a.maxX, b.maxX) - maxOf(a.minX, b.minX) + 1
        if (overlap <= 0) return 0.0
        val smallerWidth = minOf(a.maxX - a.minX + 1, b.maxX - b.minX + 1)
        return overlap.toDouble() / smallerWidth.toDouble()
    }

    private data class Band(val start: Int, val end: Int)

    /** Returns the x-centre of each gutter gap between adjacent column bands. */
    private fun gutterCentresFromColBands(colBands: List<Band>): List<Int> =
        (0 until colBands.size - 1).map { i -> (colBands[i].end + colBands[i + 1].start) / 2 }

    /**
     * For a suspicious (full-width) row band, attempts to confirm a vertical gutter boundary
     * using luma-gradient energy. Each [candidateXs] position is checked with two gates:
     *
     * 1. **Flood-fill gutter evidence**: at least [PanelDetectionConfig.energyValleyMinPartialGutterFraction]
     *    of the band's rows must be flood-fill gutter pixels at the candidate column. Flood-fill
     *    gutter pixels are background pixels reachable from the page border, which correctly
     *    excludes internal panel white-space (enclosed by panel content, not border-connected).
     *    A real bubble occludes only part of the gutter height; rows outside the bubble retain
     *    border-connected background at the gutter column.
     *
     * 2. **Energy valley**: the minimum luma-gradient energy in the search window must be below
     *    [PanelDetectionConfig.energyValleyDepthRatio] × the median profile energy.
     *
     * Returns null if no candidate passes both gates.
     */
    private fun energyValleySplit(
        grid: PixelGrid,
        gutter: BooleanArray,
        cropped: CroppedMask,
        rowBand: Band,
        colBands: List<Band>,
        candidateXs: List<Int>,
    ): List<Band>? {
        val bandHeight = rowBand.end - rowBand.start + 1
        val energies = columnLumaEnergyProfile(grid, cropped, rowBand.start, rowBand.end)
        val sorted = energies.copyOf().also { it.sort() }
        val medianEnergy = sorted[sorted.size / 2]
        if (medianEnergy == 0f) return null

        val threshold = medianEnergy * config.energyValleyDepthRatio
        val windowHalf = (cropped.width * config.energyValleyWindowFraction).toInt().coerceAtLeast(1)

        for (candidateX in candidateXs) {
            // Gate 1: the gutter must be visible as border-connected background in enough rows.
            // Using flood-fill pixels (not raw mask) rejects internal panel white-space that is
            // not connected to the page border and therefore not a real inter-panel gutter.
            var gutterRows = 0
            for (cy in rowBand.start..rowBand.end) {
                if (gutter[cy * cropped.width + candidateX]) gutterRows++
            }
            if (gutterRows.toDouble() / bandHeight < config.energyValleyMinPartialGutterFraction) continue

            // Gate 2: energy valley at the candidate position.
            val wStart = (candidateX - windowHalf).coerceAtLeast(0)
            val wEnd = (candidateX + windowHalf).coerceAtMost(cropped.width - 1)
            var minEnergy = Float.MAX_VALUE
            var minX = candidateX
            for (x in wStart..wEnd) {
                if (energies[x] < minEnergy) {
                    minEnergy = energies[x]
                    minX = x
                }
            }
            if (minEnergy < threshold) {
                val left = Band(colBands[0].start, minX - 1)
                val right = Band(minX + 1, colBands[0].end)
                if (left.end >= left.start && right.end >= right.start) return listOf(left, right)
            }
        }
        return null
    }

    /** Sum of |luma[y][x] − luma[y+1][x]| per column for rows [yStart, yEnd) in CroppedMask coords. */
    private fun columnLumaEnergyProfile(grid: PixelGrid, cropped: CroppedMask, yStart: Int, yEnd: Int): FloatArray =
        FloatArray(cropped.width) { cx ->
            val gx = cx + cropped.offsetX
            var energy = 0f
            for (cy in yStart until yEnd) {
                energy += kotlin.math.abs(
                    grid.get(gx, cy + cropped.offsetY) - grid.get(gx, cy + 1 + cropped.offsetY),
                ).toFloat()
            }
            energy
        }

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
        val panelMask = PanelMaskBinarizer(config).binarize(grid) ?: return null
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

    internal class CroppedMask(
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

