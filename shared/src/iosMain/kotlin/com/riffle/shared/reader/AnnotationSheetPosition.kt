package com.riffle.shared.reader

/**
 * Computes the Y offset (in dp) at which the annotation actions sheet should be placed when
 * anchoring near a text selection.
 *
 * Unlike Android (which prefers above), iOS prefers *below* the selection so that the top
 * selection handle — which appears right at [selectionY] and extends above it — is never covered
 * by the sheet. Falling back to above when the sheet would go off the bottom of the screen.
 *
 * @param selectionY   top of the selection rect, in navigator-view dp coordinates.
 * @param selectionHeight height of the selection rect, in the same coordinate space.
 * @param sheetEstimatedHeight estimated sheet height used to determine whether "below" fits.
 * @param margin gap between the selection edge and the sheet.
 * @param minAboveY minimum acceptable Y for the "above" fallback (safe-area top + margin).
 * @param maxBelowY maximum acceptable Y for the sheet bottom in "below" position (screen height).
 */
internal fun annotationSheetTargetY(
    selectionY: Float,
    selectionHeight: Float,
    sheetEstimatedHeight: Float = 80f,
    margin: Float = 8f,
    minAboveY: Float = 0f,
    maxBelowY: Float = Float.MAX_VALUE,
): Float {
    val belowY = selectionY + selectionHeight + margin
    val belowFits = belowY + sheetEstimatedHeight <= maxBelowY
    return if (belowFits) belowY else (selectionY - sheetEstimatedHeight - margin).coerceAtLeast(minAboveY)
}
