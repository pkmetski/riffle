package com.riffle.core.domain.comic.panel

import kotlin.math.min

/**
 * Compose-agnostic math for the Panel View camera. Given a viewport, the source image dimensions,
 * and a panel bounding box, returns the `(scale, translationX, translationY)` triple that — when
 * applied to a fit-whole image via a graphics layer whose pivot is the composable centre — makes
 * the panel fill the viewport centred, letterbox-aware.
 *
 * Pure math; unit-tested. Used by the CBZ Panel View renderer (ADR 0043).
 */
data class PanelFitTransform(
    val scale: Float,
    val translationX: Float,
    val translationY: Float,
) {
    companion object {
        val Identity = PanelFitTransform(1f, 0f, 0f)

        fun compute(
            viewportWidth: Int,
            viewportHeight: Int,
            imageWidth: Int,
            imageHeight: Int,
            panel: PanelRegion,
        ): PanelFitTransform {
            if (viewportWidth <= 0 || viewportHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
                return Identity
            }
            val fitScale = min(
                viewportWidth.toFloat() / imageWidth.toFloat(),
                viewportHeight.toFloat() / imageHeight.toFloat(),
            )
            val displayedW = imageWidth * fitScale
            val displayedH = imageHeight * fitScale
            val letterboxX = (viewportWidth - displayedW) / 2f
            val letterboxY = (viewportHeight - displayedH) / 2f
            val panelDisplayedW = panel.width * fitScale
            val panelDisplayedH = panel.height * fitScale
            if (panelDisplayedW <= 0 || panelDisplayedH <= 0) return Identity
            val zoom = min(
                viewportWidth.toFloat() / panelDisplayedW,
                viewportHeight.toFloat() / panelDisplayedH,
            )
            val panelCentroidX = letterboxX + (panel.x + panel.width / 2f) * fitScale
            val panelCentroidY = letterboxY + (panel.y + panel.height / 2f) * fitScale
            return PanelFitTransform(
                scale = zoom,
                translationX = zoom * (viewportWidth / 2f - panelCentroidX),
                translationY = zoom * (viewportHeight / 2f - panelCentroidY),
            )
        }
    }
}
