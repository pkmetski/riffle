package com.riffle.core.domain.comic.panel

/**
 * Sorts an unordered set of panel regions into reading order.
 *
 * Row-band heuristic: cluster panels into horizontal rows by y-overlap, sort rows top-to-bottom,
 * sort panels within a row left-to-right. Handles Western grid, terrace, T-shape, and staircase
 * layouts correctly; degrades on layouts with heavy vertical overlap (splash-with-insets) — for
 * those the user's escape hatch on the peek overlay is the intended answer.
 *
 * RTL manga stays deferred (ADR 0042). When it lands, only the within-row sort flips.
 */
class PanelOrderer(
    private val rowOverlapFraction: Double = 0.5,
) {
    /**
     * Return the input panels in reading order. Stable: for a pair of panels that overlap on both
     * axes, the one with the smaller y wins the row assignment, then the smaller x wins the row
     * position — so a Fallback single-region page returns the same single region unchanged.
     */
    fun order(panels: List<PanelRegion>): List<PanelRegion> {
        if (panels.size <= 1) return panels

        // Sort candidates top-to-bottom first so rows accumulate in reading order.
        val byY = panels.sortedBy { it.y }
        val rows = mutableListOf<MutableList<PanelRegion>>()

        for (panel in byY) {
            val row = rows.firstOrNull { existing -> sharesRowWith(existing, panel) }
            if (row != null) row.add(panel) else rows.add(mutableListOf(panel))
        }

        // Rows are already discovered top-to-bottom; sort within each row left-to-right.
        return rows.flatMap { row -> row.sortedBy { it.x } }
    }

    private fun sharesRowWith(row: List<PanelRegion>, candidate: PanelRegion): Boolean {
        // A candidate belongs to a row if it y-overlaps the row's y-extent by at least
        // [rowOverlapFraction] of the SHORTER of (candidate, row-extent).
        val rowTop = row.minOf { it.y }
        val rowBottom = row.maxOf { it.bottom }
        val overlapTop = maxOf(rowTop, candidate.y)
        val overlapBottom = minOf(rowBottom, candidate.bottom)
        if (overlapBottom <= overlapTop) return false
        val overlap = overlapBottom - overlapTop
        val shorter = minOf(rowBottom - rowTop, candidate.height)
        return overlap.toDouble() / shorter.toDouble() >= rowOverlapFraction
    }
}
