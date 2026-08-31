package com.riffle.core.domain.comic.panel

import kotlinx.serialization.Serializable

object PanelEngineVersion {
    const val CURRENT = "1.0.0"
}

/**
 * Full configuration for the on-device panel detection engine. All tunable thresholds
 * and algorithm parameters live here so they can be serialised from a remote definitions
 * file and updated independently of the app binary (ADR 0063).
 *
 * Defaults reproduce the behaviour of the original hardcoded constants.
 */
@Serializable
data class PanelDetectionConfig(
    /**
     * Minimum interpreter version required to execute this config. The app rejects
     * (and falls back to its bundled config) if its interpreter version is older.
     */
    val minInterpreterVersion: String = PanelEngineVersion.CURRENT,

    // ── Binarization ──────────────────────────────────────────────────────────

    /** Local adaptive threshold constant (was PanelMaskBinarizer.LOCAL_C). */
    val localAdaptiveConstant: Long = 10L,

    /**
     * A pixel is content if its luma differs from the page background by at least this
     * much [0, 255]. Also used by PanelMaskBinarizer as GLOBAL_CONTRAST_CUTOFF.
     */
    val backgroundContrastThreshold: Int = 50,

    /** Std-dev threshold for the texture classifier. Also used by PanelMaskBinarizer. */
    val textureStdDevThreshold: Double = 12.0,

    /** Half-side of the texture detection window (pixels). Also used by PanelMaskBinarizer. */
    val textureWindowRadius: Int = 2,

    // ── Margin trimming ───────────────────────────────────────────────────────

    /** A row/column with fewer than this many content pixels is considered outer margin. */
    val marginContentThreshold: Int = 6,

    /**
     * A panel bbox is shrunk by trimming trailing rows/columns with fewer than this many
     * content pixels.
     */
    val tightenContentThreshold: Int = 2,

    // ── Projection-based grid detector ────────────────────────────────────────

    /**
     * A row/column is a gutter band if its content-pixel count is at or below this
     * fraction of the maximum row/column content in the cropped page.
     */
    val projectionGutterFraction: Double = 0.15,

    /**
     * The projection detector rejects gutter/content bands thinner than this many pixels.
     */
    val projectionMinBandThickness: Int = 15,

    // ── Panel sanity checks ───────────────────────────────────────────────────

    /**
     * Reject any candidate whose bbox area is smaller than this fraction of the page.
     */
    val minPanelAreaFraction: Double = 0.02,

    /**
     * A single panel covering at least this fraction of the page is treated as a splash
     * or detection collapse → fallback to Fit Whole.
     */
    val wholePagePanelThreshold: Double = 0.95,

    /**
     * If any two panels overlap by more than this fraction of the smaller panel, treat as
     * detection confusion → fallback.
     */
    val overlapRejectFraction: Double = 0.25,

    /**
     * A panel must span at least this fraction of the page in either dimension.
     */
    val minPanelDimensionFraction: Double = 0.14,

    /** Total panel area must be at least this fraction of the page. */
    val minTotalCoverageFraction: Double = 0.3,

    /**
     * Reject detections whose summed panel area exceeds this fraction of the page.
     */
    val maxSummedCoverageFraction: Double = 1.05,

    // ── Duplicate removal ─────────────────────────────────────────────────────

    /**
     * When one bbox contains ≥ this fraction of the smaller overlapping bbox, treat them
     * as duplicates and keep only the smaller (tighter) one.
     */
    val dedupOverlapFraction: Double = 0.6,

    // ── Internal gutter splitting ─────────────────────────────────────────────

    /** Maximum recursion depth for splitSinglePanelRecursively. */
    val maxInternalGutterSplitDepth: Int = 3,

    /**
     * A row/column inside a CC bbox is a real internal gutter if at least this fraction
     * of its pixels are flood-fill gutter pixels.
     */
    val internalGutterFloodFillFraction: Double = 0.3,

    /**
     * An internal gutter run thicker than this fraction of the bbox's perpendicular
     * dimension is rejected as a false gutter.
     */
    val internalGutterMaxFraction: Double = 0.25,

    /**
     * Ignore internal gutters this close to the panel edge (fraction of panel dimension).
     */
    val internalGutterEdgeMargin: Double = 0.1,

    /** An internal gutter must be at least this many pixels thick to trigger a split. */
    val internalGutterMinThickness: Int = 4,

    /**
     * When scanning for internal gutters, sample only the inner
     * `(1 - 2 * this)` fraction on the perpendicular axis.
     */
    val internalGutterInnerSampleInset: Double = 0.1,

    // ── Energy-valley gutter confirmation (issue #788) ────────────────────────

    /**
     * A suspected column gutter (inferred from other non-suspicious row bands) is confirmed when
     * the minimum luma-gradient energy in the search window is below this fraction of the median
     * energy across all columns of that row band. Lower = stricter (requires a more pronounced
     * valley). A genuine blank gutter column has near-zero energy while dithered/art panel
     * columns are high. Diagonal-layout pages can produce moderate energy at the candidate position
     * (≈0.29 of median) even when no gutter is present → keep the threshold tight (< 0.25) to
     * leave enough separation above the diagonal-layout signal and below the real gutter signal.
     */
    val energyValleyDepthRatio: Double = 0.25,

    /**
     * Half-width of the search window around a candidate gutter x (as a fraction of the cropped
     * page width). The valley minimum is found within [candidateX ± width × this].
     *
     * Keep this tight: a large window allows the search to stray into white-space regions of a
     * genuine full-width panel and cause a false split. The gutter is never more than a few
     * percent of the width away from the candidate position derived from the non-suspicious row.
     */
    val energyValleyWindowFraction: Double = 0.05,

    /**
     * Minimum fraction of rows in the suspicious band that must have a **flood-fill gutter** pixel
     * at the candidate column before the energy-valley check is attempted. Flood-fill gutter pixels
     * are background pixels reachable from the page border — this excludes internal panel
     * white-space that is enclosed by panel content and not connected to any actual inter-panel gap.
     * A real bubble occludes only part of the gutter height; rows outside the bubble retain
     * border-connected background at the gutter column, so this fraction is non-trivial. A genuine
     * full-width panel's interior white-space is not reachable from the border, so the fraction is
     * near zero and the check is skipped — preventing false splits on splash rows and real pages
     * where a full-width panel happens to have a low-energy column at the candidate position.
     */
    val energyValleyMinPartialGutterFraction: Double = 0.15,
)
