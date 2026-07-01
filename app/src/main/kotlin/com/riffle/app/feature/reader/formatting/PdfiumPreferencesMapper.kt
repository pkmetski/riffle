package com.riffle.app.feature.reader.formatting

import com.riffle.core.domain.FormattingPreferences
import org.readium.adapter.pdfium.navigator.PdfiumPreferences
import org.readium.r2.navigator.preferences.Axis
import org.readium.r2.navigator.preferences.Fit
import org.readium.r2.navigator.preferences.ReadingProgression

// Base page-gap scale (dp) that the user's margins multiplier (0.2f..3.0f) is applied to when
// deriving PdfiumPreferences.pageSpacing. 8dp is a reasonable middle for the default 1.0x margin.
private const val MARGIN_BASE_DP = 8.0

/**
 * Maps Riffle's cross-format [FormattingPreferences] onto pdfium's native preference type.
 * PdfiumPreferences only supports 4 fields (fit, pageSpacing, readingProgression, scrollAxis) —
 * there is no backgroundColor/scroll/spread/offset. Theme, fontFamily, fontSize, lineSpacing,
 * justifyText, autoScrollWpm, and doublePageSpread have no pdfium equivalent and are ignored here.
 *
 * Axis/fit are always vertical/width, never read from [FormattingPreferences.orientation]:
 * [RenderCapabilities.PDF] hides the Reading Mode row, so orientation is never user-settable
 * on PDF. Reading its EPUB-driven default (Horizontal) would leave PDF stuck on horizontal
 * scroll with no way for the user to change it.
 */
fun FormattingPreferences.toPdfiumPreferences(): PdfiumPreferences {
    return PdfiumPreferences(
        fit = Fit.WIDTH,
        pageSpacing = margins.toDouble() * MARGIN_BASE_DP,
        readingProgression = ReadingProgression.LTR,
        scrollAxis = Axis.VERTICAL,
    )
}
