package com.riffle.core.domain.comic.panel

import com.riffle.core.domain.comic.PanelOverflowBehavior
import kotlin.math.min

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
        // Only fire in portrait: in landscape the full-width panel already has a wide viewport and doesn't need assist.
        val isWideOverflow = isPortrait && widthRatio >= 0.9f && panelDisplayW < viewportHeight
        // Symmetric: tall panels only need assist in portrait viewport (landscape already gives height room).
        val isTallOverflow = isLandscape && heightRatio >= 0.9f && panelDisplayH < viewportWidth
        return isWideOverflow || isTallOverflow
    }

    fun applyOverflow(
        panels: List<PanelRegion>,
        imageWidth: Int,
        imageHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        behavior: PanelOverflowBehavior,
    ): List<PanelRegion> {
        if (behavior == PanelOverflowBehavior.OFF || behavior == PanelOverflowBehavior.AUTO_ROTATE) return panels
        return panels.flatMap { panel ->
            if (isOverflowing(panel, imageWidth, imageHeight, viewportWidth, viewportHeight)) {
                panel.splitAtCenter(imageWidth, imageHeight)
            } else {
                listOf(panel)
            }
        }
    }

    private fun PanelRegion.splitAtCenter(imageWidth: Int, imageHeight: Int): List<PanelRegion> {
        val widthRatio = width.toFloat() / imageWidth
        val heightRatio = height.toFloat() / imageHeight
        return if (widthRatio >= heightRatio) {
            val halfWidth = width / 2
            listOf(
                PanelRegion(x, y, halfWidth, height),
                PanelRegion(x + halfWidth, y, width - halfWidth, height),
            )
        } else {
            val halfHeight = height / 2
            listOf(
                PanelRegion(x, y, width, halfHeight),
                PanelRegion(x, y + halfHeight, width, height - halfHeight),
            )
        }
    }
}
