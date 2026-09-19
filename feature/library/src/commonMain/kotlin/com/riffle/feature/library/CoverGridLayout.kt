package com.riffle.feature.library

import kotlin.math.floor
import kotlin.math.max

/**
 * Pure layout arithmetic behind every cover grid.
 *
 * Both platforms render their cover grids with `GridCells.Adaptive`, so the only thing that
 * decides how many covers land on a row is the minimum cell size. That number used to be
 * computed in `app/.../CoverGridSizing.kt` (Android) and hard-coded to `120.dp` (iOS), which meant
 * the size-class breakpoint and the pinch-zoom clamp only existed on Android. Keeping the
 * arithmetic here lets the Compose-Multiplatform grids the iOS app renders share it, and lets both
 * platforms' test suites pin the same numbers.
 *
 * No Compose types on purpose: callers multiply the returned dp scalar into their own `Dp`.
 */
object CoverGridLayout {

    /** Window width (dp) at which the Expanded size class begins — ADR 0019. */
    const val EXPANDED_WIDTH_BREAKPOINT_DP: Float = 840f

    /** Minimum adaptive cell for the full-page cover grids (All Books, series, collections). */
    const val PHONE_MIN_CELL_DP: Float = 112f
    const val EXPANDED_MIN_CELL_DP: Float = 160f

    /** Minimum adaptive cell for the denser home-shelf / To Read grids. */
    const val PHONE_SHELF_MIN_CELL_DP: Float = 112f
    const val EXPANDED_SHELF_MIN_CELL_DP: Float = 140f

    /** Lower/upper bounds for the user's persisted pinch-to-zoom multiplier. */
    const val MIN_COVER_SCALE: Float = 0.7f
    const val MAX_COVER_SCALE: Float = 1.6f

    /** Inter-cell gap the column arithmetic assumes. */
    const val GRID_SPACING_DP: Float = 8f

    /** Clamps a raw pinch multiplier into the supported zoom range. */
    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_COVER_SCALE, MAX_COVER_SCALE)

    /**
     * Minimum adaptive cell size for a full-page cover grid on a window [windowWidthDp] wide,
     * scaled by the user's pinch multiplier.
     */
    fun minCellSizeDp(windowWidthDp: Float, scale: Float): Float =
        baseMinCell(windowWidthDp, PHONE_MIN_CELL_DP, EXPANDED_MIN_CELL_DP) * clampScale(scale)

    /** As [minCellSizeDp] but for the denser home-shelf grids. */
    fun shelfMinCellSizeDp(windowWidthDp: Float, scale: Float): Float =
        baseMinCell(windowWidthDp, PHONE_SHELF_MIN_CELL_DP, EXPANDED_SHELF_MIN_CELL_DP) * clampScale(scale)

    private fun baseMinCell(windowWidthDp: Float, phone: Float, expanded: Float): Float =
        if (windowWidthDp >= EXPANDED_WIDTH_BREAKPOINT_DP) expanded else phone

    /**
     * How many columns `GridCells.Adaptive(minCellDp)` yields inside [availableWidthDp] of
     * content width (i.e. after the grid's own horizontal content padding). Never fewer than 1.
     */
    fun columns(availableWidthDp: Float, minCellDp: Float, spacingDp: Float): Int =
        max(1, floor((availableWidthDp + spacingDp) / (minCellDp + spacingDp)).toInt())

    /**
     * Number of cover tiles a two-row section preview shows before the "see more" tile.
     *
     * `cols × 2 − 1` covers plus the see-more tile is exactly `cols × 2` slots, so the see-more
     * tile always ends the second row instead of being orphaned alone on a third one.
     */
    fun sectionPreviewCount(columns: Int): Int = max(1, columns * 2 - 1)

    /** True when a section has more items than its two-row preview can show. */
    fun shouldShowSeeMore(itemCount: Int, previewCount: Int): Boolean = itemCount > previewCount

    /**
     * [com.riffle.core.models.LibraryItem.seriesName] carries its sequence as a ` #` suffix when
     * the source provides one. A series detail screen already names the series in its app bar, so
     * only the position belongs on each cover.
     */
    fun seriesPositionBadge(seriesName: String?): String? {
        val sequence = seriesName
            ?.substringAfterLast(" #", missingDelimiterValue = "")
            ?.trim()
            .orEmpty()
        return sequence.takeIf { it.isNotEmpty() }?.let { "#$it" }
    }
}
