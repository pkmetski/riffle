package com.riffle.core.domain.comic.panel

/**
 * A developer-authored record of a panel detection failure (ADR 0062).
 * [tappedX] and [tappedY] are pixel coordinates in the original image space.
 * [tappedPanelIndex] is the index into [detectedPanels] if a panel region was tapped,
 * or null if the tap landed in blank space (Missed panel case).
 */
data class PanelDetectionReport(
    val bookId: String,
    val pageIndex: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val detectedPanels: List<PanelRegion>,
    val detectedSource: PanelSource,
    val failureType: PanelDetectionFailureType,
    val notes: String,
    val tappedX: Int?,
    val tappedY: Int?,
    val tappedPanelIndex: Int?,
    val drawnPanels: List<PanelRegion> = emptyList(),
    val drawnBoundaries: List<PanelBoundaryLine> = emptyList(),
    val expectedPanelOrder: List<Int>? = null,
    val falsePanelIndices: List<Int>? = null,
)
