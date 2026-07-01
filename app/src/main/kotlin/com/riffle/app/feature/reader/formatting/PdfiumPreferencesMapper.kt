package com.riffle.app.feature.reader.formatting

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderOrientation
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
 * justifyText, autoScrollWpm, and doublePageSpread have no pdfium equivalent and are ignored here;
 * theme is applied at the composable layer instead (see PdfReaderScreen).
 */
fun FormattingPreferences.toPdfiumPreferences(): PdfiumPreferences {
    val axis = when (orientation) {
        ReaderOrientation.Horizontal -> Axis.HORIZONTAL
        // pdfium has no distinct "continuous" scroll mode — treat the same as Vertical for now.
        ReaderOrientation.Vertical, ReaderOrientation.Continuous -> Axis.VERTICAL
    }
    val fit = when (orientation) {
        ReaderOrientation.Horizontal -> Fit.CONTAIN
        ReaderOrientation.Vertical, ReaderOrientation.Continuous -> Fit.WIDTH
    }
    return PdfiumPreferences(
        fit = fit,
        pageSpacing = margins.toDouble() * MARGIN_BASE_DP,
        readingProgression = ReadingProgression.LTR,
        scrollAxis = axis,
    )
}
