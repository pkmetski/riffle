package com.riffle.core.domain.comic.panel

import com.riffle.core.domain.comic.PanelOverflowBehavior
import kotlin.math.min
import kotlin.math.roundToInt

object PanelOverflowTransform {

    fun isOverflowing(
        panel: PanelRegion,
        imageWidth: Int,
        imageHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ): Boolean {
        val fitScale = min(viewportWidth.toFloat() / imageWidth, viewportHeight.toFloat() / imageHeight)
        val panelDisplayW = panel.width * fitScale
        val panelDisplayH = panel.height * fitScale
        val widthRatio = panel.width.toFloat() / imageWidth
        val heightRatio = panel.height.toFloat() / imageHeight
        val isPortrait = viewportHeight > viewportWidth
        val isLandscape = viewportWidth > viewportHeight
        val isWideOverflow = isPortrait && widthRatio >= 0.85f && panelDisplayW < viewportHeight
        val isTallOverflow = isLandscape && heightRatio >= 0.85f && panelDisplayH < viewportWidth
        return isWideOverflow || isTallOverflow
    }

    /**
     * Applies the overflow behavior to the panel list. For SPLIT and SMART_SPLIT, overflowing
     * panels are divided into two halves. SMART_SPLIT uses [energySampler] to find the
     * lowest-energy seam in the panel; falls back to dead-centre when the sampler is null.
     *
     * @param energySampler Returns a FloatArray of energies along the split axis (columns for
     * wide panels, rows for tall panels). Length may differ from the panel's pixel dimension —
     * the split position is derived by normalising the index back to panel pixel space.
     */
    fun applyOverflow(
        panels: List<PanelRegion>,
        imageWidth: Int,
        imageHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        behavior: PanelOverflowBehavior,
        energySampler: ((PanelRegion) -> FloatArray)? = null,
    ): List<PanelRegion> {
        if (behavior == PanelOverflowBehavior.OFF) return panels
        return panels.flatMap { panel ->
            if (isOverflowing(panel, imageWidth, imageHeight, viewportWidth, viewportHeight)) {
                val widthRatio = panel.width.toFloat() / imageWidth
                val heightRatio = panel.height.toFloat() / imageHeight
                val isWide = widthRatio >= heightRatio
                if (behavior == PanelOverflowBehavior.SMART_SPLIT && energySampler != null) {
                    panel.splitAtSmartSeam(energySampler(panel), isWide)
                } else {
                    panel.splitAtCenter(isWide)
                }
            } else {
                listOf(panel)
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
        // Search the central 40–60% so the split stays visually close to the midpoint.
        // The wider middle-third window (33–67%) allowed seams too close to the edges —
        // for scene panels with no natural seam, the lowest-energy column often lands at
        // the edge of the search range (≈33% or ≈67%), producing an asymmetric split.
        val start = energies.size * 4 / 10
        val end = (energies.size * 6 / 10).coerceAtLeast(start + 1)
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
