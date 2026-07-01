package com.riffle.app.feature.reader.formatting

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger

interface PdfWebViewOps {
    fun evaluateJavascript(js: String)
    fun scrollByPx(dx: Int, dy: Int)
}

class PdfFormattingApplier(
    private val ops: PdfWebViewOps,
    private val logger: Logger,
) {
    val capabilities = RenderCapabilities.PDF

    fun apply(prefs: FormattingPreferences) {
        val js = buildPdfFormattingCss(prefs)
        logger.d(LogChannel.PdfFormatting) { "apply prefs=$prefs" }
        ops.evaluateJavascript(js)
    }

    fun applyScrollDelta(px: Int) {
        ops.scrollByPx(0, px)
    }
}
