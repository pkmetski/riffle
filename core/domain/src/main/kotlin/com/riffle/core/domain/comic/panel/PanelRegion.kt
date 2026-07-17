package com.riffle.core.domain.comic.panel

import kotlinx.serialization.Serializable

/**
 * A single panel's bounding box in the source image's coordinate space (pre-downscale).
 * Values are absolute pixel coordinates on the full-resolution page.
 */
@Serializable
data class PanelRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0) { "PanelRegion dimensions must be positive" }
        require(x >= 0 && y >= 0) { "PanelRegion origin must be non-negative" }
    }

    val right: Int get() = x + width
    val bottom: Int get() = y + height

    fun area(): Long = width.toLong() * height.toLong()

    /** Fraction of `other` that overlaps this region. `0.0` = disjoint, `1.0` = fully contained. */
    fun overlapFraction(other: PanelRegion): Double {
        val ox = maxOf(x, other.x)
        val oy = maxOf(y, other.y)
        val or = minOf(right, other.right)
        val ob = minOf(bottom, other.bottom)
        if (or <= ox || ob <= oy) return 0.0
        val intersection = (or - ox).toLong() * (ob - oy).toLong()
        return intersection.toDouble() / other.area().toDouble()
    }
}

/** Where a page's panel regions came from. */
@Serializable
enum class PanelSource {
    /** From the on-device panel detector. */
    Auto,

    /** Detector fell back to a single whole-page region; caller renders Fit Whole for this page. */
    Fallback,
}

/**
 * Detector output for one page. `panels` is never empty — a fallback produces a single region
 * spanning the full page and `source = Fallback`, so callers can uniformly walk `panels[0]`.
 */
@Serializable
data class PagePanels(
    val pageIndex: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val panels: List<PanelRegion>,
    val source: PanelSource,
) {
    init {
        require(panels.isNotEmpty()) { "PagePanels must contain at least one region" }
    }

    /** Convenience: this page is a Fit-Whole fallback (single region covering the page). */
    val isFallback: Boolean get() = source == PanelSource.Fallback
}
