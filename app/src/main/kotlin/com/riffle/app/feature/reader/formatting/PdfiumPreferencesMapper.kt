package com.riffle.app.feature.reader.formatting

import com.riffle.core.domain.FormattingPreferences
import org.readium.adapter.pdfium.navigator.PdfiumPreferences
import org.readium.r2.navigator.preferences.Axis
import org.readium.r2.navigator.preferences.Fit

// Extra-page-gap scale (dp) applied per unit above the default margins multiplier (1.0f).
// At margins == 1.0f the mapper emits pageSpacing = null so Readium's own default is preserved
// (avoiding a silent regression on existing PDF readers who never open the Aa sheet).
// At margins != 1.0f, pageSpacing = (margins - 1.0) * MARGIN_STEP_DP, so the min (0.2f) yields
// a modest -12.8dp adjustment and the max (3.0f) yields +32dp.
private const val MARGIN_STEP_DP = 16.0
private const val DEFAULT_MARGINS = 1.0f

/**
 * Maps Riffle's cross-format [FormattingPreferences] onto pdfium's native preference type.
 * PdfiumPreferences only supports 4 fields (fit, pageSpacing, readingProgression, scrollAxis) —
 * there is no backgroundColor/scroll/spread/offset. Theme, fontFamily, fontSize, lineSpacing,
 * justifyText, autoScrollWpm, and doublePageSpread have no pdfium equivalent and are ignored here.
 *
 * Nulls mean "defer to Readium's default" — critical so the mapper does not silently override
 * publication-metadata-driven defaults (e.g. readingProgression on Arabic/Hebrew PDFs) or
 * Readium's baked-in resolved defaults for users who never open the Aa sheet.
 *
 * Axis/fit are always vertical/width, never read from [FormattingPreferences.orientation]:
 * [RenderCapabilities.PDF] hides the Reading Mode row, so orientation is never user-settable
 * on PDF. Reading its EPUB-driven default (Horizontal) would leave PDF stuck on horizontal
 * scroll with no way for the user to change it.
 */
fun FormattingPreferences.toPdfiumPreferences(): PdfiumPreferences {
    val pageSpacing: Double? = if (margins == DEFAULT_MARGINS) {
        null
    } else {
        (margins - DEFAULT_MARGINS).toDouble() * MARGIN_STEP_DP
    }
    return PdfiumPreferences(
        fit = Fit.WIDTH,
        pageSpacing = pageSpacing,
        readingProgression = null,
        scrollAxis = Axis.VERTICAL,
    )
}
