package com.riffle.core.domain.comic.panel

import com.riffle.core.domain.comic.PanelOverflowBehavior
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object PanelOverflowTransform {

    /**
     * Minimum zoom gain a half-split must achieve for a panel to be split.
     * A true banner (wide+short in portrait) roughly doubles its zoom when halved (gain ≈ 2.0).
     * Splitting a panel along an axis that is not its binding constraint gains nothing (≈ 1.0):
     * the "slices" are the same fit view merely panned with black voids — the senseless pan
     * reported on single-panel pages. 1.5 sits between the two. Note the gain is viewport
     * dependent: a full-page splash panel splits left/right on a tall 20:9 phone (gain ≈ 1.57,
     * each half genuinely magnified) but stays whole on a 16:9 display (gain ≈ 1.25).
     */
    private const val MIN_SPLIT_ZOOM_GAIN = 1.5f

    /** A panel must span at least this fraction of the page along the viewport's long axis. */
    private const val SPAN_THRESHOLD = 0.85f

    /**
     * Decides whether [panel] should be split and along which axis. Returns `true` for a
     * horizontal (left/right) split, `false` for a vertical (top/bottom) split, or `null` when
     * the panel should stay whole.
     *
     * The direction is the axis whose half-split yields the larger zoom gain — i.e. the axis
     * that is the binding constraint of the whole-panel fit. Splitting is only worthwhile when
     * that gain clears [MIN_SPLIT_ZOOM_GAIN]; otherwise the slices render at (nearly) the same
     * zoom as the whole panel and the extra tap conveys nothing.
     */
    private fun splitHorizontallyOrNull(
        panel: PanelRegion,
        imageWidth: Int,
        imageHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ): Boolean? {
        if (viewportWidth <= 0 || viewportHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return null
        val widthRatio = panel.width.toFloat() / imageWidth
        val heightRatio = panel.height.toFloat() / imageHeight
        val isPortrait = viewportHeight > viewportWidth
        val isLandscape = viewportWidth > viewportHeight
        val spansSplitAxis = (isPortrait && widthRatio >= SPAN_THRESHOLD) ||
            (isLandscape && heightRatio >= SPAN_THRESHOLD)
        if (!spansSplitAxis) return null
        // Measure the gain with the SAME function the renderer uses for the camera, so the
        // split decision can never drift from the zoom the split actually delivers.
        fun zoomFor(p: PanelRegion) =
            PanelFitTransform.compute(viewportWidth, viewportHeight, imageWidth, imageHeight, p).scale
        val zoomWhole = zoomFor(panel)
        val gainHorizontal =
            zoomFor(panel.copy(width = (panel.width / 2).coerceAtLeast(1))) / zoomWhole
        val gainVertical =
            zoomFor(panel.copy(height = (panel.height / 2).coerceAtLeast(1))) / zoomWhole
        if (max(gainHorizontal, gainVertical) < MIN_SPLIT_ZOOM_GAIN) return null
        return gainHorizontal >= gainVertical
    }

    fun isOverflowing(
        panel: PanelRegion,
        imageWidth: Int,
        imageHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ): Boolean = splitHorizontallyOrNull(panel, imageWidth, imageHeight, viewportWidth, viewportHeight) != null

    /**
     * Applies the overflow behavior to the panel list. For SPLIT and SMART_SPLIT, overflowing
     * panels are divided into two halves along the axis that maximises zoom gain. SMART_SPLIT
     * uses [energySampler] to find the lowest-energy seam in the panel; falls back to
     * dead-centre when the sampler is null.
     *
     * @param energySampler Returns a FloatArray of energies along the requested split axis
     * (columns when `splitHorizontally` is true, rows otherwise). Length may differ from the
     * panel's pixel dimension — the split position is derived by normalising the index back to
     * panel pixel space.
     */
    fun applyOverflow(
        panels: List<PanelRegion>,
        imageWidth: Int,
        imageHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        behavior: PanelOverflowBehavior,
        energySampler: ((panel: PanelRegion, splitHorizontally: Boolean) -> FloatArray)? = null,
    ): List<PanelRegion> {
        if (behavior == PanelOverflowBehavior.OFF) return panels
        return panels.flatMap { panel ->
            val splitHorizontally =
                splitHorizontallyOrNull(panel, imageWidth, imageHeight, viewportWidth, viewportHeight)
            when {
                splitHorizontally == null -> listOf(panel)
                behavior == PanelOverflowBehavior.SMART_SPLIT && energySampler != null ->
                    panel.splitAtSmartSeam(energySampler(panel, splitHorizontally), splitHorizontally)
                else -> panel.splitAtCenter(splitHorizontally)
            }
        }
    }

    private fun PanelRegion.splitAtCenter(isWide: Boolean): List<PanelRegion> =
        if (isWide) {
            val half = width / 2
            listOf(PanelRegion(x, y, half, height), PanelRegion(x + half, y, width - half, height))
        } else {
            val half = height / 2
            listOf(PanelRegion(x, y, width, half), PanelRegion(x, y + half, width, height - half))
        }

    private fun PanelRegion.splitAtSmartSeam(energies: FloatArray, isWide: Boolean): List<PanelRegion> {
        val axisLength = if (isWide) width else height
        if (energies.isEmpty()) return splitAtCenter(isWide)
        // Search the middle third only so the split never lands near an edge.
        val start = energies.size / 3
        val end = ((energies.size * 2) / 3).coerceAtLeast(start + 1)
        var minEnergy = Float.MAX_VALUE
        var minIndex = energies.size / 2
        for (i in start until end) {
            if (energies[i] < minEnergy) {
                minEnergy = energies[i]
                minIndex = i
            }
        }
        // Normalise sampler index → panel pixel coordinate, clamped so neither half is empty.
        val splitPx = (minIndex.toFloat() / energies.size * axisLength)
            .roundToInt()
            .coerceIn(1, axisLength - 1)
        return if (isWide) {
            listOf(PanelRegion(x, y, splitPx, height), PanelRegion(x + splitPx, y, width - splitPx, height))
        } else {
            listOf(PanelRegion(x, y, width, splitPx), PanelRegion(x, y + splitPx, width, height - splitPx))
        }
    }
}
