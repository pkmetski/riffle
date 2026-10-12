package com.riffle.shared.reader

/**
 * Computes the Y offset (in dp) at which the annotation actions sheet should be placed when
 * anchoring near a text selection.
 *
 * Mirrors Android's [com.riffle.app.feature.reader.HighlightPopupPositionProvider]: prefer above
 * the selection; fall back to below when there isn't enough room above.
 *
 * @param selectionY   top of the selection rect, in navigator-view dp coordinates.
 * @param selectionHeight height of the selection rect, in the same coordinate space.
 * @param sheetEstimatedHeight estimated sheet height used to determine whether "above" fits.
 * @param margin gap between the selection edge and the sheet.
 */
internal fun annotationSheetTargetY(
    selectionY: Float,
    selectionHeight: Float,
    sheetEstimatedHeight: Float = 80f,
    margin: Float = 8f,
): Float {
    val aboveY = selectionY - sheetEstimatedHeight - margin
    return if (aboveY >= 0f) aboveY else (selectionY + selectionHeight + margin)
}
